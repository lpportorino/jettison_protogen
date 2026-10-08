"""gate-trace's fail canary: break one guarantee at a time and watch ITS test fail.

A green suite proves the suite exited zero, not that any test could have gone
red. So for every guarantee README.md lists, this canary:

1. copies the tool directory into a throw-away tree (the tool and its tests
   resolve every path from their own location, so the copy is self-contained
   and the real tree is never touched);
2. applies ONE mutation to that guarantee's own clause, and asserts it LANDED:
   the anchor occurred exactly once before, the replacement occurs exactly once
   after, and the file changed. A mutation that silently matched nothing would
   leave an unchanged copy whose green reads as attribution;
3. runs the guarantee's own test(s) and requires each to FAIL by an assertion,
   never to ERROR: an exception, an import error or a crash is a red wearing
   the right colour for the wrong reason;
4. runs a NEIGHBOURING test on the same mutant and requires it to PASS, so the
   red is attributable to the clause and not to a tool the mutant broke whole.

Before any mutant, every target and control runs once against an unmutated copy
and must pass: otherwise a test that was already red would "kill" every mutant.

It then proves the runner itself: the suite's own entry point exits 1 on a real
fault and names the failed test, and a selection that holds no test refuses with
exit 2 rather than passing over nothing.

Exit 0 every mutant killed and every control green; 1 a mutant survived, a
control failed, an error stood in for a failure or a mutation did not land;
2 the canary cannot run (an unmutated baseline is red).
"""

import concurrent.futures
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.dirname(HERE)
TIMEOUT = 240

RUN_PY = "lib/trace_run.py"
STORE_PY = "lib/trace_store.py"
SCHEMA_PY = "lib/trace_schema.py"
RUN_SH = "bin/trace-run"
ANNOTATE_SH = "bin/trace-annotate"

EXIT = "TestExitFidelity.test_exit_status_passthrough"
SIGNALS = "TestSignalFidelity."
FORWARDED = SIGNALS + "test_sigterm_and_sighup_are_forwarded"
NOT_FORWARDED = SIGNALS + "test_sigint_and_sigquit_are_not_forwarded"
PIPE = SIGNALS + "test_pipeline_relying_on_silent_sigpipe"
XFSZ = SIGNALS + "test_sigxfsz_takes_its_default_action_in_the_child"
DISPOSITIONS = SIGNALS + "test_child_dispositions_and_mask_equal_the_off_path"
ENV_REFUSALS = "TestRefusals.test_environment_refusals_are_named"
UNWRITABLE = "TestRefusals.test_unwritable_directory_is_refused"
VALIDATOR = "TestValidator."

