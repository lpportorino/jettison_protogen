# gate-trace

Step one of a gate-telemetry tool: a closed, versioned, durable record of one
run of a gate or test chain. Four commands write and check it; nothing here
samples, converts or keeps history.

| command | does |
|---|---|
| `trace-start <entrypoint> --root DIR --toolchain NAME=VALUE` | creates a run directory and its header, and prints the `TELEMETRY_DIR` and `TRACEPARENT` assignments that turn telemetry on (use under `eval`) |
| `trace-run <name> [--kind K] -- cmd...` | runs `cmd` as one span, transparently |
| `trace-annotate key=value...` | attaches attributes to the current span |
| `trace-finish [DIR]` | stamps the run's end and validates the whole record |

```sh
eval "$(tools/gate-trace/bin/trace-start pre-push --root .protogen/telemetry \
          --toolchain-file image=Dockerfile.base)"
tools/gate-trace/bin/trace-run lint --kind gate -- make -f lint.mk lint
tools/gate-trace/bin/trace-finish
```

The record's shape is `SCHEMA.md`. The code is Python 3.12 standard library
behind POSIX-sh shims, and is Linux-only: the wrapper reads `/proc`.

## The OFF path costs no interpreter

With `TELEMETRY_DIR` UNSET, `trace-run` checks its arguments and `exec`s the
command from the shell, and `trace-annotate` checks its arguments and exits 0.
No Python starts and no file is written. Arguments are checked by the same
grammar in both modes, so turning telemetry on never changes which invocations
succeed. SET means on, even when empty: a set but empty, missing, unwritable or
non-run directory is refused, never silently skipped.

The ON path starts `python3 -I -S` (no environment, no user site, no `site`),
by a path and never by a bare name: started by a bare name, CPython searches
`PATH` again during its own start-up and dies on an entry too long to join.
dash names a command found through an empty `PATH` entry (the current
directory) by its bare name, so the shims make such an answer `./python3`, and
a shim that dash ran through an empty entry, and which therefore sees its own
bare name, locates itself the same way.
It imports byte-compiled modules, which Python caches beside them in the
git-ignored `__pycache__`. Importing the wrapper pulls in none of `enum`,
`json`, `re`, `signal` or `subprocess`; it uses the C `_signal` module because
the public `signal` module imports `enum`, which costs more than everything
else it loads together. `TestRecordPrimitives.test_hot_path_imports_stay_minimal`
holds that.

## Transparency, and the test that holds each guarantee

Unless its row says otherwise, each test below drives the shipped `bin/` shims
under both dash and bash, and most compare the ON path with the OFF path: the
OFF path is a plain `exec`, so it defines what the caller would have seen
without the wrapper.

