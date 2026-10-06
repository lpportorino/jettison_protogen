"""gate-trace's test suite: real children, real signals, a real make jobserver.

Every transparency guarantee README.md lists is held by a test here that drives
the shipped `bin/` shims as a caller would, under BOTH dash and bash, and most
of them compare the ON path against the OFF path: the OFF path is a plain
`exec`, so it is the definition of what the caller would have seen without the
wrapper, and "ON equals OFF" is the transparency claim stated as an assertion.

HERMETIC: every run lives in a throw-away directory under the tempdir (TMPDIR
is honoured), every git repository is created here with global and system
configuration disabled, and the caller's GIT_*, MAKEFLAGS and telemetry
variables are scrubbed. No network. No sleep is used to synchronise: a child
reports readiness over an inherited pipe and is released by closing another.
The timeouts below are FAILURE BOUNDS that turn a hang into a red, never waits.

Run through `run_tests.sh`, or directly:
    python3 -I -S test_gate_trace.py [--json] [TestClass.test_name ...]
Exit 0 all passed; 1 a test failed, errored or was skipped; 2 cannot run (a
missing tool, a name that selects nothing).
"""

import hashlib
import importlib
import json
import os
import re
import resource
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.dirname(HERE)
BIN = os.path.join(TOOL, "bin")
LIB = os.path.join(TOOL, "lib")


def _load_tool():
    """The tool's own modules, from the lib/ beside this file."""
    sys.path.insert(0, LIB)
    return tuple(importlib.import_module(n) for n in ("trace_schema", "trace_run", "trace_store"))


S, trace_run, trace_store = _load_tool()

SHELLS = ("dash", "bash")
TIMEOUT = 60
REQUIRED_TOOLS = (
    "dash",
    "bash",
    "make",
    "git",
    "timeout",
    "dd",
    "yes",
    "head",
    "cat",
    "ls",
    "mkfifo",
)
SCRUBBED_PREFIXES = ("GIT_", "MAKE", "MFLAGS", "PYTHON")
SCRUBBED = ("TELEMETRY_DIR", "TRACEPARENT", "TRACESTATE")


def tool(name):
    return os.path.join(BIN, name)


def base_env():
    env = {
        k: v
        for k, v in os.environ.items()
        if not k.startswith(SCRUBBED_PREFIXES) and k not in SCRUBBED
    }
    env["GIT_CONFIG_GLOBAL"] = os.devnull
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    return env


def run(argv, env, cwd=None, timeout=TIMEOUT, **kwargs):
    kwargs.setdefault("stdin", subprocess.DEVNULL)
    return subprocess.run(argv, env=env, cwd=cwd, capture_output=True, timeout=timeout, **kwargs)


def no_core():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def read_line(fd, timeout=TIMEOUT):
    """One newline-terminated line from `fd`, or an assertion failure."""
    buf = b""
    deadline = time.monotonic() + timeout
    while not buf.endswith(b"\n"):
        ready, _, _ = select.select([fd], [], [], max(0.0, deadline - time.monotonic()))
        if not ready:
            raise AssertionError("the child never reported over its pipe")
        chunk = os.read(fd, 4096)
        if not chunk:
            raise AssertionError("the child closed its pipe before reporting")
        buf += chunk
    return buf.decode()


def drain(fd):
    """Everything left in `fd` once every writer has closed it."""
    out = b""
    while True:
        ready, _, _ = select.select([fd], [], [], TIMEOUT)
        if not ready:
            raise AssertionError("a pipe never reached end of file")
        chunk = os.read(fd, 4096)
        if not chunk:
            return out.decode()
        out += chunk


def signal_line(status_text, field):
    for line in status_text.splitlines():
        if line.startswith(field + ":"):
            return line
    raise AssertionError(f"no {field} line in /proc status")


CHILD = r"""
import os, signal, sys
mode, ready, release = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
names = {getattr(signal, n): n for n in ("SIGINT", "SIGQUIT", "SIGTERM", "SIGHUP")}
if mode == "wait":
    for sig in names:
        signal.signal(sig, signal.SIG_DFL)
else:
    def note(sig, _frame):
        os.write(ready, f"got {names[sig]}\n".encode())
    for sig in names:
        signal.signal(sig, note)
os.write(ready, f"{os.getpid()}\n".encode())
while os.read(release, 1):
    pass
"""


