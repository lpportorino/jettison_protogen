"""`trace-run` and `trace-annotate` in their ON mode: the HOT half of gate-trace.

Reached only through `bin/trace-run` and `bin/trace-annotate`, POSIX-sh shims
that decide the OFF path before any interpreter starts and then exec
`python3 -I -S` with a fixed launcher. The launcher protocol is internal:
`trace-run`'s shim passes two facts about the caller that CPython destroys
during its own start-up, so they must travel from BEFORE the exec:

- the ignored-signal mask, read from `/proc/$$/status`, because CPython sets
  SIGPIPE and SIGXFSZ to ignored;
- whether LC_CTYPE was set and to what, because CPython's C-locale coercion
  (PEP 538, which `-I` does not disable) writes LC_CTYPE=C.UTF-8 into this
  process's environment when the caller's locale is C or POSIX, and that
  environment is what the child is handed;

and one fact the environment does not carry: the shell's search path, which
with PATH unset is the shell's own built-in default rather than anything
exported.

THE WRAPPER'S CONTRACT IS TRANSPARENCY. It changes nothing about the command it
measures that it does not have to:

- file descriptors pass through untouched: fork and execve with no
  descriptor juggling, and every descriptor this module opens is
  close-on-exec, so a make jobserver survives;
- the child gets the CALLER's signal dispositions: CPython's own ignores of
  SIGPIPE and SIGXFSZ are undone, and a signal the caller ignored stays ignored;
- the child gets the CALLER's environment plus the advanced TRACEPARENT and
  nothing else: CPython's coerced LC_CTYPE is put back as the caller had it;
- the signals this module handles are blocked across the spawn, and the child
  starts with the caller's signal mask;
- SIGTERM and SIGHUP are forwarded; SIGINT and SIGQUIT are not, because a
  terminal delivers them to the whole process group the child already shares.
  The wrapper ignores those two while it waits and dies of them only if the
  child did;
- the child stays in the caller's process group;
- a child's signal death is re-raised on the wrapper, so the caller's wait
  status names the same signal; an exit status is passed through exactly;
- the child's output is never read, parsed or buffered.

A caller that IGNORES SIGCHLD would have the kernel reap the child before its
status could be read, so the wrapper sets SIGCHLD to default for ITSELF, and
puts the ignore back in the child before the exec.

Imports are the minimum, because each is paid once per span: `os`, `stat`,
`sys`, `time`, `_signal` (the C module; the public `signal` module imports
`enum`, which costs more than every other import here together) and the
schema, plus the built-in `errno` where an error is classified and `resource`
on a signal death. `json` is not imported either; records are written by the
small encoder below, whose output the validator reads back with the standard
parser.
"""

import _signal
import os
import stat
import sys
import time

import trace_schema as S

# The interpreter floor. The shims run whatever python3 is first on PATH, so it
# is checked at run time and refused by name rather than failing obscurely.
MIN_PYTHON = (3, 12)

_SIGNAL_NAMES = {_signal.SIGHUP: "SIGHUP", _signal.SIGTERM: "SIGTERM"}


# The closed set of reasons an ON-mode command refuses with exit 2. README.md
# documents each, and the test suite holds the two in agreement.
REFUSAL_CODES = (
    "usage",
    "launcher",
    "python-too-old",
    "telemetry-dir-empty",
    "telemetry-dir-relative",
    "telemetry-dir-missing",
    "telemetry-dir-not-a-directory",
    "telemetry-dir-not-a-run",
    "telemetry-dir-unwritable",
    "telemetry-dir-unusable",
    "traceparent-missing",
    "traceparent-malformed",
)


class Refusal(Exception):
    """A precondition failed before the command ran: exit 2, command not run.

    `code` is one of REFUSAL_CODES, so a caller and a canary can tell WHICH
    precondition failed without parsing prose.
    """

    def __init__(self, code, message):
        if code not in REFUSAL_CODES:
            raise ValueError(f"unregistered refusal code {code!r}")
        super().__init__(message)
        self.code = code


def _say(prog, message):
    """Write one diagnostic line to stderr; a closed stderr must not crash us."""
    try:
        os.write(2, f"{prog}: {message}\n".encode("utf-8", "surrogateescape"))
    except OSError:
        pass


def _refuse(prog, refusal, note=""):
    """Report a refusal by its code and exit 2. Never returns."""
    _say(prog, f"refused [{refusal.code}]: {refusal}{note}")
    os._exit(2)


_NOT_RUN = "; the command was NOT run"