| guarantee | held by (`test/test_gate_trace.py`) |
|---|---|
| exit status passed through exactly | `TestExitFidelity.test_exit_status_passthrough` |
| a spawn failure returns 127 or 126, as dash does | `TestExitFidelity.test_spawn_failure_matches_the_shell` |
| a file with no `#!` line still runs, under `/bin/sh` as dash's `exec` runs it | `TestExitFidelity.test_script_without_shebang_runs_like_exec` |
| SIGTERM and SIGHUP forwarded to the child | `TestSignalFidelity.test_sigterm_and_sighup_are_forwarded` |
| a child's signal death re-raised on the wrapper | `TestSignalFidelity.test_child_killed_by_sigkill_is_reraised` |
| SIGINT and SIGQUIT not forwarded; the wrapper survives them | `TestSignalFidelity.test_sigint_and_sigquit_are_not_forwarded` |
| a group SIGINT ends the child and the span is still written | `TestSignalFidelity.test_group_sigint_ends_child_and_the_wrapper_records_it` |
| signals blocked across the spawn, then forwarded (drives the library directly, to raise SIGTERM inside the spawn) | `TestSignalFidelity.test_a_signal_during_the_spawn_is_held_and_forwarded` |
| SIGPIPE default in the child: a pipeline's silent SIGPIPE exit survives | `TestSignalFidelity.test_pipeline_relying_on_silent_sigpipe` |
| SIGXFSZ default in the child | `TestSignalFidelity.test_sigxfsz_takes_its_default_action_in_the_child` |
| the child's ignored signals and signal mask equal the OFF path's, including a caller that ignores SIGPIPE, SIGHUP, SIGINT and SIGQUIT, or SIGCHLD | `TestSignalFidelity.test_child_dispositions_and_mask_equal_the_off_path` |
| the child's environment equals the OFF path's plus `TELEMETRY_DIR` and the advanced `TRACEPARENT`, for unset, C, POSIX, empty and UTF-8 `LC_CTYPE`, `PYTHON*` variables, an exported `SHELLOPTS` carrying pipefail, errexit and nounset, allexport, or xtrace and verbose, a name that is not an identifier, and every bare name the shims have assigned or would assign without their prefix | `TestEnvironment.test_child_environment_equals_the_off_path` |
| the OFF path's environment equals a plain exec's, for the same callers less, per shell, the inputs that shell is known to edit (below) | `TestEnvironment.test_off_path_environment_equals_a_plain_exec` |
| with `PATH` unset, the command is found where the shell would find it (its built-in default) | `TestEnvironment.test_with_path_unset_the_wrapper_searches_where_the_shell_does` |
| a script found through an empty entry, or through an entry ending in `/`, sees the same `$0` ON as OFF under dash, and an entry containing `%` is skipped as dash skips it | `TestSearch.test_entries_are_named_and_skipped_as_dash_does` |
| the search continues past every error and reports dash's status, the last error that is not "no such file" or "not a directory": a self-looping symlink before a valid entry, a self-looping symlink alone (127), an entry longer than `PATH_MAX` before a valid one, such an entry alone (127), an unexecutable file then a self-looping symlink (127), the reverse order (126), an unexecutable file then a regular file used as an entry (126), and a slash path through a regular file (127) | `TestSearch.test_errors_while_searching_are_handled_as_dash_handles_them` |
| every shim starts its interpreter by a path, so an entry too long to join does not crash it, including when dash found `python3` through an empty entry | `TestSearch.test_every_shim_starts_its_interpreter_by_a_path` |
| every shim run by bare name through an empty `PATH` entry from its own directory finds its library, under both shells | `TestSearch.test_a_shim_found_through_an_empty_entry_locates_its_library` |
| the child stays in the caller's process group | `TestSignalFidelity.test_child_stays_in_the_callers_process_group` |
| caller file descriptors intact | `TestSignalFidelity.test_descriptors_pass_through_untouched` |
| a make jobserver survives `make -j` | `TestJobserver.test_jobserver_survives_make_j` |
| nesting through `TRACEPARENT` across real children | `TestNesting.test_nesting_through_real_children` |
| the start marker is written before the child runs | `TestNesting.test_start_marker_exists_before_the_child_runs` |
| the OFF path runs zero Python | `TestOffPath.test_off_path_runs_zero_python` |
| the interpreter starts with `-I -S` (dash) | `TestOffPath.test_interpreter_starts_isolated` |
| the wrapper's modules are bytecode-cached (dash) | `TestOffPath.test_the_wrapper_is_bytecode_cached` |
| an unwritable telemetry directory is refused, command not run | `TestRefusals.test_unwritable_directory_is_refused` |
| every other unusable environment is refused by name | `TestRefusals.test_environment_refusals_are_named` |
| an interpreter older than 3.12 is refused by name (drives the library directly) | `TestRefusals.test_an_old_interpreter_is_refused_by_name` |
| records are written atomically and never overwritten (in-process) | `TestRecordPrimitives.test_write_once_leaves_nothing_behind_a_failed_write`, `TestRecordPrimitives.test_write_once_never_overwrites` |

The output is never read, parsed or buffered: the child inherits the caller's
descriptors directly, which the descriptor and pipeline tests observe.

**What "SIGPIPE default" means here.** CPython ignores SIGPIPE and SIGXFSZ in
itself, so a naive wrapper hands its child both ignored. This one hands the
child the CALLER's disposition, which is default in the ordinary case. It does
not force default: a caller that ignores SIGPIPE gets a child that ignores it,
exactly as a plain `exec` would, and forcing default would make the ON path
differ from the OFF path. The disposition test covers both cases.

**Where the platform works against transparency, and what puts it back.**