# (id, file, anchor, replacement, targets, control). Each anchor is the exact
# production text of the clause under test.
MUTANTS = (
    (
        "off-path-starts-python",
        RUN_SH,
        'if [ -z "${TELEMETRY_DIR+set}" ]; then',
        "if false; then",
        ["TestOffPath.test_off_path_runs_zero_python"],
        ENV_REFUSALS,
    ),
    (
        "annotate-off-path-starts-python",
        ANNOTATE_SH,
        '[ -n "${TELEMETRY_DIR+set}" ] || exit 0',
        '[ -n "${TELEMETRY_DIR+set}" ] || true',
        ["TestOffPath.test_off_path_runs_zero_python"],
        "TestAnnotate.test_reserved_namespace_is_refused_in_both_modes",
    ),
    (
        "descriptors-closed",
        RUN_PY,
        "            _signal.pthread_sigmask(_signal.SIG_SETMASK, sigmask)\n"
        "            _execvp(command, env, search_path)",
        "            _signal.pthread_sigmask(_signal.SIG_SETMASK, sigmask)\n"
        "            os.closerange(3, 1024)\n"
        "            _execvp(command, env, search_path)",
        [
            SIGNALS + "test_descriptors_pass_through_untouched",
            "TestJobserver.test_jobserver_survives_make_j",
        ],
        EXIT,
    ),
    (
        "sigpipe-left-ignored",
        RUN_PY,
        "reset = [s for s in (_signal.SIGPIPE, _signal.SIGXFSZ) if not caller_ignores(s)]",
        "reset = [s for s in (_signal.SIGXFSZ,) if not caller_ignores(s)]",
        [PIPE],
        XFSZ,
    ),
    (
        "sigxfsz-left-ignored",
        RUN_PY,
        "reset = [s for s in (_signal.SIGPIPE, _signal.SIGXFSZ) if not caller_ignores(s)]",
        "reset = [s for s in (_signal.SIGPIPE,) if not caller_ignores(s)]",
        [XFSZ],
        PIPE,
    ),
    (
        "caller-ignore-forced-default",
        RUN_PY,
        "            return (ignored >> (sig - 1)) & 1 == 1",
        "            return False",
        [DISPOSITIONS],
        PIPE,
    ),
    (
        "dispositions-read-from-the-shell",
        RUN_PY,
        "        return _signal.getsignal(sig) == _signal.SIG_IGN",
        "        return (ignored >> (sig - 1)) & 1 == 1",
        [SIGNALS + "test_caller_ignoring_sigchld_still_gets_the_exit_status", NOT_FORWARDED],
        EXIT,
    ),
    (
        "child-mask-not-restored",
        RUN_PY,
        "            _signal.pthread_sigmask(_signal.SIG_SETMASK, sigmask)\n",
        "",
        [DISPOSITIONS],
        EXIT,
    ),
    (
        "sigterm-not-forwarded",
        RUN_PY,
        "forwarded = [s for s in (_signal.SIGHUP, _signal.SIGTERM) if not caller_ignores(s)]",
        "forwarded = [s for s in (_signal.SIGHUP,) if not caller_ignores(s)]",
        [FORWARDED],
        SIGNALS + "test_child_killed_by_sigkill_is_reraised",
    ),
    (
        "terminal-signals-forwarded",
        RUN_PY,
        "    for sig in terminal:\n        _signal.signal(sig, _signal.SIG_IGN)",
        "    for sig in terminal:\n        _signal.signal(sig, forward)",
        [NOT_FORWARDED],
        FORWARDED,
    ),
    (
        "wrapper-dies-of-sigint",
        RUN_PY,
        "    for sig in terminal:\n        _signal.signal(sig, _signal.SIG_IGN)",
        "    for sig in terminal:\n        pass",
        [NOT_FORWARDED],
        FORWARDED,
    ),
    (
        "signal-death-not-reraised",
        RUN_PY,
        '        _die_like(exit_record["signal"])',
        '        os._exit(128 + exit_record["signal"])',
        [FORWARDED],
        EXIT,
    ),
    (
        "exit-status-flattened",
        RUN_PY,
        '    os._exit(exit_record["code"])',
        '    os._exit(1 if exit_record["code"] else 0)',
        [EXIT],
        FORWARDED,
    ),
    (
        "child-in-its-own-group",
        RUN_PY,
        "            for sig in sigdef:\n                _signal.signal(sig, _signal.SIG_DFL)\n",
        "            for sig in sigdef:\n                _signal.signal(sig, _signal.SIG_DFL)\n"
        "            os.setpgid(0, 0)\n",
        [SIGNALS + "test_child_stays_in_the_callers_process_group"],
        EXIT,
    ),
    (
        "traceparent-not-advanced",
        RUN_PY,
        'env[b"TRACEPARENT"] = S.format_traceparent(trace_id, span_id, flags).encode()',
        'env[b"TRACEPARENT"] = S.format_traceparent(trace_id, parent_id, flags).encode()',
        ["TestNesting.test_nesting_through_real_children"],
        EXIT,
    ),
    (
        "start-marker-skipped",
        RUN_PY,
        "        write_once(base + S.START_SUFFIX, record_bytes(start))",
        "        record_bytes(start)",
        ["TestNesting.test_start_marker_exists_before_the_child_runs"],
        EXIT,
    ),
    (
        "unwritable-directory-runs-anyway",
        RUN_PY,
        "    except OSError as err:\n        _refuse(prog, classify_write_error(err, spans_dir), _NOT_RUN)\n\n"
        "    def caller_ignores",
        "    except OSError:\n        pass\n\n    def caller_ignores",
        [UNWRITABLE],
        ENV_REFUSALS,
    ),
    (
        "missing-directory-misnamed",
        RUN_PY,
        'raise Refusal("telemetry-dir-missing", f"TELEMETRY_DIR={tdir} does not exist")',
        'raise Refusal("telemetry-dir-unusable", f"TELEMETRY_DIR={tdir} does not exist")',
        [ENV_REFUSALS],
        UNWRITABLE,
    ),
    (
        "signals-not-blocked-across-the-spawn",
        RUN_PY,
        "caller_mask = _signal.pthread_sigmask(_signal.SIG_BLOCK, forwarded + terminal)",
        "caller_mask = _signal.pthread_sigmask(_signal.SIG_BLOCK, [])",
        [SIGNALS + "test_a_signal_during_the_spawn_is_held_and_forwarded"],
        FORWARDED,
    ),
    (
        "ignored-sigchld-kept",
        RUN_PY,
        "        _signal.signal(_signal.SIGCHLD, _signal.SIG_DFL)",
        "        pass",
        [SIGNALS + "test_caller_ignoring_sigchld_still_gets_the_exit_status"],
        EXIT,
    ),
    (
        "no-shebang-not-run-by-sh",
        RUN_PY,
        '                os.execve("/bin/sh", ["/bin/sh", candidate, *command[1:]], env)',
        "                raise",
        ["TestExitFidelity.test_script_without_shebang_runs_like_exec"],
        EXIT,
    ),
    (
        "interpreter-not-isolated",
        RUN_SH,
        'exec "$gate_trace__python" -I -S -c',
        'exec "$gate_trace__python" -I -c',
        ["TestOffPath.test_interpreter_starts_isolated"],
        EXIT,
    ),
    (
        "bytecode-cache-disabled",
        RUN_SH,
        'exec "$gate_trace__python" -I -S -c',
        'exec "$gate_trace__python" -I -S -B -c',
        ["TestOffPath.test_the_wrapper_is_bytecode_cached"],
        "TestOffPath.test_interpreter_starts_isolated",
    ),
    (
        "shim-name-grammar-drifts",
        RUN_SH,
        "*[!ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:/+=@-]*)",
        "*[!ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:/+=@*-]*)",
        ["TestGrammarAgreement.test_span_name_grammar_agrees"],
        "TestGrammarAgreement.test_kind_grammar_agrees",
    ),
    (
        "python-key-grammar-drifts",
        SCHEMA_PY,
        '_KEY_TAIL = _KEY_HEAD | frozenset(_DIGITS + "_")',
        '_KEY_TAIL = _KEY_HEAD | frozenset(_DIGITS + "_-")',
        ["TestGrammarAgreement.test_annotation_key_grammar_agrees"],
        "TestGrammarAgreement.test_span_name_grammar_agrees",
    ),
    (
        "write-not-atomic",
        RUN_PY,
        "    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o644)",
        "    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o644)",
        ["TestRecordPrimitives.test_write_once_leaves_nothing_behind_a_failed_write"],
        "TestRecordPrimitives.test_encoder_round_trips_through_the_standard_parser",
    ),
    (
        "truncation-unchecked",
        STORE_PY,
        '    if not data.endswith(b"\\n"):',
        "    if False:",
        [VALIDATOR + "test_a_truncated_record_is_named"],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "unknown-keys-admitted",
        STORE_PY,
        'out = [("unknown-key", where, f"{key!r}") for key in value if key not in keys]',
        "out = []",
        [VALIDATOR + "test_an_unknown_key_is_named"],
        VALIDATOR + "test_a_dangling_parent_is_named",
    ),
    (
        "unknown-schema-admitted",
        STORE_PY,
        "    if isinstance(version, bool) or version not in S.SUPPORTED_SCHEMAS:",
        "    if False:",
        [VALIDATOR + "test_an_unknown_schema_version_is_named"],
        VALIDATOR + "test_a_truncated_record_is_named",
    ),
    (
        "orphan-unreported",
        STORE_PY,
        '            findings.append(\n                Finding(\n                    "orphan-span",',
        '            [].append(\n                Finding(\n                    "orphan-span",',
        [VALIDATOR + "test_an_orphan_start_marker_is_named"],
        VALIDATOR + "test_a_dangling_parent_is_named",
    ),
    (
        "dangling-parent-unreported",
        STORE_PY,
        '            findings.append(\n                Finding("dangling-parent", where,',
        '            [].append(\n                Finding("dangling-parent", where,',
        [VALIDATOR + "test_a_dangling_parent_is_named"],
        VALIDATOR + "test_an_orphan_start_marker_is_named",
    ),
)