# --- the record encoder ------------------------------------------------------

_ESCAPES = {
    '"': '\\"',
    "\\": "\\\\",
    "\n": "\\n",
    "\r": "\\r",
    "\t": "\\t",
    "\b": "\\b",
    "\f": "\\f",
}


def _encode_str(text):
    """A JSON string literal for `text`.

    Lone surrogates (how Python represents argument bytes that are not UTF-8)
    are written as `\\udcXX` escapes, so the file is always valid UTF-8 and the
    original bytes survive a round trip through a Python reader.
    """
    if text.isprintable() and '"' not in text and "\\" not in text:
        return '"' + text + '"'
    out = ['"']
    for ch in text:
        code = ord(ch)
        if ch in _ESCAPES:
            out.append(_ESCAPES[ch])
        elif code < 0x20 or code == 0x7F or 0xD800 <= code <= 0xDFFF:
            out.append(f"\\u{code:04x}")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def encode(value):
    """Compact JSON for the value shapes a record holds."""
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return _encode_str(value)
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(encode(item) for item in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(_encode_str(k) + ":" + encode(v) for k, v in value.items()) + "}"
    raise TypeError(f"unencodable record value of type {type(value).__name__}")


def record_bytes(record):
    """One record as written: compact JSON and exactly one newline.

    Refuses to produce a record its own reader would reject as oversize.
    """
    data = (encode(record) + "\n").encode("utf-8")
    if len(data) > S.MAX_RECORD_BYTES:
        raise ValueError(f"record of {len(data)} bytes exceeds {S.MAX_RECORD_BYTES}")
    return data


def write_once(path, data):
    """Create `path` holding `data`, atomically and exactly once.

    The bytes go to a dot-prefixed temporary first and are then hard-linked
    into place, so a reader sees the whole record or no record, and an existing
    record is never overwritten (FileExistsError instead). A writer killed
    between the two steps leaves only the temporary, which the validator names.
    This is atomic VISIBILITY, not crash durability: nothing is fsync'd.
    """
    head, tail = os.path.split(path)
    tmp = os.path.join(head, f"{S.TEMP_PREFIX}{tail}.{new_id(S.NONCE_HEX)}{S.TEMP_SUFFIX}")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o644)
    try:
        try:
            view = memoryview(data)
            while view:
                view = view[os.write(fd, view) :]
        finally:
            os.close(fd)
        os.link(tmp, path)
    finally:
        os.unlink(tmp)