- CPython sets SIGPIPE and SIGXFSZ to ignored at start-up, so the shim reads
  the caller's mask from `/proc` before the interpreter starts. It reads only
  those two from it: the mask is the SHELL's runtime state, and a
  non-interactive bash runs with SIGQUIT ignored and SIGCHLD caught, then
  restores both before exec. Every other disposition is read live.
- CPython's C-locale coercion (PEP 538, which `-I` does not disable) writes
  `LC_CTYPE=C.UTF-8` into the interpreter's environment when the caller's
  locale is C or POSIX. The shim passes the caller's `LC_CTYPE` (unset, or its
  value) beside the mask, and the wrapper puts it back before the exec.
- glibc's `posix_spawn` sets its internal signals 32 and 33 to ignored in every
  child, and an ignored disposition survives exec, so the wrapper uses `fork`
  and `execve`. The canary holds this with a mutant that spawns through
  `posix_spawn`: the disposition test fails on exactly those two signals.
- A caller that ignores SIGCHLD would have the kernel reap the child before its
  status could be read, so the wrapper sets SIGCHLD to default for ITSELF and
  puts the ignore back in the child before the exec. The exit status still
  passes through: `TestSignalFidelity.test_caller_ignoring_sigchld_still_gets_the_exit_status`.
- bash adopts options a caller exports in `SHELLOPTS` and re-exports
  `SHELLOPTS` with whatever a script changes. The shims record the caller's
  `$-` on their second line, switch off allexport, xtrace and verbose on their
  third, run with `set -eu`, and put all five of a, e, u, v and x back as the
  caller had them immediately before every exec. So a caller's `set -euo
  pipefail` survives into the command, and a caller's allexport does not
  export the shims' own variables and functions.
- With `PATH` unset a shell searches its own built-in default and exports
  nothing, while the interpreter would fall back to `/bin:/usr/bin`. The shim
  passes the shell's search path to the wrapper, which searches it the way
  dash does.

**What still differs from a plain exec.**

- **The shell that runs a shim edits the environment before the shim's first
  line executes**, so no line of the shim can undo it, with telemetry off as
  well as on. These are measured instances of a class, not a closed list:
  - both shells add `PWD` when it is absent and reset it when it names the
    wrong directory, and rewrite their own shell-special variables when a
    caller exports them: both reset `IFS` and `OPTIND` and re-export them;
    dash rewrites `PPID`; bash drops `PPID`, `PS1`, `PS2`, `BASHPID`, `RANDOM`
    and `BASH_ARGV0`, resets `PS4`, overwrites `BASH_VERSION`, rewrites
    `BASHOPTS`, rewrites `LINENO` to the line of the exec (so it differs
    between the OFF and ON paths), and runs the file `BASH_ENV` names;
  - bash adds `SHLVL` when it is absent, drops `_`, unsets an `OLDPWD` that is
    not a directory, and, run under the name `sh`, adds `posix` to an exported
    `SHELLOPTS`;
  - dash drops every name that is not a shell identifier, such as an exported
    bash function (`BASH_FUNC_f%%`) or `a.b`, so `trace-run x -- bash -c f`
    fails under a dash shim where `bash -c f` alone succeeds;
  - bash rewrites an exported `SHELLOPTS` to its canonical form (`pipefail`
    alone becomes `braceexpand:hashall:interactive-comments:pipefail`); with
    `POSIXLY_CORRECT` exported it enters posix mode, which adds `posix` and
    drops `pipefail`; and whatever the `BASH_ENV` file it runs changes, such as
    an option, reaches the command (as `sh`, bash runs no `BASH_ENV`).

  Only a launcher that is not a shell script could avoid this.
- **The shims reserve the names `gate_trace__*`** for their variables and
  functions. In POSIX sh an exported variable that a script assigns is
  re-exported with the new value, so every name the shims assign carries that
  prefix, and a caller's exported variable of that name is overwritten.
- **A caller's xtrace or verbose shows the shim.** Before its third line can
  switch them off, the shell prints its first lines to stderr, and under
  xtrace it also prints the exec line, which carries the command line, after
  the restore; the command exec'd directly prints nothing.
- **Under noexec or onecmd the command never runs and the shim exits 0.**
  noexec reads the shim and runs none of it; onecmd runs one command and
  exits, and the first line it reads is the shebang. Neither can be detected
  from inside the shim, because no line of it executes.
- **The command search is dash's**: every entry is tried, the search goes on
  past every error, and the status follows the last error that is not "no such
  file" or "not a directory". An entry is joined to the name with one `/`, so a trailing `/` is
  kept, and an entry containing `%` is skipped, because dash reads what
  follows a `%` as an option of the entry (`%func`, `%builtin`). Under a bash
  shim the search differs from bash's own, which expands a `~` at the start of
  an entry, searches an entry containing `%`, names a script found through an
  entry ending in `/` without the doubled slash, keeps the first unexecutable
  file it meets instead of the last error (an unexecutable file before a
  self-looping symlink is 126 under bash, 127 under dash and the wrapper),
  reports a directory named like the command as not found (127, where dash and
  the wrapper report 126), reports a slash path failing with ENOTDIR or ELOOP
  as 126 (dash and the wrapper: 127), and hands a script found through a
  relative or empty entry an absolute `$0`.
- **Only SIGTERM and SIGHUP sent to the wrapper's own pid reach the child.**
  SIGINT and SIGQUIT sent to it are ignored, and any other signal takes its
  default action on the WRAPPER: SIGUSR1, for example, ends the wrapper while
  the child keeps running, and the span is left as an orphan. Signals sent to
  the process group reach the child directly, as without the wrapper.
- A re-raised signal death never writes a core file from the wrapper, so the
  caller's wait status lacks the core-dumped bit; the span records the child's.
- A command that cannot be started returns dash's status (127 or 126), but
  the diagnostic on stderr is the wrapper's wording, not the shell's.
- A file with no `#!` line is run by `/bin/sh`, as dash and execvp(3) do;
  bash running it itself would use bash.