OTHER_CLAUSES = VALIDATOR + "test_every_other_integrity_clause_names_itself"
HEADER = "TestHeader."

MUTANTS += (
    (
        "unmarked-span-unreported",
        STORE_PY,
        '                findings.append(\n                    Finding("unmarked-span", where,',
        '                [].append(\n                    Finding("unmarked-span", where,',
        [OTHER_CLAUSES],
        VALIDATOR + "test_a_dangling_parent_is_named",
    ),
    (
        "start-mismatch-unreported",
        STORE_PY,
        "            if differing:",
        "            if False:",
        [OTHER_CLAUSES],
        VALIDATOR + "test_a_dangling_parent_is_named",
    ),
    (
        "conflicting-annotation-unreported",
        STORE_PY,
        "            if key in folded and folded[key] != value:",
        "            if False:",
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "late-annotation-unreported",
        STORE_PY,
        '        elif end is not None and record["time_mono_ns"] > end:',
        "        elif False:",
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "empty-run-unreported",
        STORE_PY,
        "    if not run.named:",
        "    if False:",
        [VALIDATOR + "test_a_run_with_no_span_is_named"],
        VALIDATOR + "test_an_orphan_start_marker_is_named",
    ),
    (
        "hostname-recorded-by-default",
        STORE_PY,
        '"name": uname.nodename if with_hostname else None,',
        '"name": uname.nodename,',
        [HEADER + "test_a_clean_tree_and_its_privacy_defaults"],
        HEADER + "test_a_range_changeset_names_the_changed_paths",
    ),
    (
        "untracked-bytes-not-digested",
        STORE_PY,
        "                        digest.update(chunk)",
        "                        pass",
        [HEADER + "test_a_dirty_tree_is_digested_and_the_digest_follows_content"],
        HEADER + "test_a_clean_tree_and_its_privacy_defaults",
    ),
    (
        "range-paths-from-the-wrong-diff",
        STORE_PY,
        'names = _git(top, ["diff", "--name-only", "-z", base, tip], env)',
        'names = _git(top, ["diff", "--name-only", "-z", base, base], env)',
        [HEADER + "test_a_range_changeset_names_the_changed_paths"],
        HEADER + "test_a_dirty_tree_is_digested_and_the_digest_follows_content",
    ),
    (
        "toolchain-term-optional",
        STORE_PY,
        "    if not terms:",
        "    if False:",
        [HEADER + "test_start_refuses_a_nested_run_and_a_run_without_a_toolchain"],
        HEADER + "test_a_clean_tree_and_its_privacy_defaults",
    ),
    (
        "nested-run-admitted",
        STORE_PY,
        '    if "TELEMETRY_DIR" in os.environ:',
        "    if False:",
        [HEADER + "test_start_refuses_a_nested_run_and_a_run_without_a_toolchain"],
        HEADER + "test_a_clean_tree_and_its_privacy_defaults",
    ),
    (
        "git-start-failure-internal",
        STORE_PY,
        "    except OSError as err:\n        # git never started",
        "    except ValueError as err:\n        # git never started",
        [HEADER + "test_start_refuses_when_git_cannot_run"],
        HEADER + "test_start_refuses_a_nested_run_and_a_run_without_a_toolchain",
    ),
    (
        "git-start-failure-misnamed",
        STORE_PY,
        '        if err.filename == "git":',
        "        if False:",
        [HEADER + "test_start_refuses_when_git_cannot_run"],
        HEADER + "test_start_refuses_a_nested_run_and_a_run_without_a_toolchain",
    ),
)