class Fixture(unittest.TestCase):
    """A throw-away git repository per class and a scratch directory per test."""

    @classmethod
    def setUpClass(cls):
        cls.root = tempfile.mkdtemp(prefix="gate-trace-")
        os.chmod(cls.root, 0o755)
        cls.env = base_env()
        cls.repo = os.path.join(cls.root, "repo")
        os.mkdir(cls.repo)
        for argv in (
            ["git", "init", "-q"],
            [
                "git",
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@t",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "base",
            ],
        ):
            subprocess.run(argv, cwd=cls.repo, env=cls.env, check=True, capture_output=True)
        cls.child = os.path.join(cls.root, "child.py")
        with open(cls.child, "w", encoding="utf-8") as handle:
            handle.write(CHILD)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.root, ignore_errors=True)

    def setUp(self):
        self.tmp = tempfile.mkdtemp(dir=self.root)
        os.chmod(self.tmp, 0o755)
        self.addCleanup(shutil.rmtree, self.tmp, True)

    # -- runs -----------------------------------------------------------------

    def start_run(self, entrypoint="test"):
        runs = os.path.join(self.tmp, "runs")
        result = run(
            [tool("trace-start"), entrypoint, "--root", runs, "--toolchain", "fixture=1"],
            self.env,
            cwd=self.repo,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        env = dict(self.env)
        lines = result.stdout.decode().splitlines()
        self.assertEqual(len(lines), 2, lines)
        for line in lines:
            match = re.fullmatch(r"([A-Z_]+)='([^']*)'; export \1", line)
            self.assertIsNotNone(match, line)
            env[match.group(1)] = match.group(2)
        self.assertEqual(sorted(env.keys() - self.env.keys()), ["TELEMETRY_DIR", "TRACEPARENT"])
        return env["TELEMETRY_DIR"], env

    def finish(self, run_dir):
        return run([tool("trace-finish"), run_dir], self.env)

    def finding_list(self, result):
        """Every finding code trace-finish reported, one entry per finding."""
        return sorted(
            line.split(":")[1].strip()
            for line in result.stderr.decode().splitlines()
            if line.startswith("trace-finish: ") and not line.startswith("trace-finish: FAIL")
        )

    def finding_codes(self, result):
        return set(self.finding_list(result))

    def spans(self, run_dir):
        out = {}
        spans_dir = os.path.join(run_dir, S.SPANS_DIR)
        for entry in sorted(os.listdir(spans_dir)):
            if entry.endswith(S.SPAN_SUFFIX):
                with open(os.path.join(spans_dir, entry), encoding="utf-8") as handle:
                    record = json.load(handle)
                out[record["name"]] = record
        return out

    def only_span(self, run_dir):
        spans = self.spans(run_dir)
        self.assertEqual(len(spans), 1, sorted(spans))
        return next(iter(spans.values()))

    def wrapped(self, shell, name, *command, kind=None):
        # The shell by absolute path, so a test that empties PATH still starts it.
        argv = [shutil.which(shell) or shell, tool("trace-run"), name]
        if kind:
            argv += ["--kind", kind]
        return argv + ["--", *command]

    def both_modes(self, shell, command, **kwargs):
        """(OFF result, ON result, ON run dir) for one wrapped command."""
        off = run(self.wrapped(shell, "x", *command), self.env, **kwargs)
        run_dir, env = self.start_run()
        on = run(self.wrapped(shell, "x", *command), env, **kwargs)
        return off, on, run_dir

    def start_waiting(self, shell, env, mode="wait", name="w", preexec_fn=no_core):
        """Run CHILD under trace-run until it reports its pid; returns a handle."""
        ready_r, ready_w = os.pipe()
        release_r, release_w = os.pipe()
        argv = self.wrapped(
            shell, name, sys.executable, "-I", "-S", self.child, mode, str(ready_w), str(release_r)
        )
        proc = subprocess.Popen(
            argv,
            env=env,
            pass_fds=(ready_w, release_r),
            process_group=0,
            preexec_fn=preexec_fn,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        os.close(ready_w)
        os.close(release_r)
        handle = Waiting(proc, ready_r, release_w)
        self.addCleanup(handle.close)
        handle.child_pid = int(read_line(ready_r))
        return handle


class Waiting:
    """A wrapper whose child is blocked until `release()`."""

    def __init__(self, proc, ready_r, release_w):
        self.proc, self.ready_r, self.release_w = proc, ready_r, release_w
        self.child_pid = None

    def release(self):
        if self.release_w is not None:
            os.close(self.release_w)
            self.release_w = None

    def wait(self):
        self.proc.wait(timeout=TIMEOUT)
        return self.proc.returncode

    def close(self):
        self.release()
        if self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait(timeout=TIMEOUT)
        if self.proc.stderr is not None:
            self.proc.stderr.close()
        if self.ready_r is not None:
            os.close(self.ready_r)
            self.ready_r = None


# --- exit status --------------------------------------------------------------


class TestExitFidelity(Fixture):
    def test_exit_status_passthrough(self):
        for shell in SHELLS:
            for code in (0, 1, 42, 255):
                with self.subTest(shell=shell, code=code):
                    off, on, run_dir = self.both_modes(shell, ["sh", "-c", f"exit {code}"])
                    self.assertEqual(off.returncode, code)
                    self.assertEqual(on.returncode, code)
                    span = self.only_span(run_dir)
                    self.assertEqual(span["exit"], {"status": "exited", "code": code})
                    self.assertEqual(span["verdict"], "success" if code == 0 else "failure")

    def test_spawn_failure_matches_the_shell(self):
        plain = os.path.join(self.tmp, "not-executable")
        with open(plain, "w", encoding="utf-8") as handle:
            handle.write("exit 0\n")
        os.chmod(plain, 0o644)
        for shell in SHELLS:
            for command, code in ((os.path.join(self.tmp, "absent"), 127), (plain, 126)):
                with self.subTest(shell=shell, code=code):
                    off, on, run_dir = self.both_modes(shell, [command])
                    self.assertEqual(off.returncode, code)
                    self.assertEqual(on.returncode, code)
                    span = self.only_span(run_dir)
                    self.assertEqual(span["verdict"], "error")
                    self.assertEqual(span["exit"]["status"], "spawn-failed")
                    self.assertEqual(span["exit"]["code"], code)

    def test_script_without_shebang_runs_like_exec(self):
        script = os.path.join(self.tmp, "no-shebang")
        with open(script, "w", encoding="utf-8") as handle:
            handle.write("echo ran\nexit 5\n")
        os.chmod(script, 0o755)
        for shell in SHELLS:
            with self.subTest(shell=shell):
                off, on, run_dir = self.both_modes(shell, [script])
                self.assertEqual((off.returncode, off.stdout), (5, b"ran\n"))
                self.assertEqual((on.returncode, on.stdout), (5, b"ran\n"))
                self.assertEqual(self.only_span(run_dir)["exit"]["code"], 5)


# --- signals ------------------------------------------------------------------


class TestSignalFidelity(Fixture):
    def test_sigterm_and_sighup_are_forwarded(self):
        for shell in SHELLS:
            for sig in (signal.SIGTERM, signal.SIGHUP):
                with self.subTest(shell=shell, signal=sig.name):
                    run_dir, env = self.start_run()
                    waiting = self.start_waiting(shell, env)
                    os.kill(waiting.proc.pid, sig)
                    self.assertEqual(waiting.wait(), -sig)
                    span = self.only_span(run_dir)
                    self.assertEqual(span["exit"]["status"], "signaled")
                    self.assertEqual(span["exit"]["signal"], sig)
                    self.assertEqual(span["signals_forwarded"][sig.name], 1)

    def test_child_killed_by_sigkill_is_reraised(self):
        for shell in SHELLS:
            with self.subTest(shell=shell):
                run_dir, env = self.start_run()
                waiting = self.start_waiting(shell, env)
                os.kill(waiting.child_pid, signal.SIGKILL)
                self.assertEqual(waiting.wait(), -signal.SIGKILL)
                span = self.only_span(run_dir)
                self.assertEqual(span["exit"]["signal"], signal.SIGKILL)
                self.assertEqual(span["signals_forwarded"], {"SIGHUP": 0, "SIGTERM": 0})

    def test_sigint_and_sigquit_are_not_forwarded(self):
        # The child NOTES every signal instead of dying. A SIGTERM sent after
        # the signal under test is the synchronisation point: Linux delivers the
        # lowest-numbered pending signal first and CPython runs handlers in
        # signal-number order, so by the time the child notes SIGTERM any
        # forward of the lower-numbered SIGINT or SIGQUIT has been noted too.
        for shell in SHELLS:
            for sig in (signal.SIGINT, signal.SIGQUIT):
                with self.subTest(shell=shell, signal=sig.name):
                    run_dir, env = self.start_run()
                    waiting = self.start_waiting(shell, env, mode="watch")
                    os.kill(waiting.proc.pid, sig)
                    os.kill(waiting.proc.pid, signal.SIGTERM)
                    self.assertEqual(read_line(waiting.ready_r, timeout=15), "got SIGTERM\n")
                    waiting.release()
                    self.assertEqual(waiting.wait(), 0)
                    self.assertEqual(drain(waiting.ready_r), "")
                    span = self.only_span(run_dir)
                    self.assertEqual(span["verdict"], "success")
                    self.assertEqual(span["signals_forwarded"]["SIGTERM"], 1)

    def test_group_sigint_ends_child_and_the_wrapper_records_it(self):
        for shell in SHELLS:
            with self.subTest(shell=shell):
                run_dir, env = self.start_run()
                waiting = self.start_waiting(shell, env)
                os.killpg(waiting.proc.pid, signal.SIGINT)
                self.assertEqual(waiting.wait(), -signal.SIGINT)
                span = self.only_span(run_dir)
                self.assertEqual(span["exit"]["signal"], signal.SIGINT)

    def test_a_signal_during_the_spawn_is_held_and_forwarded(self):
        run_dir, env = self.start_run()
        harness = (
            "import sys; sys.path.insert(0, sys.argv[1]); import os, _signal, trace_run\n"
            "real = os.fork\n"
            "def fork():\n"
            "    os.kill(os.getpid(), _signal.SIGTERM)\n"
            "    return real()\n"
            "os.fork = fork\n"
            "trace_run.run_main(sys.argv[2:])\n"
        )
        # `cat` reads a pipe this test holds open, so it can only end by the
        # signal; without the forward it would block until the bound below.
        stdin_r, stdin_w = os.pipe()
        self.addCleanup(os.close, stdin_w)
        proc = subprocess.Popen(
            [
                sys.executable,
                "-I",
                "-S",
                "-c",
                harness,
                LIB,
                "0",
                "-",
                env["PATH"],
                "held",
                "--",
                "cat",
            ],
            env=env,
            stdin=stdin_r,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            preexec_fn=no_core,
        )
        os.close(stdin_r)
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            self.fail("the SIGTERM raised during the spawn never reached the child")
        self.assertEqual(proc.returncode, -signal.SIGTERM)
        span = self.only_span(run_dir)
        self.assertEqual(span["signals_forwarded"]["SIGTERM"], 1)
        self.assertEqual(span["exit"]["signal"], signal.SIGTERM)

    def test_pipeline_relying_on_silent_sigpipe(self):
        for shell in SHELLS:
            with self.subTest(shell=shell):
                script = (
                    f"set -o pipefail; {shell} {tool('trace-run')} p -- yes | head -n1; "
                    'echo "status=${PIPESTATUS[0]}"'
                )
                off = run(["bash", "-c", script], self.env)
                run_dir, env = self.start_run()
                on = run(["bash", "-c", script], env)
                for result in (off, on):
                    self.assertEqual(result.stdout, b"y\nstatus=141\n")
                    self.assertEqual(result.stderr, b"")
                self.assertEqual(self.only_span(run_dir)["exit"]["signal"], signal.SIGPIPE)

    def test_sigxfsz_takes_its_default_action_in_the_child(self):
        def limited():
            no_core()
            resource.setrlimit(resource.RLIMIT_FSIZE, (4096, 4096))

        big = os.path.join(self.tmp, "big")
        command = ["dd", "if=/dev/zero", f"of={big}", "bs=8192", "count=2", "status=none"]
        for shell in SHELLS:
            with self.subTest(shell=shell):
                off, on, run_dir = self.both_modes(shell, command, preexec_fn=limited)
                self.assertEqual(off.returncode, -signal.SIGXFSZ)
                self.assertEqual(on.returncode, -signal.SIGXFSZ)
                self.assertEqual(self.only_span(run_dir)["exit"]["signal"], signal.SIGXFSZ)

    def test_child_dispositions_and_mask_equal_the_off_path(self):
        def setup(ignore=(), block=()):
            def preexec():
                for sig in ignore:
                    signal.signal(sig, signal.SIG_IGN)
                if block:
                    signal.pthread_sigmask(signal.SIG_BLOCK, set(block))

            return preexec

        configs = {
            "default": setup(),
            "sigpipe-ignored": setup(ignore=(signal.SIGPIPE,)),
            "sighup-ignored": setup(ignore=(signal.SIGHUP,)),
            "terminal-ignored": setup(ignore=(signal.SIGINT, signal.SIGQUIT)),
            "sigusr1-blocked": setup(block=(signal.SIGUSR1,)),
            "sigchld-ignored": setup(ignore=(signal.SIGCHLD,)),
        }
        pipe_bit = 1 << (signal.SIGPIPE - 1)
        for shell in SHELLS:
            for label, preexec in configs.items():
                with self.subTest(shell=shell, caller=label):
                    off, on, _ = self.both_modes(
                        shell, ["cat", "/proc/self/status"], preexec_fn=preexec
                    )
                    self.assertEqual(off.returncode, 0)
                    self.assertEqual(on.returncode, 0)
                    for field in ("SigIgn", "SigBlk"):
                        self.assertEqual(
                            signal_line(on.stdout.decode(), field),
                            signal_line(off.stdout.decode(), field),
                        )
                    ignored = int(signal_line(on.stdout.decode(), "SigIgn").split()[1], 16)
                    self.assertEqual(bool(ignored & pipe_bit), label == "sigpipe-ignored")

    def test_child_stays_in_the_callers_process_group(self):
        # process_group=0 makes the shim its own group leader, so the group id
        # the child reports must be exactly the pid the caller started.
        for shell in SHELLS:
            for env in (self.env, self.start_run()[1]):
                with self.subTest(shell=shell, on="TELEMETRY_DIR" in env):
                    proc = subprocess.Popen(
                        self.wrapped(shell, "pg", "cat", "/proc/self/stat"),
                        env=env,
                        stdin=subprocess.DEVNULL,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        process_group=0,
                    )
                    out, err = proc.communicate(timeout=TIMEOUT)
                    self.assertEqual(proc.returncode, 0, err)
                    pgrp = int(out.decode().rsplit(")", 1)[1].split()[2])
                    self.assertEqual(pgrp, proc.pid)

    def test_descriptors_pass_through_untouched(self):
        for shell in SHELLS:
            with self.subTest(shell=shell):
                read_end, write_end = os.pipe()
                try:
                    off, on, _ = self.both_modes(
                        shell, ["ls", "/proc/self/fd"], pass_fds=(write_end,)
                    )
                    self.assertEqual(on.returncode, 0)
                    self.assertEqual(on.stdout, off.stdout)
                    self.assertIn(str(write_end), on.stdout.decode().split())
                    run_dir, env = self.start_run()
                    result = run(
                        self.wrapped(shell, "fd", "sh", "-c", f"echo through >&{write_end}"),
                        env,
                        pass_fds=(write_end,),
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)
                    os.close(write_end)
                    write_end = None
                    self.assertEqual(drain(read_end), "through\n")
                finally:
                    os.close(read_end)
                    if write_end is not None:
                        os.close(write_end)

    def test_caller_ignoring_sigchld_still_gets_the_exit_status(self):
        def preexec():
            signal.signal(signal.SIGCHLD, signal.SIG_IGN)

        for shell in SHELLS:
            with self.subTest(shell=shell):
                off, on, run_dir = self.both_modes(
                    shell, ["sh", "-c", "exit 7"], preexec_fn=preexec
                )
                self.assertEqual(off.returncode, 7)
                self.assertEqual(on.returncode, 7, on.stderr)
                self.assertEqual(self.only_span(run_dir)["exit"]["code"], 7)


# --- the environment ---------------------------------------------------------------


def _locale_free(env):
    return {k: v for k, v in env.items() if k != "LANG" and not k.startswith("LC_")}


# Every BARE name the shims have assigned, or would assign without their
# reserved gate_trace__ prefix, exported by the caller: assigning any of them in
# POSIX sh would rewrite the command's environment, so each must reach the
# child untouched. The prefixed names themselves are reserved and overwritten,
# which README.md states, so they are not in this set.
SHIM_NAMES = {
    name: f"caller-{name}"
    for name in (
        "kind",
        "lib",
        "key",
        "value",
        "self",
        "ctype",
        "ignored",
        "problem",
        "newline",
        "seen",
        "pair",
    )
}
_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")

# Callers exporting SHELLOPTS, each in bash's own canonical rendering (bash
# rewrites a non-canonical value, which README lists). The shims switch off or
# change errexit, nounset, allexport, xtrace and verbose and must hand every one
# back exactly as the caller had it, set or not.
CALLER_SHELLOPTS = {
    "shellopts-pipefail": "braceexpand:hashall:interactive-comments:pipefail",
    "shellopts-errexit-nounset": (
        "braceexpand:errexit:hashall:interactive-comments:nounset:pipefail"
    ),
    "shellopts-allexport": "allexport:braceexpand:hashall:interactive-comments",
    "shellopts-xtrace-verbose": "braceexpand:hashall:interactive-comments:verbose:xtrace",
}

# Shell-special variables each shell rewrites, drops or re-exports at start-up
# when a caller exports them, before any line of a shim runs. README.md lists
# the class under what still differs from a plain exec.
SHELL_SPECIALS = {
    "dash": {"IFS", "OPTIND", "PPID"},
    "bash": {
        "IFS",
        "OPTIND",
        "PPID",
        "PS1",
        "PS2",
        "PS4",
        "BASHPID",
        "RANDOM",
        "BASH_ARGV0",
        "BASH_VERSION",
        "BASHOPTS",
        "LINENO",
        "BASH_ENV",
    },
}


def _parse_env(output):
    return dict(item.split("=", 1) for item in output.decode().split("\0") if item)


class TestEnvironment(Fixture):
    def child_env(self, shell, env, cwd=None):
        result = run(self.wrapped(shell, "env", "env", "-0"), env, cwd=cwd)
        self.assertEqual(result.returncode, 0, result.stderr)
        return _parse_env(result.stdout)

    def callers(self):
        # A name that is not an identifier, and an OLDPWD naming a directory,
        # ride every caller: bash passes both through, so a regression on the
        # bash path stays visible (dash drops the first; see the oracle below).
        bare = dict(_locale_free(self.env), **SHIM_NAMES)
        bare.update({"a.b": "not-an-identifier", "OLDPWD": self.tmp})
        return {
            "lc-ctype-unset": bare,
            "lc-ctype-c": dict(bare, LC_CTYPE="C"),
            "lc-ctype-posix": dict(bare, LC_CTYPE="POSIX"),
            "lc-ctype-empty": dict(bare, LC_CTYPE=""),
            "lc-ctype-utf-8": dict(bare, LC_CTYPE="C.UTF-8"),
            "python-variables": dict(
                bare, PYTHONPATH="/nonexistent", PYTHONDONTWRITEBYTECODE="1", PYTHONUTF8="1"
            ),
            **{label: dict(bare, SHELLOPTS=value) for label, value in CALLER_SHELLOPTS.items()},
        }

    def test_child_environment_equals_the_off_path(self):
        # The child must see the caller's environment with only TELEMETRY_DIR
        # and the advanced TRACEPARENT added. The unset, C and POSIX cases are
        # the ones CPython's locale coercion rewrites, and the empty value must
        # stay set and empty rather than becoming unset.
        for shell in SHELLS:
            for label, caller in self.callers().items():
                with self.subTest(shell=shell, caller=label):
                    off = self.child_env(shell, caller)
                    run_dir, on_env = self.start_run()
                    on_caller = dict(
                        caller,
                        TELEMETRY_DIR=on_env["TELEMETRY_DIR"],
                        TRACEPARENT=on_env["TRACEPARENT"],
                    )
                    on = self.child_env(shell, on_caller)
                    span = self.only_span(run_dir)
                    expected = dict(
                        off,
                        TELEMETRY_DIR=on_env["TELEMETRY_DIR"],
                        TRACEPARENT=f"00-{span['trace_id']}-{span['span_id']}-01",
                    )
                    self.assertEqual(on, expected)

    def test_off_path_environment_equals_a_plain_exec(self):
        # The test above cannot see a defect the OFF path shares with the ON
        # path, because OFF is its oracle. This one holds OFF against the
        # command run with no shim at all. Per shell, the caller leaves out the
        # inputs that SHELL is known to edit before any line of the shim runs,
        # and no more, so anything else the shim changes shows up here; README
        # lists the edits under what still differs from a plain exec. Both
        # shells add PWD when it is absent or stale and rewrite their own
        # shell-special variables; bash adds SHLVL when it is absent, drops
        # `_` and unsets an OLDPWD that is not a directory (this caller's names
        # one that is); dash drops a name that is not an identifier.
        for shell in SHELLS:
            for label, caller in self.callers().items():
                with self.subTest(shell=shell, caller=label):
                    caller = dict(caller, PWD=self.tmp)
                    if shell == "bash":
                        caller["SHLVL"] = "1"
                        caller.pop("_", None)
                    else:
                        caller = {k: v for k, v in caller.items() if _IDENTIFIER.fullmatch(k)}
                    caller = {k: v for k, v in caller.items() if k not in SHELL_SPECIALS[shell]}
                    plain = run(["env", "-0"], caller, cwd=self.tmp)
                    self.assertEqual(plain.returncode, 0, plain.stderr)
                    self.assertEqual(
                        self.child_env(shell, caller, cwd=self.tmp), _parse_env(plain.stdout)
                    )

    def test_with_path_unset_the_wrapper_searches_where_the_shell_does(self):
        # With PATH unset a shell searches its own built-in default and exports
        # nothing. The command is one that default finds and the interpreter's
        # bare fallback (/bin:/usr/bin) does not, so a wrapper that searched its
        # own default would fail where the plain exec succeeds.
        fallback = {os.path.realpath(d) for d in os.defpath.split(os.pathsep)}
        for shell in SHELLS:
            with self.subTest(shell=shell):
                probe = run([shutil.which(shell), "-c", 'printf %s "$PATH"'], {})
                shell_default = probe.stdout.decode().split(os.pathsep)
                ldconfig = [
                    d for d in shell_default if os.access(os.path.join(d, "ldconfig"), os.X_OK)
                ]
                self.assertTrue(
                    ldconfig and os.path.realpath(ldconfig[0]) not in fallback,
                    f"precondition: ldconfig must be on {shell}'s default path {shell_default} "
                    "and outside the interpreter's fallback",
                )
                _run_dir, on_env = self.start_run()
                for env in ({}, {k: on_env[k] for k in ("TELEMETRY_DIR", "TRACEPARENT")}):
                    result = run(self.wrapped(shell, "nopath", "ldconfig", "--version"), env)
                    self.assertEqual(result.returncode, 0, result.stderr)


# --- make -j -------------------------------------------------------------------

OUTER_MK = """\
all:
\t+$(TRACE_RUN) inner --kind chain -- $(MAKE) --no-print-directory -f inner.mk
"""

INNER_MK = """\
all: a b
a:
\t@$(TRACE_RUN) a -- timeout 15 sh -c 'printf "%s\\n" "$$MAKEFLAGS" >flags; printf x >ab; cat ba >/dev/null'
b:
\t@$(TRACE_RUN) b -- timeout 15 sh -c 'cat ab >/dev/null; printf y >ba'
"""


def make_version():
    out = subprocess.run(["make", "--version"], capture_output=True, check=True).stdout.decode()
    match = re.search(r"GNU Make (\d+)\.(\d+)", out)
    if match is None:
        raise AssertionError(f"not GNU make: {out.splitlines()[:1]}")
    return int(match.group(1)), int(match.group(2))


class TestJobserver(Fixture):
    def test_jobserver_survives_make_j(self):
        # Targets a and b RENDEZVOUS through two FIFOs, so they can only finish
        # when make runs them at the same time: with the jobserver lost the
        # inner make falls back to -j1 and the pair deadlocks until `timeout`
        # ends it. Pipe style is the one that passes descriptors, so it runs on
        # every make; fifo style is added where the make offers it.
        styles = [[]] if make_version() < (4, 4) else [["--jobserver-style=pipe"]]
        if make_version() >= (4, 4):
            styles.append(["--jobserver-style=fifo"])
        for shell in SHELLS:
            for style in styles:
                with self.subTest(shell=shell, style=style or "default"):
                    work = tempfile.mkdtemp(dir=self.tmp)
                    for name, text in (("outer.mk", OUTER_MK), ("inner.mk", INNER_MK)):
                        with open(os.path.join(work, name), "w", encoding="utf-8") as handle:
                            handle.write(text)
                    for fifo in ("ab", "ba"):
                        os.mkfifo(os.path.join(work, fifo))
                    run_dir, env = self.start_run()
                    result = run(
                        [
                            "make",
                            "-j2",
                            *style,
                            "--no-print-directory",
                            "-f",
                            "outer.mk",
                            f"TRACE_RUN={shell} {tool('trace-run')}",
                        ],
                        env,
                        cwd=work,
                    )
                    self.assertEqual(result.returncode, 0, result.stderr.decode())
                    self.assertNotIn(b"jobserver unavailable", result.stderr)
                    with open(os.path.join(work, "flags"), encoding="utf-8") as handle:
                        self.assertIn("--jobserver-auth=", handle.read())
                    spans = self.spans(run_dir)
                    self.assertEqual(sorted(spans), ["a", "b", "inner"])
                    root = env["TRACEPARENT"].split("-")[2]
                    self.assertEqual(spans["inner"]["parent_span_id"], root)
                    for leaf in ("a", "b"):
                        self.assertEqual(spans[leaf]["parent_span_id"], spans["inner"]["span_id"])
                    self.assertEqual(self.finish(run_dir).returncode, 0)


# --- nesting and the start marker ------------------------------------------------


class TestNesting(Fixture):
    def test_nesting_through_real_children(self):
        for shell in SHELLS:
            with self.subTest(shell=shell):
                run_dir, env = self.start_run()
                inner = f"{shell} {tool('trace-run')} leaf -- true"
                middle = f"{shell} {tool('trace-run')} middle -- sh -c '{inner}'"
                result = run(self.wrapped(shell, "outer", "sh", "-c", middle), env)
                self.assertEqual(result.returncode, 0, result.stderr)
                spans = self.spans(run_dir)
                root = env["TRACEPARENT"].split("-")[2]
                self.assertEqual(spans["outer"]["parent_span_id"], root)
                self.assertEqual(spans["middle"]["parent_span_id"], spans["outer"]["span_id"])
                self.assertEqual(spans["leaf"]["parent_span_id"], spans["middle"]["span_id"])
                self.assertEqual(self.finish(run_dir).returncode, 0)

    def test_start_marker_exists_before_the_child_runs(self):
        probe = (
            "id=${TRACEPARENT#00-*-}; id=${id%-*}; "
            'test -f "$TELEMETRY_DIR/spans/$id.start.json" && echo present'
        )
        for shell in SHELLS:
            with self.subTest(shell=shell):
                run_dir, env = self.start_run()
                result = run(self.wrapped(shell, "m", "sh", "-c", probe), env)
                self.assertEqual(result.stdout, b"present\n", result.stderr)


# --- the OFF path ----------------------------------------------------------------


class TestSearch(Fixture):
    def script(self, directory, text):
        os.makedirs(directory, exist_ok=True)
        path = os.path.join(directory, "hello")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        os.chmod(path, 0o755)

    def test_entries_are_named_and_skipped_as_dash_does(self):
        # The wrapper's search is dash's, so a script sees the same $0 ON as
        # under a dash OFF path: an empty entry names the bare file, an entry
        # ending in `/` keeps the doubled slash dash's concatenation gives, and
        # an entry holding `%` (dash's `dir%option` form) is skipped. bash
        # differs from dash in each (README lists how), so the oracle is dash.
        name_self = '#!/bin/sh\nprintf "%s\\n" "$0"\n'
        self.script(self.tmp, name_self)
        trailing = os.path.join(self.tmp, "trailing")
        self.script(trailing, name_self)
        skipped = os.path.join(self.tmp, "skipped%func")
        self.script(skipped, name_self)
        found = os.path.join(self.tmp, "found")
        self.script(found, name_self)
        base = self.env["PATH"]
        cases = {
            "an empty entry": (":" + base, "hello"),
            "an entry ending in /": (f"{trailing}/:{base}", f"{trailing}//hello"),
            "an entry holding %": (f"{skipped}:{found}:{base}", f"{found}/hello"),
        }
        _run_dir, on_env = self.start_run()
        for label, (path, dollar_zero) in cases.items():
            off = run(
                self.wrapped("dash", "search", "hello"), dict(self.env, PATH=path), cwd=self.tmp
            )
            self.assertEqual(
                (off.returncode, off.stdout.decode()), (0, dollar_zero + "\n"), off.stderr
            )
            for shell in SHELLS:
                with self.subTest(entry=label, shell=shell):
                    argv = self.wrapped(shell, "search", "hello")
                    on = run(argv, dict(on_env, PATH=path), cwd=self.tmp)
                    self.assertEqual((on.returncode, on.stdout), (0, off.stdout), on.stderr)

    def test_errors_while_searching_are_handled_as_dash_handles_them(self):
        # dash keeps searching past EVERY error and reports the LAST one that
        # is not ENOENT or ENOTDIR (so a regular file used as an entry, which
        # gives ENOTDIR, does not replace an earlier error), with ELOOP, ENAMETOOLONG and (for a slash
        # path, tried once) ENOTDIR as "not found" (127) and anything else,
        # such as a file without execute permission, as 126. A self-referencing
        # symlink gives ELOOP; an entry longer than PATH_MAX gives
        # ENAMETOOLONG, and before the wrapper started its interpreter by a
        # path, CPython's own start-up died on that entry. bash keeps
        # the first unexecutable file instead of the last error and reports a
        # slash path's ENOTDIR as 126 (README lists both), so a bash OFF path
        # is held only where it agrees; the ON path is held to dash under both
        # shells.
        found = os.path.join(self.tmp, "found")
        self.script(found, "#!/bin/sh\necho found\n")
        loop = os.path.join(self.tmp, "loop")
        os.mkdir(loop)
        os.symlink("hello", os.path.join(loop, "hello"))
        denied = os.path.join(self.tmp, "denied")
        self.script(denied, "#!/bin/sh\necho denied\n")
        os.chmod(os.path.join(denied, "hello"), 0o644)
        a_file = os.path.join(self.tmp, "a-file")
        with open(a_file, "w", encoding="utf-8"):
            pass
        too_long = os.path.join(self.tmp, *(["b" * 250] * 18))
        base = self.env["PATH"]
        cases = {
            "self-loop before a valid entry": (
                f"{loop}:{found}:{base}",
                "hello",
                0,
                b"found\n",
                True,
            ),
            "self-loop alone": (f"{loop}:{base}", "hello", 127, b"", True),
            "over-long entry before a valid entry": (
                f"{too_long}:{found}:{base}",
                "hello",
                0,
                b"found\n",
                True,
            ),
            "over-long entry alone": (f"{too_long}:{base}", "hello", 127, b"", True),
            "unexecutable file, then self-loop": (
                f"{denied}:{loop}:{base}",
                "hello",
                127,
                b"",
                False,
            ),
            "self-loop, then unexecutable file": (
                f"{loop}:{denied}:{base}",
                "hello",
                126,
                b"",
                True,
            ),
            "unexecutable file, then a regular file as an entry": (
                f"{denied}:{a_file}:{base}",
                "hello",
                126,
                b"",
                True,
            ),
            "slash path through a regular file": (base, f"{a_file}/hello", 127, b"", False),
        }
        _run_dir, on_env = self.start_run()
        for shell in SHELLS:
            for label, (path, command, code, stdout, bash_agrees) in cases.items():
                with self.subTest(shell=shell, path=label):
                    argv = self.wrapped(shell, "search", command)
                    if shell == "dash" or bash_agrees:
                        off = run(argv, dict(self.env, PATH=path))
                        self.assertEqual((off.returncode, off.stdout), (code, stdout), off.stderr)
                    on = run(argv, dict(on_env, PATH=path))
                    self.assertEqual((on.returncode, on.stdout), (code, stdout), on.stderr)

    def test_every_shim_starts_its_interpreter_by_a_path(self):
        # Started by a bare name, CPython searches PATH again during its own
        # start-up and dies on an entry too long to join, so each shim execs
        # a path. Two ways a shell can hand back a bare name are held: none
        # (the too-long entry alone, before the interpreter's own entry), and
        # dash naming an interpreter found through an EMPTY entry (the current
        # directory) by its bare name. trace-run's first case is also held by
        # the search-error test.
        too_long = os.path.join(self.tmp, *(["b" * 250] * 18))
        here = os.path.join(self.tmp, "here")
        os.mkdir(here)
        os.symlink(shutil.which("python3"), os.path.join(here, "python3"))
        variants = {
            "too-long entry": (f"{too_long}:{self.env['PATH']}", self.tmp),
            "interpreter through an empty entry": (f"{too_long}::{self.env['PATH']}", here),
        }
        dash = shutil.which("dash")
        for label, (path, cwd) in variants.items():
            with self.subTest(variant=label):
                runs = os.path.join(self.tmp, f"runs-{len(path)}")
                env = dict(self.env, PATH=path)
                start = run(
                    [dash, tool("trace-start"), "longpath", "--root", runs]
                    + ["--repo", self.repo, "--toolchain", "t=1"],
                    env,
                    cwd=cwd,
                )
                self.assertEqual(start.returncode, 0, start.stderr)
                out = start.stdout.decode()
                run_dir = re.search(r"TELEMETRY_DIR='([^']*)'", out).group(1)
                traceparent = re.search(r"TRACEPARENT='([^']*)'", out).group(1)
                on_env = dict(env, TELEMETRY_DIR=run_dir, TRACEPARENT=traceparent)
                annotate = run([dash, tool("trace-annotate"), "long.path=1"], on_env, cwd=cwd)
                self.assertEqual(annotate.returncode, 0, annotate.stderr)
                span = run(self.wrapped("dash", "span", "true"), on_env, cwd=cwd)
                self.assertEqual(span.returncode, 0, span.stderr)
                finish = run([dash, tool("trace-finish"), run_dir], env, cwd=cwd)
                self.assertEqual(finish.returncode, 0, finish.stderr)

    def test_a_shim_found_through_an_empty_entry_locates_its_library(self):
        # Through an empty PATH entry dash hands a script its bare name as $0,
        # and `command -v` answers with the same bare name, so each shim
        # locates itself as ./name in the current directory. Run from the bin
        # directory, by bare name, under both shells.
        runs = os.path.join(self.tmp, "runs")
        env = dict(self.env, PATH=os.pathsep + self.env["PATH"])

        def by_name(shell, *argv, env):
            return run([shell, "-c", 'exec "$@"', "x", *argv], env, cwd=BIN)

        for shell in SHELLS:
            with self.subTest(shell=shell):
                off = by_name(shell, "trace-run", "x", "--", "echo", "ran", env=env)
                self.assertEqual((off.returncode, off.stdout), (0, b"ran\n"), off.stderr)
                start = by_name(
                    shell,
                    "trace-start",
                    f"empty-{shell}",
                    "--root",
                    runs,
                    "--repo",
                    self.repo,
                    "--toolchain",
                    "t=1",
                    env=env,
                )
                self.assertEqual(start.returncode, 0, start.stderr)
                out = start.stdout.decode()
                run_dir = re.search(r"TELEMETRY_DIR='([^']*)'", out).group(1)
                traceparent = re.search(r"TRACEPARENT='([^']*)'", out).group(1)
                on_env = dict(env, TELEMETRY_DIR=run_dir, TRACEPARENT=traceparent)
                on = by_name(shell, "trace-run", "x", "--", "echo", "ran", env=on_env)
                self.assertEqual((on.returncode, on.stdout), (0, b"ran\n"), on.stderr)
                annotate = by_name(shell, "trace-annotate", "found.by=name", env=on_env)
                self.assertEqual(annotate.returncode, 0, annotate.stderr)
                finish = by_name(shell, "trace-finish", run_dir, env=env)
                self.assertEqual(finish.returncode, 0, finish.stderr)


class TestOffPath(Fixture):
    def fake_python(self):
        fake = os.path.join(self.tmp, "fake")
        os.mkdir(fake)
        marker = os.path.join(self.tmp, "python-started")
        path = os.path.join(fake, "python3")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(f"#!/bin/sh\n: >'{marker}'\nexit 97\n")
        os.chmod(path, 0o755)
        return fake, marker

    def test_off_path_runs_zero_python(self):
        fake, marker = self.fake_python()
        for shell in SHELLS:
            with self.subTest(shell=shell):
                env = dict(self.env, PATH=fake + os.pathsep + self.env["PATH"])
                off = run(self.wrapped(shell, "x", "sh", "-c", "exit 3"), env)
                self.assertEqual(off.returncode, 3, off.stderr)
                annotate = run([shell, tool("trace-annotate"), "a.b=1"], env)
                self.assertEqual(annotate.returncode, 0, annotate.stderr)
                self.assertFalse(os.path.exists(marker), "the OFF path started python3")
                # The control: the same probe DOES see an ON-mode start.
                _run_dir, on_env = self.start_run()
                on_env["PATH"] = env["PATH"]
                on = run(self.wrapped(shell, "x", "sh", "-c", "exit 3"), on_env)
                self.assertEqual(on.returncode, 97)
                self.assertTrue(os.path.exists(marker), "the probe cannot see python3 at all")
                os.unlink(marker)

    def test_interpreter_starts_isolated(self):
        # The flags the shims exec the interpreter with ARE the contract, so
        # they are read off the exec itself by a recording python3 on PATH.
        fake = os.path.join(self.tmp, "fake")
        os.mkdir(fake)
        record = os.path.join(self.tmp, "argv")
        with open(os.path.join(fake, "python3"), "w", encoding="utf-8") as handle:
            handle.write(f"#!/bin/sh\nprintf '%s\\n' \"$1\" \"$2\" >'{record}'\nexit 0\n")
        os.chmod(os.path.join(fake, "python3"), 0o755)
        _run_dir, env = self.start_run()
        env["PATH"] = fake + os.pathsep + env["PATH"]
        for argv in (
            self.wrapped("dash", "iso", "true"),
            ["dash", tool("trace-annotate"), "a.b=1"],
        ):
            with self.subTest(argv=argv[1]):
                self.assertEqual(run(argv, env).returncode, 0)
                with open(record, encoding="utf-8") as handle:
                    self.assertEqual(handle.read(), "-I\n-S\n")

    def test_the_wrapper_is_bytecode_cached(self):
        _run_dir, env = self.start_run()
        cache = os.path.join(LIB, "__pycache__")
        for entry in os.listdir(cache) if os.path.isdir(cache) else ():
            if entry.startswith(("trace_run.", "trace_schema.")):
                os.unlink(os.path.join(cache, entry))
        result = run(self.wrapped("dash", "cached", "true"), env)
        self.assertEqual(result.returncode, 0, result.stderr)
        cached = sorted(e.split(".")[0] for e in os.listdir(cache) if e.endswith(".pyc"))
        self.assertIn("trace_run", cached)
        self.assertIn("trace_schema", cached)


# --- refusals ---------------------------------------------------------------------


class TestRefusals(Fixture):
    def assert_refused(self, argv, env, code, marker, **kwargs):
        result = run(argv, env, **kwargs)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn(f"refused [{code}]".encode(), result.stderr)
        self.assertFalse(os.path.exists(marker), "the command ran")

    def test_unwritable_directory_is_refused(self):
        run_dir, env = self.start_run()
        outbox = os.path.join(self.tmp, "outbox")
        os.mkdir(outbox)
        os.chmod(outbox, 0o777)
        marker = os.path.join(outbox, "ran")
        preexec = None
        if os.geteuid() == 0:
            # Root writes through any mode bits, so the refusal is proven as an
            # unprivileged identity; the control below proves the switch works.
            def preexec():
                os.setgroups([])
                os.setgid(65534)
                os.setuid(65534)

        spans_dir = os.path.join(run_dir, S.SPANS_DIR)
        for shell in SHELLS:
            with self.subTest(shell=shell):
                os.chmod(spans_dir, 0o555)
                command = self.wrapped(shell, "w", "sh", "-c", f": >'{marker}'")
                self.assert_refused(
                    command, env, "telemetry-dir-unwritable", marker, preexec_fn=preexec
                )
                annotate = run([shell, tool("trace-annotate"), "a.b=1"], env, preexec_fn=preexec)
                self.assertEqual(annotate.returncode, 2)
                self.assertIn(b"refused [telemetry-dir-unwritable]", annotate.stderr)
                os.chmod(spans_dir, 0o777)
                control = run(command, env, preexec_fn=preexec)
                self.assertEqual(control.returncode, 0, control.stderr)
                self.assertTrue(os.path.exists(marker))
                os.unlink(marker)

    def test_environment_refusals_are_named(self):
        run_dir, env = self.start_run()
        marker = os.path.join(self.tmp, "ran")
        a_file = os.path.join(self.tmp, "a-file")
        open(a_file, "w").close()
        bare_dir = os.path.join(self.tmp, "bare")
        os.mkdir(bare_dir)
        no_tools = os.path.join(self.tmp, "no-tools")
        os.mkdir(no_tools)
        # A symlink to itself: stat fails with ELOOP even as root, which is
        # neither "missing" nor "not a directory".
        loop = os.path.join(self.tmp, "loop")
        os.symlink(loop, loop)
        cases = {
            "launcher": {"PATH": no_tools},
            "telemetry-dir-empty": {"TELEMETRY_DIR": ""},
            "telemetry-dir-unusable": {"TELEMETRY_DIR": loop},
            "telemetry-dir-relative": {"TELEMETRY_DIR": os.path.relpath(run_dir, self.tmp)},
            "telemetry-dir-missing": {"TELEMETRY_DIR": os.path.join(self.tmp, "absent")},
            "telemetry-dir-not-a-directory": {"TELEMETRY_DIR": a_file},
            "telemetry-dir-not-a-run": {"TELEMETRY_DIR": bare_dir},
            "traceparent-missing": {"TRACEPARENT": None},
            "traceparent-malformed": {"TRACEPARENT": "01-" + env["TRACEPARENT"][3:]},
        }
        for shell in SHELLS:
            for code, change in cases.items():
                with self.subTest(shell=shell, code=code):
                    case_env = dict(env)
                    for key, value in change.items():
                        if value is None:
                            case_env.pop(key)
                        else:
                            case_env[key] = value
                    command = self.wrapped(shell, "e", "sh", "-c", f": >'{marker}'")
                    self.assert_refused(command, case_env, code, marker)

    def test_an_old_interpreter_is_refused_by_name(self):
        _run_dir, env = self.start_run()
        marker = os.path.join(self.tmp, "ran")
        harness = (
            "import sys; sys.path.insert(0, sys.argv[1]); import trace_run\n"
            "sys.version_info = (3, 11, 9)\n"
            "trace_run.run_main(sys.argv[2:])\n"
        )
        argv = [sys.executable, "-I", "-S", "-c", harness, LIB, "0", "-", env["PATH"], "old", "--"]
        self.assert_refused([*argv, "sh", "-c", f": >'{marker}'"], env, "python-too-old", marker)

    def test_the_library_refuses_an_empty_or_dash_command_word(self):
        # The shim refuses it first in both modes; this holds the interpreter's
        # own grammar to the same rule for any caller that reaches it directly.
        _run_dir, env = self.start_run()
        marker = os.path.join(self.tmp, "ran")
        harness = "import sys; sys.path.insert(0, sys.argv[1]); import trace_run; trace_run.run_main(sys.argv[2:])"
        argv = [sys.executable, "-I", "-S", "-c", harness, LIB, "0", "-", env["PATH"], "ok", "--"]
        self.assert_refused([*argv, "-c", "sh", "-c", f": >'{marker}'"], env, "usage", marker)
        self.assert_refused([*argv, ""], env, "usage", marker)

    def test_usage_is_refused_in_both_modes(self):
        marker = os.path.join(self.tmp, "ran")
        bad = (
            ["-x", "--", "sh", "-c", f": >'{marker}'"],
            ["ok", "--kind", "nope", "--", "sh", "-c", f": >'{marker}'"],
            ["ok", "--kind", "gate", "--kind", "test", "--", "sh", "-c", f": >'{marker}'"],
            ["ok", "sh", "-c", f": >'{marker}'"],
            ["ok", "--"],
            ["ok", "--", "-c", "sh", "-c", f": >'{marker}'"],
            ["ok", "--", ""],
            [],
        )
        _run_dir, on_env = self.start_run()
        for shell in SHELLS:
            for env in (self.env, on_env):
                for args in bad:
                    with self.subTest(shell=shell, on="TELEMETRY_DIR" in env, args=args):
                        self.assert_refused([shell, tool("trace-run"), *args], env, "usage", marker)


# --- the sh and Python grammars agree ----------------------------------------------

NAMES = [
    "a",
    "Z",
    "9",
    "lint-python",
    "check/fixtures",
    "x:y+z=w@v.u_t",
    "-x",
    "_x",
    ".x",
    "",
    "a b",
    "a\tb",
    "a\nb",
    "é",
    "a\x7f",
    "a*",
    "a[b]",
    "a'b",
    'a"b',
    "a$b",
    "a\\b",
    "x" * 128,
    "x" * 129,
    b"\xff",
    b"a\xffb",
]
KIND_CORPUS = [*S.KINDS, "", "Chain", "chains", "--", "gate ", "boundary2"]
KEYS = [
    "a.b",
    "a1.b_2",
    "a.b.c.d",
    "a",
    ".a",
    "a.",
    "a..b",
    "1a.b",
    "a.1b",
    "_a.b",
    "a._b",
    "A.b",
    "a.B",
    "a-b.c",
    "a.b-c",
    "process.pid",
    "host.x",
    "vcs.y",
    "processx.y",
    "a.process",
    "é.b",
    "a.b c",
    "a." + "b" * 126,
    "a." + "b" * 127,
    b"\xff.b",
]


def as_text(value):
    return os.fsdecode(value) if isinstance(value, bytes) else value


class TestGrammarAgreement(Fixture):
    def test_span_name_grammar_agrees(self):
        for shell in SHELLS:
            for name in NAMES:
                with self.subTest(shell=shell, name=name):
                    result = run([shell, tool("trace-run"), name, "--", "true"], self.env)
                    accepted = S.name_problem(as_text(name)) is None
                    self.assertEqual(result.returncode, 0 if accepted else 2, result.stderr)

    def test_kind_grammar_agrees(self):
        for shell in SHELLS:
            for kind in KIND_CORPUS:
                with self.subTest(shell=shell, kind=kind):
                    result = run(
                        [shell, tool("trace-run"), "n", "--kind", kind, "--", "true"], self.env
                    )
                    self.assertEqual(result.returncode, 0 if kind in S.KINDS else 2, result.stderr)

    def test_annotation_key_grammar_agrees(self):
        for shell in SHELLS:
            for key in KEYS:
                with self.subTest(shell=shell, key=key):
                    pair = key + b"=v" if isinstance(key, bytes) else key + "=v"
                    result = run([shell, tool("trace-annotate"), pair], self.env)
                    accepted = S.attribute_key_problem(as_text(key)) is None
                    self.assertEqual(result.returncode, 0 if accepted else 2, result.stderr)


# --- annotation --------------------------------------------------------------------


class TestAnnotate(Fixture):
    def test_annotations_fold_onto_their_span_and_the_run(self):
        run_dir, env = self.start_run()
        annotate = f"{tool('trace-annotate')} gate.cache=hit gate.lane=lint"
        result = run(self.wrapped("dash", "s", "sh", "-c", annotate), env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(run([tool("trace-annotate"), "run.note=whole"], env).returncode, 0)
        self.assertEqual(self.finish(run_dir).returncode, 0)
        record = trace_store.load_run(run_dir)
        span_id = self.only_span(run_dir)["span_id"]
        root = env["TRACEPARENT"].split("-")[2]
        self.assertEqual(record.attributes[span_id], {"gate.cache": "hit", "gate.lane": "lint"})
        self.assertEqual(record.attributes[root], {"run.note": "whole"})

    def test_reserved_namespace_is_refused_in_both_modes(self):
        _run_dir, on_env = self.start_run()
        for shell in SHELLS:
            for env in (self.env, on_env):
                with self.subTest(shell=shell, on="TELEMETRY_DIR" in env):
                    result = run([shell, tool("trace-annotate"), "process.pid=1"], env)
                    self.assertEqual(result.returncode, 2)
                    self.assertIn(b"refused [usage]", result.stderr)

    def test_an_overlong_value_is_cut_and_says_so(self):
        run_dir, env = self.start_run()
        value = "v" * (S.MAX_ATTRIBUTE_VALUE_CHARS + 10)
        for mode_env in (self.env, env):
            self.assertEqual(run([tool("trace-annotate"), f"a.b={value}"], mode_env).returncode, 0)
        spans_dir = os.path.join(run_dir, S.SPANS_DIR)
        (entry,) = [e for e in os.listdir(spans_dir) if S.ANNOTATION_INFIX in e]
        with open(os.path.join(spans_dir, entry), encoding="utf-8") as handle:
            record = json.load(handle)
        self.assertEqual(record["truncated"], ["a.b"])
        self.assertEqual(len(record["attributes"]["a.b"]), S.MAX_ATTRIBUTE_VALUE_CHARS)


# --- the validator -----------------------------------------------------------------


class TestValidator(Fixture):
    def setUp(self):
        super().setUp()
        self.run_dir, env = self.start_run()
        nested = f"{tool('trace-run')} c --kind test -- {tool('trace-annotate')} span.note=c"
        for argv in (
            self.wrapped("dash", "a", "true", kind="gate"),
            self.wrapped("dash", "b", "sh", "-c", nested, kind="chain"),
            [tool("trace-annotate"), "run.note=x"],
        ):
            result = run(argv, env)
            self.assertEqual(result.returncode, 0, result.stderr)
        self.env_on = env

    def copy(self):
        target = os.path.join(tempfile.mkdtemp(dir=self.tmp), "run")
        shutil.copytree(self.run_dir, target)
        return target

    def span_file(self, run_dir, name, suffix):
        spans = self.spans(run_dir)
        return os.path.join(run_dir, S.SPANS_DIR, spans[name]["span_id"] + suffix)

    def rewrite(self, path, change):
        with open(path, encoding="utf-8") as handle:
            record = json.load(handle)
        change(record)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(record, separators=(",", ":")) + "\n")

    def assert_only(self, run_dir, code, count=1):
        """trace-finish reports `code` exactly `count` times and nothing else.

        Counted, not compared as a set: a set cannot see one cause reported
        twice under the same name."""
        result = self.finish(run_dir)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(self.finding_list(result), [code] * count, result.stderr.decode())

    def test_control_a_whole_record_passes(self):
        target = self.copy()
        result = self.finish(target)
        self.assertEqual(result.returncode, 0, result.stderr)
        match = re.fullmatch(
            r"trace-finish: ok 3 span\(s\), 0 not successful, 2 annotation\(s\); run (.+)\n",
            result.stdout.decode(),
        )
        self.assertIsNotNone(match, result.stdout)
        self.assertEqual(match.group(1), os.path.abspath(target))
        again = self.finish(target)
        self.assertEqual(again.returncode, 0)
        self.assertIn(b"(already finished)", again.stdout)

    def test_a_truncated_record_is_named(self):
        target = self.copy()
        path = self.span_file(target, "a", S.SPAN_SUFFIX)
        with open(path, "rb") as handle:
            data = handle.read()
        with open(path, "wb") as handle:
            handle.write(data[: len(data) // 2])
        self.assert_only(target, "truncated-record")

    def test_an_unknown_key_is_named(self):
        target = self.copy()
        self.rewrite(self.span_file(target, "a", S.SPAN_SUFFIX), lambda r: r.update(bogus=1))
        self.assert_only(target, "unknown-key")

    def test_an_unknown_schema_version_is_named(self):
        target = self.copy()
        self.rewrite(os.path.join(target, S.RUN_FILE), lambda r: r.update(schema=2))
        self.assert_only(target, "unknown-schema")

    def test_a_dangling_parent_is_named(self):
        target = self.copy()
        stranger = trace_run.new_id(S.SPAN_ID_HEX)
        for suffix in (S.START_SUFFIX, S.SPAN_SUFFIX):
            self.rewrite(
                self.span_file(target, "a", suffix), lambda r: r.update(parent_span_id=stranger)
            )
        self.assert_only(target, "dangling-parent")

    def test_an_orphan_start_marker_is_named(self):
        # A REAL orphan: the wrapper is killed outright while its child runs,
        # so the start marker it wrote before the spawn is all that survives.
        waiting = self.start_waiting("dash", self.env_on, name="killed")
        os.kill(waiting.proc.pid, signal.SIGKILL)
        self.assertEqual(waiting.wait(), -signal.SIGKILL)
        waiting.release()
        self.assert_only(self.run_dir, "orphan-span")

    def test_every_other_integrity_clause_names_itself(self):
        # One planted defect per clause, each on a fresh copy, and each must be
        # reported as exactly its own code and nothing else.
        def unlink(path):
            os.unlink(path)

        def a_file(run_dir, suffix):
            return self.span_file(run_dir, "a", suffix)

        def plant_late(run_dir):
            final = self.spans(run_dir)["a"]
            record = {
                "schema": 1,
                "record": "annotation",
                "trace_id": final["trace_id"],
                "span_id": final["span_id"],
                "time_mono_ns": final["end_mono_ns"] + 1,
                "attributes": {"late.key": "v"},
                "truncated": [],
            }
            name = f"{final['span_id']}.attr.{trace_run.new_id(S.NONCE_HEX)}.json"
            with open(os.path.join(run_dir, S.SPANS_DIR, name), "w", encoding="utf-8") as handle:
                handle.write(json.dumps(record) + "\n")

        def plant_conflict(run_dir):
            env = dict(self.env_on, TELEMETRY_DIR=run_dir)
            result = run([tool("trace-annotate"), "run.note=different"], env)
            self.assertEqual(result.returncode, 0, result.stderr)

        def retrace(run_dir):
            stranger = trace_run.new_id(S.TRACE_ID_HEX)
            for suffix in (S.START_SUFFIX, S.SPAN_SUFFIX):
                self.rewrite(a_file(run_dir, suffix), lambda r: r.update(trace_id=stranger))

        cases = {
            "unmarked-span": lambda d: unlink(a_file(d, S.START_SUFFIX)),
            "start-mismatch": lambda d: self.rewrite(
                a_file(d, S.SPAN_SUFFIX), lambda r: r.update(name="renamed")
            ),
            "stray-temp": lambda d: open(os.path.join(d, S.SPANS_DIR, ".left.tmp"), "w").close(),
            "stray-file": lambda d: open(os.path.join(d, "notes.txt"), "w").close(),
            "time-inconsistent": lambda d: self.rewrite(
                a_file(d, S.SPAN_SUFFIX), lambda r: r.update(end_mono_ns=r["start_mono_ns"] - 1)
            ),
            "late-annotation": plant_late,
            "conflicting-annotation": plant_conflict,
            "foreign-trace": retrace,
            "missing-header": lambda d: unlink(os.path.join(d, S.RUN_FILE)),
            "bad-value": lambda d: self.rewrite(
                a_file(d, S.SPAN_SUFFIX), lambda r: r.update(verdict="failure")
            ),
            "missing-key": lambda d: self.rewrite(
                a_file(d, S.SPAN_SUFFIX), lambda r: r.pop("rusage")
            ),
            "unknown-record": lambda d: self.rewrite(
                a_file(d, S.SPAN_SUFFIX), lambda r: r.update(record="span-start")
            ),
            "duplicate-key": lambda d: self.edit_bytes(
                a_file(d, S.SPAN_SUFFIX), lambda b: b.replace(b"{", b'{"schema":1,', 1)
            ),
            "malformed-record": lambda d: self.edit_bytes(
                a_file(d, S.SPAN_SUFFIX), lambda _b: b"{not json}\n"
            ),
            "oversize-record": lambda d: self.edit_bytes(
                a_file(d, S.SPAN_SUFFIX), lambda _b: b" " * S.MAX_RECORD_BYTES + b"\n"
            ),
            "misnamed-record": lambda d: self.plant_annotation(
                d, name_for=self.spans(d)["a"]["span_id"]
            ),
            "dangling-annotation": lambda d: self.plant_annotation(
                d, span_id=trace_run.new_id(S.SPAN_ID_HEX)
            ),
            "too-many-attributes": self.plant_too_many,
        }
        # The marker and the final record each carry the foreign trace id.
        counts = {"foreign-trace": 2}
        for code, plant in cases.items():
            with self.subTest(code=code):
                target = self.copy()
                plant(target)
                self.assert_only(target, code, counts.get(code, 1))

        def replace_with(run_dir, make):
            # The real record moves OUTSIDE the run, so the planted entry is the
            # only thing in spans/ that is not a regular record.
            path = a_file(run_dir, S.SPAN_SUFFIX)
            outside = os.path.join(os.path.dirname(run_dir), "outside-final.json")
            os.rename(path, outside)
            make(path, outside)

        not_regular = {
            "a directory": lambda d: replace_with(d, lambda path, _outside: os.mkdir(path)),
            "a symlink": lambda d: replace_with(d, lambda path, outside: os.symlink(outside, path)),
        }
        for label, plant in not_regular.items():
            with self.subTest(code="stray-file", final_record_is=label):
                target = self.copy()
                plant(target)
                self.assert_only(target, "stray-file")

    # -- planting helpers ---------------------------------------------------------

    def edit_bytes(self, path, change):
        with open(path, "rb") as handle:
            data = handle.read()
        with open(path, "wb") as handle:
            handle.write(change(data))

    def plant_annotation(self, run_dir, span_id=None, name_for=None):
        """A copy of the run-level annotation, re-addressed to `span_id` and
        filed under the name of `name_for` (each defaulting to the other)."""
        spans_dir = os.path.join(run_dir, S.SPANS_DIR)
        root = self.env_on["TRACEPARENT"].split("-")[2]
        (source,) = [e for e in os.listdir(spans_dir) if e.startswith(root + S.ANNOTATION_INFIX)]
        with open(os.path.join(spans_dir, source), encoding="utf-8") as handle:
            record = json.load(handle)
        record["span_id"] = span_id or record["span_id"]
        target = name_for or record["span_id"]
        name = f"{target}{S.ANNOTATION_INFIX}{trace_run.new_id(S.NONCE_HEX)}{S.JSON_SUFFIX}"
        with open(os.path.join(spans_dir, name), "w", encoding="utf-8") as handle:
            handle.write(json.dumps(record, separators=(",", ":")) + "\n")

    def plant_too_many(self, run_dir):
        env = dict(self.env_on, TELEMETRY_DIR=run_dir)
        for batch in range(S.MAX_ATTRIBUTES_PER_SPAN // S.MAX_ANNOTATION_PAIRS + 1):
            pairs = [f"bulk.k{batch}_{i}=v" for i in range(S.MAX_ANNOTATION_PAIRS)]
            result = run([tool("trace-annotate"), *pairs], env)
            self.assertEqual(result.returncode, 0, result.stderr)

    # -- one root cause, one finding ----------------------------------------------

    def test_a_rejected_annotated_span_cascades_nothing(self):
        # Span c carries an annotation. A final record that parses but is
        # inconsistent must be reported once, and must still count as a span:
        # its annotation is not dangling and the run is not empty.
        def c_final(run_dir):
            return self.span_file(run_dir, "c", S.SPAN_SUFFIX)

        cases = {
            "bad-value": lambda r: r.update(
                verdict="success" if r["verdict"] != "success" else "failure"
            ),
            "time-inconsistent": lambda r: r.update(end_mono_ns=r["start_mono_ns"] - 1),
        }
        for code, change in cases.items():
            with self.subTest(code=code):
                target = self.copy()
                self.rewrite(c_final(target), change)
                self.assert_only(target, code)
        # Both of c's files unreadable: c is named but stands for nothing, so
        # its annotation must still not be reported as targeting nothing.
        with self.subTest(code="malformed-record"):
            target = self.copy()
            for suffix in (S.START_SUFFIX, S.SPAN_SUFFIX):
                self.edit_bytes(self.span_file(target, "c", suffix), lambda _b: b"{not json}\n")
            self.assert_only(target, "malformed-record", 2)

    def test_an_inconsistent_spans_annotations_are_still_judged(self):
        # Two independent defects, two findings: c's final record contradicts
        # itself, AND two annotations give c's one key different values. The
        # second is only visible if the inconsistent span still stands.
        target = self.copy()
        c_id = self.spans(target)["c"]["span_id"]
        self.rewrite(
            self.span_file(target, "c", S.SPAN_SUFFIX), lambda r: r.update(verdict="failure")
        )
        spans_dir = os.path.join(target, S.SPANS_DIR)
        (source,) = [e for e in os.listdir(spans_dir) if e.startswith(c_id + S.ANNOTATION_INFIX)]
        with open(os.path.join(spans_dir, source), encoding="utf-8") as handle:
            record = json.load(handle)
        record["attributes"]["span.note"] = "other"
        name = f"{c_id}{S.ANNOTATION_INFIX}{trace_run.new_id(S.NONCE_HEX)}{S.JSON_SUFFIX}"
        with open(os.path.join(spans_dir, name), "w", encoding="utf-8") as handle:
            handle.write(json.dumps(record, separators=(",", ":")) + "\n")
        result = self.finish(target)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(
            self.finding_codes(result),
            {"bad-value", "conflicting-annotation"},
            result.stderr.decode(),
        )

    def test_a_parent_cycle_is_reported_once(self):
        target = self.copy()
        spans = self.spans(target)
        loop_to = spans["c"]["span_id"]
        for suffix in (S.START_SUFFIX, S.SPAN_SUFFIX):
            self.rewrite(
                self.span_file(target, "b", suffix), lambda r: r.update(parent_span_id=loop_to)
            )
        result = self.finish(target)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(self.finding_codes(result), {"parent-cycle"}, result.stderr.decode())
        self.assertEqual(
            result.stderr.count(b"trace-finish: parent-cycle:"), 1, result.stderr.decode()
        )

    def test_an_unfinished_run_is_named_by_the_reader(self):
        record = trace_store.load_run(self.copy())
        self.assertEqual({f.code for f in record.findings}, {"missing-end"})

    def test_a_run_whose_only_span_is_rejected_is_not_also_empty(self):
        run_dir, env = self.start_run()
        self.assertEqual(run(self.wrapped("dash", "only", "true"), env).returncode, 0)
        only = self.only_span(run_dir)["span_id"]
        path = os.path.join(run_dir, S.SPANS_DIR, only + S.SPAN_SUFFIX)
        self.rewrite(path, lambda r: r.update(verdict="failure"))
        self.assert_only(run_dir, "bad-value")

    def test_a_run_with_no_span_is_named(self):
        run_dir, _env = self.start_run()
        self.assert_only(run_dir, "empty-run")


# --- the run header --------------------------------------------------------------------


class TestHeader(Fixture):
    def header(self, run_dir):
        with open(os.path.join(run_dir, S.RUN_FILE), encoding="utf-8") as handle:
            return json.load(handle)

    def git(self, *args):
        subprocess.run(
            ["git", "-c", "user.name=t", "-c", "user.email=t@t", *args],
            cwd=self.repo,
            env=self.env,
            check=True,
            capture_output=True,
        )

    def test_a_clean_tree_and_its_privacy_defaults(self):
        header = self.header(self.start_run()[0])
        head = (
            subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=self.repo,
                env=self.env,
                capture_output=True,
                check=True,
            )
            .stdout.decode()
            .strip()
        )
        self.assertEqual(header["vcs"]["revision"], head)
        self.assertEqual((header["vcs"]["dirty"], header["vcs"]["dirty_digest"]), (False, None))
        self.assertEqual(
            header["vcs"]["changeset"],
            {"kind": "worktree", "base": head, "tip": None, "paths": [], "path_count": 0},
        )
        self.assertIsNone(header["host"]["name"], "the host name was recorded without --hostname")
        self.assertEqual(header["toolchain"], {"fixture": "1"})

    def test_a_dirty_tree_is_digested_and_the_digest_follows_content(self):
        probe = os.path.join(self.repo, "probe.txt")
        digests = []
        try:
            for content in ("one\n", "two\n"):
                with open(probe, "w", encoding="utf-8") as handle:
                    handle.write(content)
                vcs = self.header(self.start_run()[0])["vcs"]
                self.assertTrue(vcs["dirty"])
                self.assertEqual(vcs["changeset"]["paths"], ["probe.txt"])
                digests.append(vcs["dirty_digest"])
        finally:
            os.unlink(probe)
        self.assertRegex(digests[0], r"^[0-9a-f]{64}$")
        self.assertNotEqual(digests[0], digests[1])

    def test_a_range_changeset_names_the_changed_paths(self):
        self.git("commit", "-q", "--allow-empty", "-m", "range-base")
        base = (
            subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=self.repo,
                env=self.env,
                capture_output=True,
                check=True,
            )
            .stdout.decode()
            .strip()
        )
        for name in ("x.txt", "y.txt"):
            with open(os.path.join(self.repo, name), "w", encoding="utf-8") as handle:
                handle.write(name)
        self.git("add", "x.txt", "y.txt")
        self.git("commit", "-q", "-m", "range-tip")
        runs = os.path.join(self.tmp, "runs")
        result = run(
            [
                tool("trace-start"),
                "range",
                "--root",
                runs,
                "--toolchain",
                "t=1",
                "--changeset",
                f"{base}..HEAD",
                "--hostname",
            ],
            self.env,
            cwd=self.repo,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        (run_name,) = os.listdir(runs)
        header = self.header(os.path.join(runs, run_name))
        changeset = header["vcs"]["changeset"]
        self.assertEqual((changeset["kind"], changeset["base"]), ("range", base))
        self.assertEqual(changeset["tip"], header["vcs"]["revision"])
        self.assertEqual((changeset["paths"], changeset["path_count"]), (["x.txt", "y.txt"], 2))
        self.assertEqual(header["host"]["name"], os.uname().nodename)

    def test_start_refuses_when_git_cannot_run(self):
        # An unusable environment is exit 2 with a named reason, never an
        # internal error: git absent from PATH, and a --repo that is missing.
        only_python = os.path.join(self.tmp, "only-python")
        os.mkdir(only_python)
        os.symlink(shutil.which("python3"), os.path.join(only_python, "python3"))
        runs = os.path.join(self.tmp, "runs")
        cases = {
            "git not on PATH": (dict(self.env, PATH=only_python), self.repo, b"cannot start git"),
            "missing --repo": (self.env, os.path.join(self.tmp, "absent"), b"cannot run git in"),
        }
        for label, (env, repo, reason) in cases.items():
            with self.subTest(case=label):
                result = run(
                    [tool("trace-start"), "n", "--root", runs, "--repo", repo]
                    + ["--toolchain", "t=1"],
                    env,
                )
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn(reason, result.stderr)
                self.assertNotIn(b"internal", result.stderr)

    def test_start_refuses_a_nested_run_and_a_run_without_a_toolchain(self):
        runs = os.path.join(self.tmp, "runs")
        _run_dir, env = self.start_run()
        nested = run(
            [tool("trace-start"), "n", "--root", runs, "--toolchain", "t=1"], env, cwd=self.repo
        )
        self.assertEqual(nested.returncode, 2)
        self.assertIn(b"already active", nested.stderr)
        bare = run([tool("trace-start"), "n", "--root", runs], self.env, cwd=self.repo)
        self.assertEqual(bare.returncode, 2)
        self.assertIn(b"no toolchain term", bare.stderr)
        image = os.path.join(self.tmp, "Imagefile")
        with open(image, "w", encoding="utf-8") as handle:
            handle.write("FROM scratch\n")
        result = run(
            [tool("trace-start"), "n", "--root", runs, "--toolchain-file", f"image={image}"],
            self.env,
            cwd=self.repo,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        run_dir = re.search(r"TELEMETRY_DIR='([^']*)'", result.stdout.decode()).group(1)
        self.assertEqual(
            self.header(run_dir)["toolchain"],
            {"image": "sha256:" + hashlib.sha256(b"FROM scratch\n").hexdigest()},
        )


# --- record primitives ---------------------------------------------------------------


class TestRecordPrimitives(Fixture):
    def test_write_once_leaves_nothing_behind_a_failed_write(self):
        target = os.path.join(self.tmp, "record.json")
        with mock.patch("os.write", side_effect=OSError(28, "No space left on device")):
            with self.assertRaises(OSError):
                trace_run.write_once(target, b"{}\n")
        self.assertEqual(os.listdir(self.tmp), [])

    def test_write_once_never_overwrites(self):
        target = os.path.join(self.tmp, "record.json")
        trace_run.write_once(target, b'{"first":1}\n')
        with self.assertRaises(FileExistsError):
            trace_run.write_once(target, b'{"second":2}\n')
        with open(target, "rb") as handle:
            self.assertEqual(handle.read(), b'{"first":1}\n')
        self.assertEqual(os.listdir(self.tmp), ["record.json"])

    def test_encoder_round_trips_through_the_standard_parser(self):
        corpus = [
            "",
            "plain",
            'quote"d',
            "back\\slash",
            "tab\tnew\nline\r",
            "\x00\x01\x1f\x7f",
            "é中\U0001f600",
            "  ",
            os.fsdecode(b"\xff\xfe"),
            "\ud800",
        ]
        record = {"strings": corpus, "n": [0, -1, 2**63], "t": True, "f": False, "z": None}
        self.assertEqual(json.loads(trace_run.encode(record)), record)

    def test_hot_path_imports_stay_minimal(self):
        probe = (
            "import sys; sys.path.insert(0, sys.argv[1]); import trace_run; "
            "print(sorted(m for m in ('enum', 'json', 're', 'signal', 'subprocess') if m in sys.modules))"
        )
        result = run([sys.executable, "-I", "-S", "-c", probe, LIB], self.env)
        self.assertEqual(result.stdout, b"[]\n", result.stderr)


# --- the documents match the code ------------------------------------------------------


def _doc_tables(path, marker):
    """{name: [first-column backticked tokens]} for every `<!-- marker: name -->` table."""
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().splitlines()
    tables, current = {}, None
    for line in lines:
        found = re.fullmatch(rf"<!-- {marker}: ([A-Za-z_.-]+) -->", line.strip())
        if found:
            current = tables.setdefault(found.group(1), [])
            continue
        if current is None:
            continue
        if not line.startswith("|"):
            if current:
                current = None
            continue
        cell = re.match(r"\|\s*`([^`]+)`", line)
        if cell:
            current.append(cell.group(1))
    return tables


class TestDocuments(unittest.TestCase):
    def test_schema_key_tables_match_the_schema(self):
        tables = _doc_tables(os.path.join(TOOL, "SCHEMA.md"), "keys")
        expected = {
            name: list(getattr(S, name))
            for name in (
                "RUN_KEYS",
                "VCS_KEYS",
                "SUBMODULE_KEYS",
                "CHANGESET_KEYS",
                "HOST_KEYS",
                "RUN_END_KEYS",
                "SPAN_START_KEYS",
                "SPAN_END_KEYS",
                "SPAN_ATTRIBUTE_KEYS",
                "RUSAGE_KEYS",
                "ANNOTATION_KEYS",
            )
        }
        expected.update({f"EXIT_KEYS.{k}": list(v) for k, v in S.EXIT_KEYS.items()})
        self.assertEqual(tables, expected)

    def test_enums_findings_and_refusals_are_documented(self):
        schema = _doc_tables(os.path.join(TOOL, "SCHEMA.md"), "values")
        self.assertEqual(
            schema,
            {
                "KINDS": list(S.KINDS),
                "VERDICTS": list(S.VERDICTS),
                "EXIT_STATUSES": list(S.EXIT_STATUSES),
                "CHANGESET_KINDS": list(S.CHANGESET_KINDS),
                "SUBMODULE_STATES": list(S.SUBMODULE_STATES),
                "FINDINGS": list(trace_store.FINDINGS),
            },
        )
        readme = _doc_tables(os.path.join(TOOL, "README.md"), "values")
        self.assertEqual(readme, {"REFUSAL_CODES": list(trace_run.REFUSAL_CODES)})


# --- the runner ------------------------------------------------------------------------


def _ids(pairs):
    return sorted(test.id() for test, _ in pairs)


def main(argv):
    if sys.version_info < trace_run.MIN_PYTHON:
        print("gate-trace tests: CANNOT RUN: Python 3.12 or newer is required", file=sys.stderr)
        return 2
    missing = [name for name in REQUIRED_TOOLS if shutil.which(name) is None]
    if missing:
        print(
            f"gate-trace tests: CANNOT RUN: not on PATH: {', '.join(missing)} "
            "(run inside the pinned image: "
            "tools/uber.sh 'bash tools/gate-trace/test/run_tests.sh')",
            file=sys.stderr,
        )
        return 2
    as_json = "--json" in argv
    names = [arg for arg in argv if arg != "--json"]
    loader = unittest.TestLoader()
    module = sys.modules[__name__]
    # The loader turns a name it cannot resolve into a test that ERRORS when run,
    # which would read as a red verdict; a selection that names nothing is a
    # refusal to run, so it is resolved here first.
    for name in names:
        target = module
        for part in name.split("."):
            target = getattr(target, part, None)
        if target is None:
            print(f"gate-trace tests: CANNOT RUN: no test named {name}", file=sys.stderr)
            return 2
    suite = (
        loader.loadTestsFromNames(names, module) if names else loader.loadTestsFromModule(module)
    )
    selected = suite.countTestCases()
    if selected == 0:
        print("gate-trace tests: CANNOT RUN: the selection holds no test", file=sys.stderr)
        return 2
    if as_json:
        with open(os.devnull, "w", encoding="utf-8") as sink:
            result = unittest.TextTestRunner(stream=sink, verbosity=1).run(suite)
    else:
        result = unittest.TextTestRunner(stream=sys.stderr, verbosity=2).run(suite)
    summary = {
        "selected": selected,
        "run": result.testsRun,
        "failures": _ids(result.failures),
        "errors": _ids(result.errors),
        "skipped": _ids(result.skipped),
        "unexpected": sorted(t.id() for t in result.unexpectedSuccesses)
        + _ids(result.expectedFailures),
        "failure_text": "\n".join(text for _test, text in result.failures),
    }
    if as_json:
        print(json.dumps(summary, sort_keys=True))
    else:
        print(
            f"gate-trace tests: {summary['run']} of {selected} run, {len(summary['failures'])} "
            f"failed, {len(summary['errors'])} errored, {len(summary['skipped'])} skipped",
            file=sys.stderr,
        )
    clean = (
        result.testsRun == selected
        and not summary["failures"]
        and not summary["errors"]
        and not summary["skipped"]
        and not summary["unexpected"]
    )
    return 0 if clean else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