def new_id(width):
    """A random lowercase hex id of `width` digits that is not all zero."""
    while True:
        value = os.urandom(width // 2).hex()
        if S.is_hex_id(value, width):
            return value


# --- shared argument and environment handling -------------------------------


def _check_python():
    if sys.version_info < MIN_PYTHON:
        have = ".".join(str(part) for part in sys.version_info[:3])
        need = ".".join(str(part) for part in MIN_PYTHON)
        raise Refusal("python-too-old", f"needs Python {need} or newer; this is {have}")


def parse_ctype(token):
    """The caller's LC_CTYPE as the shim saw it: None when unset, else its value.

    The shim sends `-` for unset and `=` followed by the value for set, so an
    empty value and an unset variable stay distinguishable.
    """
    if token == "-":
        return None
    if token is not None and token.startswith("="):
        return token[1:]
    raise Refusal(
        "launcher",
        "the caller's LC_CTYPE was not passed in; run trace-run through bin/, which "
        "reads it before the interpreter's locale coercion can overwrite it",
    )


def parse_ignored_mask(token):
    """The caller's ignored-signal mask, as the shim read it from /proc."""
    if not token or any(c not in "0123456789abcdefABCDEF" for c in token):
        raise Refusal(
            "launcher",
            "the caller's signal dispositions were not passed in; run trace-run "
            "through bin/, which reads them from /proc before the interpreter starts",
        )
    return int(token, 16)


_RUN_USAGE = "usage: trace-run <name> [--kind K] -- <command> [<arg>...]"


def parse_run_args(args):
    """`<name> [--kind K] -- cmd...` as (name, kind, command).

    The same grammar `bin/trace-run` applies before it knows whether telemetry
    is on; the test suite holds the two in agreement.
    """
    if not args:
        raise Refusal("usage", _RUN_USAGE)
    name, rest, kind = args[0], args[1:], None
    problem = S.name_problem(name)
    if problem is not None:
        raise Refusal("usage", f"bad span name: {problem}")
    while rest:
        head = rest[0]
        if head == "--":
            if len(rest) < 2:
                raise Refusal("usage", _RUN_USAGE)
            if not rest[1] or rest[1].startswith("-"):
                raise Refusal("usage", "the command may not be empty or begin with -")
            return name, kind or S.DEFAULT_KIND, rest[1:]
        if kind is not None:
            raise Refusal("usage", _RUN_USAGE)
        if head == "--kind" and len(rest) >= 2:
            kind, rest = rest[1], rest[2:]
        elif head.startswith("--kind="):
            kind, rest = head[len("--kind=") :], rest[1:]
        else:
            raise Refusal("usage", _RUN_USAGE)
        if kind not in S.KINDS:
            raise Refusal("usage", f"unknown kind (one of {', '.join(S.KINDS)})")
    raise Refusal("usage", _RUN_USAGE)


def telemetry_context():
    """(spans dir, trace id, current span id, flags) for an ON-mode command.

    Every way the environment can fail to name a usable run is a separate,
    named refusal; writability is discovered by the first write and classified
    by `classify_write_error`.
    """
    tdir = os.environ.get("TELEMETRY_DIR")
    if tdir is None:
        raise Refusal("launcher", "TELEMETRY_DIR is unset, so the shim should have run the command")
    if tdir == "":
        raise Refusal("telemetry-dir-empty", "TELEMETRY_DIR is set but empty")
    if not os.path.isabs(tdir):
        # A relative path resolves against each process's own directory, so a
        # child that changes directory (make -C) would write somewhere else.
        raise Refusal("telemetry-dir-relative", f"TELEMETRY_DIR={tdir} is not an absolute path")
    try:
        mode = os.stat(tdir).st_mode
    except (FileNotFoundError, NotADirectoryError):
        raise Refusal("telemetry-dir-missing", f"TELEMETRY_DIR={tdir} does not exist") from None
    except OSError as err:
        raise Refusal("telemetry-dir-unusable", f"TELEMETRY_DIR={tdir}: {err.strerror}") from None
    if not stat.S_ISDIR(mode):
        raise Refusal("telemetry-dir-not-a-directory", f"TELEMETRY_DIR={tdir} is not a directory")
    spans_dir = os.path.join(tdir, S.SPANS_DIR)
    if not os.path.isdir(spans_dir) or not os.path.isfile(os.path.join(tdir, S.RUN_FILE)):
        raise Refusal(
            "telemetry-dir-not-a-run",
            f"TELEMETRY_DIR={tdir} has no {S.RUN_FILE} and {S.SPANS_DIR}/; "
            "it must be a run directory made by trace-start",
        )
    raw = os.environ.get("TRACEPARENT")
    if raw is None:
        raise Refusal(
            "traceparent-missing",
            "TELEMETRY_DIR is set but TRACEPARENT is not; a span must belong to a "
            "run started by trace-start",
        )
    parsed = S.parse_traceparent(raw)
    if parsed is None:
        raise Refusal(
            "traceparent-malformed", f"TRACEPARENT is not a W3C version-00 value: {raw!r}"
        )
    trace_id, parent_id, flags = parsed
    return spans_dir, trace_id, parent_id, flags


def classify_write_error(err, spans_dir):
    """The named refusal for a failed record write into `spans_dir`."""
    import errno

    if err.errno in (errno.EACCES, errno.EPERM, errno.EROFS):
        return Refusal("telemetry-dir-unwritable", f"cannot write into {spans_dir}: {err.strerror}")
    if err.errno in (errno.ENOENT, errno.ENOTDIR):
        return Refusal("telemetry-dir-not-a-run", f"{spans_dir} vanished: {err.strerror}")
    return Refusal("telemetry-dir-unusable", f"cannot write into {spans_dir}: {err.strerror}")


# --- the wrapper ------------------------------------------------------------


def _execvp(command, env, search_path):
    """dash's command search and exec, done by hand. Returns only by raising.

    The directories are the SHELL's search path, passed by the shim, which is
    the environment's PATH when one is exported and the shell's built-in
    default when not. The algorithm is dash's shellexec: a name holding a slash
    is tried once and its error is the result; otherwise every entry is tried
    in turn as `entry/name`, concatenated as dash does (so `d/` gives `d//name`;
    an empty entry names the bare file; nothing is tilde-expanded; an entry
    holding `%` is dash's `dir%option` form, which exec skips),
    the search continues past EVERY error, and the error kept is the last one
    that is not ENOENT or ENOTDIR, else ENOENT. A file with no `#!` line
    (ENOEXEC) is run by /bin/sh, as dash and execvp(3) do. `exit_status_for`
    maps the error to dash's status. bash searches differently in ways
    README.md lists.
    """
    import errno

    name = command[0]
    if "/" in name:
        candidates = [name]
    else:
        candidates = [
            entry + "/" + name if entry else name
            for entry in search_path.split(os.pathsep)
            if "%" not in entry
        ]
    kept = None
    for candidate in candidates:
        try:
            try:
                os.execve(candidate, command, env)
            except OSError as err:
                if err.errno != errno.ENOEXEC:
                    raise
                os.execve("/bin/sh", ["/bin/sh", candidate, *command[1:]], env)
        except OSError as err:
            if "/" in name or err.errno not in (errno.ENOENT, errno.ENOTDIR):
                kept = err
    raise kept or OSError(errno.ENOENT, os.strerror(errno.ENOENT))


def exit_status_for(error_number):
    """dash's status for a command it could not start: 127 for an error that
    means "not found" (ENOENT, ENOTDIR, ELOOP, ENAMETOOLONG), 126 otherwise."""
    import errno

    not_found = (errno.ENOENT, errno.ENOTDIR, errno.ELOOP, errno.ENAMETOOLONG)
    return 127 if error_number in not_found else 126


def _spawn(command, env, search_path, sigmask, sigdef, sigign):
    """Start the child exactly as a shell's exec would, and return its pid.

    fork and execve rather than posix_spawn, because glibc's posix_spawn sets
    its own internal signals (32 and 33) to SIG_IGN in every child it creates,
    and an ignored disposition survives exec: the child would start with two
    signals ignored that the caller never ignored. A fork child inherits this
    process's dispositions, every caught one resets to default at exec, and
    the signals in `sigdef` are put back to default, and those in `sigign` back
    to ignored, before the mask is restored, so a signal held pending across the spawn meets the disposition
    the command would have had. An exec failure comes back over a close-on-exec
    pipe as the errno, and is raised here as the OSError a spawn would raise.
    """
    import errno

    failure_r, failure_w = os.pipe()
    pid = os.fork()
    if pid == 0:
        code = errno.EINVAL
        try:
            os.close(failure_r)
            for sig in sigdef:
                _signal.signal(sig, _signal.SIG_DFL)
            for sig in sigign:
                _signal.signal(sig, _signal.SIG_IGN)
            _signal.pthread_sigmask(_signal.SIG_SETMASK, sigmask)
            _execvp(command, env, search_path)
        except OSError as err:
            code = err.errno or errno.EINVAL
        except BaseException:
            code = errno.EINVAL
        try:
            os.write(failure_w, code.to_bytes(4, "little"))
        finally:
            os._exit(127)
    os.close(failure_w)
    report = b""
    while chunk := os.read(failure_r, 4):
        report += chunk
    os.close(failure_r)
    if report:
        os.waitpid(pid, 0)
        code = int.from_bytes(report[:4], "little")
        raise OSError(code, os.strerror(code))
    return pid


def _rusage_record(usage):
    return {
        "user_us": round(usage.ru_utime * 1_000_000),
        "sys_us": round(usage.ru_stime * 1_000_000),
        "maxrss_kib": usage.ru_maxrss,
        "minflt": usage.ru_minflt,
        "majflt": usage.ru_majflt,
        "inblock": usage.ru_inblock,
        "oublock": usage.ru_oublock,
        "nvcsw": usage.ru_nvcsw,
        "nivcsw": usage.ru_nivcsw,
    }


def _exit_record(status):
    if os.WIFSIGNALED(status):
        return {
            "status": "signaled",
            "signal": os.WTERMSIG(status),
            "core_dumped": os.WCOREDUMP(status),
        }
    return {"status": "exited", "code": os.WEXITSTATUS(status)}


def _die_like(sig):
    """End this process by `sig`, so the caller's wait status names the same signal.

    Core dumps are suppressed first: the child may have dumped one, and the
    wrapper writing a second core file is a side effect the bare command never
    had. The cost is that the caller's status lacks the core-dumped bit; the
    span records it.
    """
    try:
        import resource

        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    except (ImportError, OSError, ValueError):
        pass
    if sig not in (_signal.SIGKILL, _signal.SIGSTOP):
        _signal.signal(sig, _signal.SIG_DFL)
    _signal.pthread_sigmask(_signal.SIG_UNBLOCK, {sig})
    os.kill(os.getpid(), sig)
    os._exit(128 + sig)


def _executable_name(command):
    return os.path.basename(command[0])[: S.MAX_EXECUTABLE_NAME_CHARS]


def _write_span(prog, path, record):
    """Write the final span record. A failure is loud but never changes the exit
    status: the command has already run, and the start marker left behind is
    exactly the evidence `trace-finish` names as an orphan."""
    try:
        write_once(path, record_bytes(record))
    except (OSError, ValueError) as err:
        _say(prog, f"span record NOT written ({err}); trace-finish will report an orphan")


def run_main(argv):
    """Entry point for `bin/trace-run` in its ON mode. Never returns."""
    prog = "trace-run"
    try:
        _check_python()
        ignored = parse_ignored_mask(argv[0] if argv else None)
        caller_ctype = parse_ctype(argv[1] if len(argv) > 1 else None)
        if len(argv) < 3:
            raise Refusal(
                "launcher", "the shell's search path was not passed in; run trace-run through bin/"
            )
        search_path = argv[2]
        name, kind, command = parse_run_args(argv[3:])
        spans_dir, trace_id, parent_id, flags = telemetry_context()
    except Refusal as refusal:
        _refuse(prog, refusal, _NOT_RUN)

    span_id = new_id(S.SPAN_ID_HEX)
    base = os.path.join(spans_dir, span_id)
    start = {
        "schema": S.SCHEMA_VERSION,
        "record": S.RECORD_SPAN_START,
        "trace_id": trace_id,
        "span_id": span_id,
        "parent_span_id": parent_id,
        "name": name,
        "kind": kind,
        "start_mono_ns": time.monotonic_ns(),
    }
    try:
        write_once(base + S.START_SUFFIX, record_bytes(start))
    except OSError as err:
        _refuse(prog, classify_write_error(err, spans_dir), _NOT_RUN)

    def caller_ignores(sig):
        # Only SIGPIPE and SIGXFSZ need the shim's /proc reading: CPython sets
        # both to ignored at start-up. Every other disposition is read LIVE,
        # because it is exactly what exec handed this process, and the shim's
        # reading is the SHELL's runtime state, which is not: a non-interactive
        # bash runs with SIGCHLD caught and SIGQUIT ignored, and restores the
        # dispositions it inherited just before it execs.
        if sig in (_signal.SIGPIPE, _signal.SIGXFSZ):
            return (ignored >> (sig - 1)) & 1 == 1
        return _signal.getsignal(sig) == _signal.SIG_IGN

    forwarded = [s for s in (_signal.SIGHUP, _signal.SIGTERM) if not caller_ignores(s)]
    terminal = [s for s in (_signal.SIGINT, _signal.SIGQUIT) if not caller_ignores(s)]
    # Put back to default in the child unless the CALLER ignored them: CPython
    # ignores SIGPIPE and SIGXFSZ itself, the wrapper ignores the terminal
    # signals while it waits, and it handles the forwarded ones.
    reset = [s for s in (_signal.SIGPIPE, _signal.SIGXFSZ) if not caller_ignores(s)]
    reset += terminal + forwarded
    # The wrapper needs SIGCHLD at default to read its child's status; the child
    # gets the caller's ignore back before the exec.
    sigign = []
    if caller_ignores(_signal.SIGCHLD):
        _signal.signal(_signal.SIGCHLD, _signal.SIG_DFL)
        sigign.append(_signal.SIGCHLD)

    counts = {}
    child = [0]

    def forward(sig, _frame):
        # The pid guard is defence in depth: these signals are blocked until the
        # pid is known, and kill(0, sig) would signal the whole process group.
        if child[0] > 0:
            counts[sig] = counts.get(sig, 0) + 1
            try:
                os.kill(child[0], sig)
            except ProcessLookupError:
                pass

    caller_mask = _signal.pthread_sigmask(_signal.SIG_BLOCK, forwarded + terminal)
    for sig in terminal:
        _signal.signal(sig, _signal.SIG_IGN)
    for sig in forwarded:
        _signal.signal(sig, forward)

    env = dict(os.environb)
    if caller_ctype is None:
        env.pop(b"LC_CTYPE", None)
    else:
        env[b"LC_CTYPE"] = os.fsencode(caller_ctype)
    env[b"TRACEPARENT"] = S.format_traceparent(trace_id, span_id, flags).encode()
    try:
        child[0] = _spawn(command, env, search_path, caller_mask, reset, sigign)
    except OSError as err:
        import errno

        code = exit_status_for(err.errno)
        end = dict(start)
        end.update(
            record=S.RECORD_SPAN,
            end_mono_ns=time.monotonic_ns(),
            verdict="error",
            exit={
                "status": "spawn-failed",
                "errno": errno.errorcode.get(err.errno, f"E{err.errno}"),
                "code": code,
            },
            rusage=None,
            signals_forwarded={name: 0 for name in _SIGNAL_NAMES.values()},
            attributes={"process.pid": None, "process.executable.name": _executable_name(command)},
        )
        _write_span(prog, base + S.SPAN_SUFFIX, end)
        _say(prog, f"{command[0]}: {err.strerror}")
        os._exit(code)
    _signal.pthread_sigmask(_signal.SIG_SETMASK, caller_mask)

    # Reap in two steps. WNOWAIT leaves the exited child a zombie, so its pid
    # cannot be recycled while a forward is still possible; the forwarded
    # signals are then blocked, and only then is the zombie reaped. A single
    # wait4 would leave a window in which a late SIGTERM is forwarded to
    # whatever process inherited the pid.
    os.waitid(os.P_PID, child[0], os.WEXITED | os.WNOWAIT)
    end_mono = time.monotonic_ns()
    _signal.pthread_sigmask(_signal.SIG_BLOCK, forwarded)
    _pid, status, usage = os.wait4(child[0], 0)

    exit_record = _exit_record(status)
    end = dict(start)
    end.update(
        record=S.RECORD_SPAN,
        end_mono_ns=end_mono,
        verdict=S.verdict_for_exit(exit_record),
        exit=exit_record,
        rusage=_rusage_record(usage),
        signals_forwarded={name: counts.get(sig, 0) for sig, name in _SIGNAL_NAMES.items()},
        attributes={"process.pid": child[0], "process.executable.name": _executable_name(command)},
    )
    _write_span(prog, base + S.SPAN_SUFFIX, end)
    if exit_record["status"] == "signaled":
        _die_like(exit_record["signal"])
    os._exit(exit_record["code"])


# --- annotation -------------------------------------------------------------

_ANNOTATE_USAGE = "usage: trace-annotate <key>=<value> [<key>=<value>...]"


def parse_pairs(argv):
    """`key=value...` as (attributes, truncated keys).

    The key checks are the ones `bin/trace-annotate` applies while telemetry is
    OFF, so no invocation that succeeds OFF is refused ON. A VALUE is data, not
    wiring: it is never a reason to refuse, and one longer than the bound is cut
    to it and its key listed in the record's `truncated`.
    """
    if not argv or len(argv) > S.MAX_ANNOTATION_PAIRS:
        raise Refusal("usage", f"{_ANNOTATE_USAGE} (1 to {S.MAX_ANNOTATION_PAIRS} pairs)")
    attributes, truncated = {}, []
    for pair in argv:
        key, sep, value = pair.partition("=")
        if not sep:
            raise Refusal("usage", "an argument is not a key=value pair")
        problem = S.attribute_key_problem(key)
        if problem is not None:
            raise Refusal("usage", f"bad attribute key: {problem}")
        if key in attributes:
            raise Refusal("usage", f"attribute {key} given twice")
        if len(value) > S.MAX_ATTRIBUTE_VALUE_CHARS:
            value = value[: S.MAX_ATTRIBUTE_VALUE_CHARS]
            truncated.append(key)
        attributes[key] = value
    return attributes, truncated


def annotate_main(argv):
    """Entry point for `bin/trace-annotate` in its ON mode. Never returns."""
    prog = "trace-annotate"
    try:
        _check_python()
        attributes, truncated = parse_pairs(argv)
        spans_dir, trace_id, span_id, _flags = telemetry_context()
    except Refusal as refusal:
        _refuse(prog, refusal)
    record = {
        "schema": S.SCHEMA_VERSION,
        "record": S.RECORD_ANNOTATION,
        "trace_id": trace_id,
        "span_id": span_id,
        "time_mono_ns": time.monotonic_ns(),
        "attributes": attributes,
        "truncated": truncated,
    }
    path = os.path.join(
        spans_dir, f"{span_id}{S.ANNOTATION_INFIX}{new_id(S.NONCE_HEX)}{S.JSON_SUFFIX}"
    )
    try:
        write_once(path, record_bytes(record))
    except OSError as err:
        _refuse(prog, classify_write_error(err, spans_dir))
    os._exit(0)