MUTANTS += (
    (
        "relative-directory-admitted",
        RUN_PY,
        "    if not os.path.isabs(tdir):",
        "    if False:",
        [ENV_REFUSALS],
        UNWRITABLE,
    ),
    (
        "missing-python-left-to-exec",
        RUN_SH,
        "gate_trace__python=$(command -v python3) || gate_trace__refuse launcher 'python3 is not on PATH'",
        "gate_trace__python=python3",
        [ENV_REFUSALS],
        UNWRITABLE,
    ),
)

ENVIRONMENT = "TestEnvironment.test_child_environment_equals_the_off_path"
SIGCHLD_EXIT = SIGNALS + "test_caller_ignoring_sigchld_still_gets_the_exit_status"
CASCADE = VALIDATOR + "test_a_rejected_annotated_span_cascades_nothing"

# (id, file, anchor, replacement, targets, control[, detail]). A detail is text
# the target's failure must contain, for a mutant whose red must name its cause.
MUTANTS += (
    (
        "coerced-lc-ctype-leaks",
        RUN_PY,
        "    if caller_ctype is None:\n"
        '        env.pop(b"LC_CTYPE", None)\n'
        "    else:\n"
        '        env[b"LC_CTYPE"] = os.fsencode(caller_ctype)\n',
        "",
        [ENVIRONMENT],
        EXIT,
    ),
    (
        "child-sigchld-left-default",
        RUN_PY,
        "            for sig in sigign:\n                _signal.signal(sig, _signal.SIG_IGN)\n",
        "",
        [DISPOSITIONS],
        SIGCHLD_EXIT,
    ),
    (
        "spawned-through-posix-spawn",
        RUN_PY,
        "    failure_r, failure_w = os.pipe()\n    pid = os.fork()",
        "    return os.posix_spawnp(command[0], command, env, setsigmask=sigmask, setsigdef=sigdef)\n"
        "    failure_r, failure_w = os.pipe()\n    pid = os.fork()",
        [DISPOSITIONS],
        EXIT,
        "SigIgn differs from the off path in: SIGCHLD",
    ),
    (
        "inconsistent-final-dropped",
        STORE_PY,
        "            run.unjudged[span_id] = record\n            continue\n        run.spans[span_id] = record",
        "            continue\n        run.spans[span_id] = record",
        [VALIDATOR + "test_an_inconsistent_spans_annotations_are_still_judged"],
        VALIDATOR + "test_a_dangling_parent_is_named",
    ),
    (
        "rejected-span-annotation-reported",
        STORE_PY,
        "            if span_id not in run.named:",
        "            if True:",
        [CASCADE],
        VALIDATOR + "test_a_dangling_parent_is_named",
    ),
    (
        "cycle-reported-per-member",
        STORE_PY,
        "                if members not in reported_cycles:",
        "                if True:",
        [VALIDATOR + "test_a_parent_cycle_is_reported_once"],
        VALIDATOR + "test_a_dangling_parent_is_named",
    ),
    (
        "empty-run-counts-whole-spans-only",
        STORE_PY,
        "    if not run.named:",
        "    if not run.spans and not run.orphans:",
        [VALIDATOR + "test_a_run_whose_only_span_is_rejected_is_not_also_empty"],
        VALIDATOR + "test_a_run_with_no_span_is_named",
    ),
    (
        "missing-end-unreported",
        STORE_PY,
        '        findings.append(Finding("missing-end", S.END_FILE, "absent"))',
        '        [].append(Finding("missing-end", S.END_FILE, "absent"))',
        [VALIDATOR + "test_an_unfinished_run_is_named_by_the_reader"],
        VALIDATOR + "test_an_orphan_start_marker_is_named",
    ),
    (
        "duplicate-keys-admitted",
        STORE_PY,
        "            raise _DuplicateKey(key)",
        "            pass",
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "malformed-unreported",
        STORE_PY,
        '        findings.append(Finding("malformed-record", where, str(err)))',
        '        [].append(Finding("malformed-record", where, str(err)))',
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "oversize-unchecked",
        STORE_PY,
        "        if size > S.MAX_RECORD_BYTES:",
        "        if False:",
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "unknown-record-admitted",
        STORE_PY,
        '    if record.get("record") != expected_record:',
        "    if False:",
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "missing-keys-admitted",
        STORE_PY,
        '                out.append(("missing-key", where, f"{key!r}"))',
        "                pass",
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_orphan_start_marker_is_named",
    ),
    (
        "misnamed-records-admitted",
        STORE_PY,
        '        if record["span_id"] != span_id:\n            findings.append(Finding("misnamed-record",',
        '        if False:\n            findings.append(Finding("misnamed-record",',
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "dangling-annotation-unreported",
        STORE_PY,
        '                findings.append(\n                    Finding("dangling-annotation",',
        '                [].append(\n                    Finding("dangling-annotation",',
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "attribute-bound-unchecked",
        STORE_PY,
        "        if own + len(folded) > S.MAX_ATTRIBUTES_PER_SPAN:",
        "        if False:",
        [OTHER_CLAUSES],
        VALIDATOR + "test_an_unknown_key_is_named",
    ),
    (
        "unusable-directory-misnamed",
        RUN_PY,
        'raise Refusal("telemetry-dir-unusable", f"TELEMETRY_DIR={tdir}: {err.strerror}")',
        'raise Refusal("telemetry-dir-missing", f"TELEMETRY_DIR={tdir}: {err.strerror}")',
        [ENV_REFUSALS],
        UNWRITABLE,
    ),
    (
        "interpreter-floor-ignored",
        RUN_PY,
        "    if sys.version_info < MIN_PYTHON:",
        "    if False:",
        ["TestRefusals.test_an_old_interpreter_is_refused_by_name"],
        ENV_REFUSALS,
    ),
)