## Refusals

An ON-mode command that cannot run refuses with exit 2 and one of these names,
as `trace-run: refused [<name>]: <reason>; the command was NOT run`.

<!-- values: REFUSAL_CODES -->
| name | when |
|---|---|
| `usage` | the arguments break the grammar (checked in both modes), including an empty command word, which no search can find, and a command word beginning with `-`, which bash's `exec` would read as its own option |
| `launcher` | `python3` is not on PATH, the shim protocol was bypassed, or `/proc` is unreadable |
| `python-too-old` | the interpreter is older than 3.12 |
| `telemetry-dir-empty` | `TELEMETRY_DIR` is set but empty |
| `telemetry-dir-relative` | `TELEMETRY_DIR` is not absolute, so a child that changes directory would resolve it elsewhere |
| `telemetry-dir-missing` | `TELEMETRY_DIR` does not exist |
| `telemetry-dir-not-a-directory` | `TELEMETRY_DIR` is not a directory |
| `telemetry-dir-not-a-run` | it holds no run.json and spans/ made by trace-start |
| `telemetry-dir-unwritable` | a record cannot be created in it |
| `telemetry-dir-unusable` | any other operating-system error reaching it |
| `traceparent-missing` | `TELEMETRY_DIR` is set and `TRACEPARENT` is not |
| `traceparent-malformed` | `TRACEPARENT` is not a W3C version-00 value |

`trace-start` and `trace-finish` share an exit contract: 0 ok, 1 findings
(trace-finish only, each named by a code in `SCHEMA.md`), 2 cannot run (a
usage error, or an unusable environment such as a git that will not start or
a `--repo` that is missing), 3 an internal error.

## Tests and the fail canary

```sh
tools/uber.sh 'bash tools/gate-trace/test/run_tests.sh'
```

`test/run_tests.sh` runs the suite, then `test/mutants.py`, the fail canary. It
needs dash, bash, GNU make, git and coreutils, and refuses with exit 2 naming
whatever is missing. The canary breaks one guarantee at a time in a throw-away
copy of this directory, asserts the mutation landed (the new text present, the
old absent), and requires the guarantee's own test to FAIL, not error, while a
neighbouring test stays green on the same mutant. It also proves the runner
itself fails on a real fault and refuses a selection that holds no test.

## What is deliberately absent

- No sampler, no converter to any wire format, no history or index of runs:
  later steps.
- No interception of commands that were not wrapped: a span exists only where
  a caller put `trace-run`.
- No record of command lines, environment variables or output, and no host
  name unless asked: a record may be quoted publicly.
- No crash durability: records are visible atomically but never synced.
- No support outside Linux.