PLAIN_ORACLE = "TestEnvironment.test_off_path_environment_equals_a_plain_exec"

MUTANTS += (
    (
        # Rewrites a caller's exported name on BOTH paths, so the ON-versus-OFF
        # test is blind to it by construction; only the plain-exec oracle sees it.
        "shim-assigns-a-caller-name-off-path",
        RUN_SH,
        "\tgate_trace__kind=\n\twhile",
        "\tkind= gate_trace__kind=\n\twhile",
        [PLAIN_ORACLE],
        ENVIRONMENT,
    ),
    (
        # Rewrites it on the ON path only, which the plain-exec oracle never runs.
        "shim-assigns-a-caller-name-on-path",
        RUN_SH,
        "gate_trace__self=$0\n",
        "self=$0 gate_trace__self=$0\n",
        [ENVIRONMENT],
        PLAIN_ORACLE,
    ),
    (
        "non-regular-record-not-rejected",
        STORE_PY,
        '"named like a record but not a regular file")\n            )\n'
        "            rejected[kind].add(span_id)\n",
        '"named like a record but not a regular file")\n            )\n',
        [OTHER_CLAUSES],
        VALIDATOR + "test_a_dangling_parent_is_named",
    ),
)

PATH_UNSET = "TestEnvironment.test_with_path_unset_the_wrapper_searches_where_the_shell_does"
ENTRY_NAMING = "TestSearch.test_entries_are_named_and_skipped_as_dash_does"

MUTANTS += (
    (
        # The shim's own errexit and nounset leak through an exported SHELLOPTS
        # under bash. Dropping the OFF-path restore breaks BOTH tests: OFF then
        # differs from a plain exec, AND from the ON path, which still restores.
        "shell-options-leak-off-path",
        RUN_SH,
        '\tset "$gate_trace__set_a" "$gate_trace__set_e" "$gate_trace__set_u" '
        '"$gate_trace__set_v" "$gate_trace__set_x"\n\texec "$@"',
        '\texec "$@"',
        [PLAIN_ORACLE, ENVIRONMENT],
        EXIT,
    ),
    (
        # The ON path alone, which the plain-exec oracle never runs.
        "shell-options-leak-on-path",
        RUN_SH,
        'set "$gate_trace__set_a" "$gate_trace__set_e" "$gate_trace__set_u" '
        '"$gate_trace__set_v" "$gate_trace__set_x"\nexec "$gate_trace__python"',
        'exec "$gate_trace__python"',
        [ENVIRONMENT],
        PLAIN_ORACLE,
    ),
    (
        # The previous repair: switching errexit and nounset OFF before the exec
        # strips a caller's own. Off-path only, so both tests see it.
        "caller-options-cleared-off-path",
        RUN_SH,
        '\tset "$gate_trace__set_a" "$gate_trace__set_e" "$gate_trace__set_u" '
        '"$gate_trace__set_v" "$gate_trace__set_x"\n\texec "$@"',
        '\tset +eu\n\texec "$@"',
        [PLAIN_ORACLE, ENVIRONMENT],
        EXIT,
    ),
    (
        "caller-options-cleared-on-path",
        RUN_SH,
        'set "$gate_trace__set_a" "$gate_trace__set_e" "$gate_trace__set_u" '
        '"$gate_trace__set_v" "$gate_trace__set_x"\nexec "$gate_trace__python"',
        'set +eu\nexec "$gate_trace__python"',
        [ENVIRONMENT],
        PLAIN_ORACLE,
    ),
    (
        # allexport, xtrace and verbose left as a caller exported them: under
        # allexport the shim's functions and variables reach the command.
        "caller-allexport-adopted",
        RUN_SH,
        'set -- "$-" "$@"\nset +axv\n',
        'set -- "$-" "$@"\n',
        [PLAIN_ORACLE, ENVIRONMENT],
        EXIT,
    ),
    (
        "empty-path-entry-named-dot",
        RUN_PY,
        'entry + "/" + name if entry else name',
        'os.path.join(entry or ".", name)',
        [ENTRY_NAMING],
        EXIT,
    ),
    (
        "trailing-slash-collapsed",
        RUN_PY,
        'entry + "/" + name if entry else name',
        "os.path.join(entry, name) if entry else name",
        [ENTRY_NAMING],
        EXIT,
    ),
    (
        "percent-entry-searched",
        RUN_PY,
        '            if "%" not in entry\n',
        "",
        [ENTRY_NAMING],
        EXIT,
    ),
    (
        "interpreter-search-path-used",
        RUN_PY,
        "for entry in search_path.split(os.pathsep)",
        "for entry in os.defpath.split(os.pathsep)",
        [PATH_UNSET],
        EXIT,
    ),
)

SEARCH_ERRORS = "TestSearch.test_errors_while_searching_are_handled_as_dash_handles_them"
USAGE = "TestRefusals.test_usage_is_refused_in_both_modes"
LIBRARY_USAGE = "TestRefusals.test_the_library_refuses_an_empty_or_dash_command_word"

MUTANTS += (
    (
        "keep-rule-keeps-not-a-directory",
        RUN_PY,
        'if "/" in name or err.errno not in (errno.ENOENT, errno.ENOTDIR):',
        'if "/" in name or err.errno != errno.ENOENT:',
        [SEARCH_ERRORS],
        EXIT,
    ),
    (
        "search-stops-at-an-unexpected-error",
        RUN_PY,
        '            if "/" in name or err.errno not in (errno.ENOENT, errno.ENOTDIR):\n'
        "                kept = err",
        '            if "/" in name or err.errno not in (errno.ENOENT, errno.ENOTDIR):\n'
        "                raise",
        [SEARCH_ERRORS],
        EXIT,
    ),
    (
        "eloop-reported-as-126",
        RUN_PY,
        "not_found = (errno.ENOENT, errno.ENOTDIR, errno.ELOOP, errno.ENAMETOOLONG)",
        "not_found = (errno.ENOENT, errno.ENOTDIR, errno.ENAMETOOLONG)",
        [SEARCH_ERRORS],
        EXIT,
    ),
    (
        "enametoolong-reported-as-126",
        RUN_PY,
        "not_found = (errno.ENOENT, errno.ENOTDIR, errno.ELOOP, errno.ENAMETOOLONG)",
        "not_found = (errno.ENOENT, errno.ENOTDIR, errno.ELOOP)",
        [SEARCH_ERRORS],
        EXIT,
    ),
    (
        "slash-path-enotdir-reported-as-126",
        RUN_PY,
        "not_found = (errno.ENOENT, errno.ENOTDIR, errno.ELOOP, errno.ENAMETOOLONG)",
        "not_found = (errno.ENOENT, errno.ELOOP, errno.ENAMETOOLONG)",
        [SEARCH_ERRORS],
        EXIT,
    ),
    (
        "interpreter-started-by-bare-name",
        RUN_SH,
        'exec "$gate_trace__python" -I -S',
        "exec python3 -I -S",
        [SEARCH_ERRORS],
        EXIT,
    ),
    (
        "search-keeps-the-first-error",
        RUN_PY,
        '            if "/" in name or err.errno not in (errno.ENOENT, errno.ENOTDIR):\n'
        "                kept = err",
        '            if "/" in name or err.errno not in (errno.ENOENT, errno.ENOTDIR):\n'
        "                kept = kept or err",
        [SEARCH_ERRORS],
        EXIT,
    ),
    (
        "shim-runs-a-leading-dash-command",
        RUN_SH,
        "\t\t\tcase $2 in '' | -*) gate_trace__refuse usage "
        "'the command may not be empty or begin with -' ;; esac\n",
        "",
        [USAGE],
        LIBRARY_USAGE,
    ),
    (
        "shim-passes-an-empty-command",
        RUN_SH,
        "\t\t\tcase $2 in '' | -*)",
        "\t\t\tcase $2 in -*)",
        [USAGE],
        LIBRARY_USAGE,
    ),
    (
        "library-runs-a-leading-dash-command",
        RUN_PY,
        '            if not rest[1] or rest[1].startswith("-"):',
        "            if False:",
        [LIBRARY_USAGE],
        USAGE,
    ),
    (
        "library-passes-an-empty-command",
        RUN_PY,
        '            if not rest[1] or rest[1].startswith("-"):',
        '            if rest[1].startswith("-"):',
        [LIBRARY_USAGE],
        USAGE,
    ),
)

# Every shim starts its interpreter by a path, and locates itself when found
# through an empty PATH entry; one mutant per shim for each half.
SHIMS_BY_PATH = "TestSearch.test_every_shim_starts_its_interpreter_by_a_path"
SELF_LOCATION = "TestSearch.test_a_shim_found_through_an_empty_entry_locates_its_library"
MUTANTS += tuple(
    (
        f"{shim}-starts-python-by-bare-name",
        f"bin/{shim}",
        'exec "$gate_trace__python" -I -S',
        "exec python3 -I -S",
        [SHIMS_BY_PATH],
        EXIT,
    )
    for shim in ("trace-start", "trace-annotate", "trace-finish")
)
MUTANTS += tuple(
    (
        f"{shim}-keeps-a-bare-interpreter-name",
        f"bin/{shim}",
        'if [ -f "./$gate_trace__python" ]; then',
        "if false; then",
        [SHIMS_BY_PATH],
        EXIT,
    )
    for shim in ("trace-run", "trace-start", "trace-annotate", "trace-finish")
)
MUTANTS += tuple(
    (
        f"{shim}-asks-path-for-a-bare-self",
        f"bin/{shim}",
        'if [ -f "./$gate_trace__self" ]; then',
        "if false; then",
        [SELF_LOCATION],
        EXIT,
    )
    for shim in ("trace-run", "trace-start", "trace-annotate", "trace-finish")
)

# The mutant whose copy also drives the suite's own entry point, end to end.
RUNNER_MUTANT = "exit-status-flattened"


class CanaryError(Exception):
    """A precondition of the canary failed: exit 2."""


def make_copy(scratch):
    """A self-contained copy of the tool, readable by the unprivileged identity
    the unwritable-directory test switches to when it runs as root."""
    target = os.path.join(tempfile.mkdtemp(dir=scratch), "gate-trace")
    shutil.copytree(TOOL, target, ignore=shutil.ignore_patterns("__pycache__"))
    for path, _dirs, files in os.walk(os.path.dirname(target)):
        os.chmod(path, 0o755)
        for name in files:
            full = os.path.join(path, name)
            os.chmod(full, 0o755 if os.access(full, os.X_OK) else 0o644)
    return target


def mutate(copy, relpath, anchor, replacement):
    """Apply one mutation and prove it landed; returns a problem or None.

    Landing is proven on the exact bytes: the anchor occurred once, the file
    changed, everything before the anchor is untouched, the replacement now sits
    where the anchor was, and everything after it is the original tail.
    """
    path = os.path.join(copy, relpath)
    with open(path, encoding="utf-8") as handle:
        before = handle.read()
    if before.count(anchor) != 1:
        return f"anchor occurs {before.count(anchor)} times in {relpath}, not once"
    at = before.index(anchor)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(before[:at] + replacement + before[at + len(anchor) :])
    with open(path, encoding="utf-8") as handle:
        landed = handle.read()
    tail = before[at + len(anchor) :]
    if (
        landed == before
        or landed[:at] != before[:at]
        or landed[at : at + len(replacement)] != replacement
        or landed[at + len(replacement) :] != tail
    ):
        return f"the mutation did not land in {relpath}"
    return None


def run_test(copy, name):
    """The suite's JSON verdict for one test in `copy`, or a string problem."""
    suite = os.path.join(copy, "test", "test_gate_trace.py")
    try:
        proc = subprocess.run(
            [sys.executable, "-I", "-S", suite, "--json", name],
            capture_output=True,
            timeout=TIMEOUT,
            stdin=subprocess.DEVNULL,
        )
    except subprocess.TimeoutExpired:
        return f"{name}: timed out after {TIMEOUT}s (inconclusive, never a kill)"
    lines = proc.stdout.decode().strip().splitlines()
    if proc.returncode not in (0, 1) or not lines:
        return f"{name}: the runner could not run it (exit {proc.returncode}): {proc.stderr.decode()[-400:]}"
    verdict = json.loads(lines[-1])
    if verdict["selected"] != 1 or verdict["run"] != 1:
        return f"{name}: selected {verdict['selected']}, ran {verdict['run']}"
    return verdict


def passed(verdict):
    return not (
        verdict["failures"] or verdict["errors"] or verdict["skipped"] or verdict["unexpected"]
    )


def judge(scratch, mutant):
    """(ok, line) for one mutant."""
    mutant_id, relpath, anchor, replacement, targets, control = mutant[:6]
    detail = mutant[6] if len(mutant) > 6 else None
    copy = make_copy(scratch)
    problem = mutate(copy, relpath, anchor, replacement)
    if problem:
        return False, f"NOT LANDED {mutant_id}: {problem}"
    for target in targets:
        verdict = run_test(copy, target)
        if isinstance(verdict, dict) and detail and detail not in verdict["failure_text"]:
            return False, f"MISATTRIBUTED {mutant_id}: {target} failed without {detail!r}"
        if isinstance(verdict, str):
            return False, f"INCONCLUSIVE {mutant_id}: {verdict}"
        if verdict["errors"]:
            return False, f"ERROR NOT FAIL {mutant_id}: {target} errored: {verdict['errors']}"
        if not verdict["failures"]:
            return False, f"SURVIVED {mutant_id}: {target} still passes"
        stray = [f for f in verdict["failures"] if not f.endswith(target) and target not in f]
        if stray:
            return False, f"MISATTRIBUTED {mutant_id}: {stray}"
    verdict = run_test(copy, control)
    if isinstance(verdict, str):
        return False, f"INCONCLUSIVE {mutant_id}: control {verdict}"
    if not passed(verdict):
        return (
            False,
            f"CONTROL RED {mutant_id}: {control} failed too, so the red is not attributable",
        )
    return True, f"killed {mutant_id}: {', '.join(targets)} FAILED; control {control} passed"


def baseline(scratch):
    """Every target and control must pass on an unmutated copy."""
    names = sorted({name for m in MUTANTS for name in (*m[4], m[5])})
    copy = make_copy(scratch)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers()) as pool:
        verdicts = dict(zip(names, pool.map(lambda n: run_test(copy, n), names), strict=True))
    red = [n for n, v in verdicts.items() if isinstance(v, str) or not passed(v)]
    if red:
        raise CanaryError(f"the unmutated baseline is red for {red}: {[verdicts[n] for n in red]}")
    return len(names)


def runner_canaries(scratch):
    """The suite's own entry point fails on a real fault and refuses an empty selection."""
    problems = []
    (mutant,) = [m for m in MUTANTS if m[0] == RUNNER_MUTANT]
    copy = make_copy(scratch)
    problem = mutate(copy, mutant[1], mutant[2], mutant[3])
    if problem:
        return [f"runner canary: {problem}"]
    suite = os.path.join(copy, "test", "run_tests.sh")
    proc = subprocess.run(
        ["bash", suite, "--suite-only"], capture_output=True, timeout=900, stdin=subprocess.DEVNULL
    )
    text = proc.stderr.decode()
    if proc.returncode != 1 or f"FAIL: {mutant[4][0].split('.')[-1]}" not in text:
        problems.append(
            f"runner canary: run_tests.sh exited {proc.returncode} without naming the fault"
        )
    entry = os.path.join(copy, "test", "test_gate_trace.py")
    for selection in ("Fixture", "TestNoSuchClass"):
        empty = subprocess.run(
            [sys.executable, "-I", "-S", entry, selection],
            capture_output=True,
            timeout=TIMEOUT,
            stdin=subprocess.DEVNULL,
        )
        if empty.returncode != 2 or b"CANNOT RUN" not in empty.stderr:
            problems.append(
                f"runner canary: selecting {selection!r} exited {empty.returncode}, not 2"
            )
    return problems


def workers():
    return max(1, min(6, (os.cpu_count() or 2) // 4))


def main():
    scratch = tempfile.mkdtemp(prefix="gate-trace-mutants-")
    os.chmod(scratch, 0o755)
    try:
        controls = baseline(scratch)
        print(f"gate-trace canary: baseline green over {controls} target and control tests")
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers()) as pool:
            results = list(pool.map(lambda m: judge(scratch, m), MUTANTS))
        for _ok, line in results:
            print(f"  {line}")
        problems = [line for ok, line in results if not ok]
        problems += runner_canaries(scratch)
    except CanaryError as err:
        print(f"gate-trace canary: CANNOT RUN: {err}", file=sys.stderr)
        return 2
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    killed = sum(1 for ok, _ in results if ok)
    if problems:
        for line in problems:
            print(f"gate-trace canary: FAIL: {line}", file=sys.stderr)
        print(f"gate-trace canary: FAIL {killed} of {len(MUTANTS)} mutants killed", file=sys.stderr)
        return 1
    print(f"gate-trace canary: ok, {killed} of {len(MUTANTS)} mutants killed, runner proven")
    return 0


if __name__ == "__main__":
    sys.exit(main())
