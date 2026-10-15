#!/usr/bin/env python3
"""Manual affected-scope mutation assessment; never added to ordinary gates."""

import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
FAULTS = [
    (
        "fractional-ratio",
        "reduce.awk",
        'values["ns",i,r]/values["ns",0,r]',
        'int(values["ns",i,r]/1000000)/int(values["ns",0,r]/1000000)',
        "test_fractional_ms_ratios_use_raw_ns",
        "test_units_and_positive_control",
    ),
    (
        "decimal-consistency",
        "reduce.awk",
        'length($6)>6 ? substr($6,1,length($6)-6) : "0"',
        "int($6/1000000)",
        "test_large_ns_consistency_is_decimal",
        "test_units_and_positive_control",
    ),
    (
        "clock",
        "measure.py",
        "start = time.monotonic_ns()",
        "start = time.time_ns()",
        "test_units_and_positive_control",
        "test_option_values_required",
    ),
    (
        "units",
        "bench.sh",
        "variant\\telapsed_ms\\texit",
        "variant\\tseconds\\texit",
        "test_units_and_positive_control",
        "test_failed_variant_invalidates_comparison",
    ),
    (
        "exit",
        "bench.sh",
        'exit "$result"',
        "exit 0",
        "test_failed_variant_invalidates_comparison",
        "test_units_and_positive_control",
    ),
    (
        "rounds",
        "bench.sh",
        "^[1-9][0-9]*$",
        "^[0-9]+$",
        "test_positive_rounds_required",
        "test_units_and_positive_control",
    ),
    (
        "share",
        "reduce.awk",
        '100*values["ns",i,r]/total[r]',
        '100*values["ns",i,1]/total[r]',
        "test_per_round_reduction",
        "test_units_and_positive_control",
    ),
    (
        "ratio",
        "reduce.awk",
        'values["ns",i,r]/values["ns",0,r]',
        'values["ns",0,r]/values["ns",i,r]',
        "test_ratio_direction_and_nanosecond_retention",
        "test_units_and_positive_control",
    ),
    (
        "logs",
        "measure.py",
        "stdout=log, stderr=subprocess.STDOUT",
        "stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL",
        "test_command_output_preserved",
        "test_units_and_positive_control",
    ),
    (
        "overwrite",
        "bench.sh",
        'LOG="$(mktemp "$OUTDIR/${STAMP}-${LABEL}-XXXXXX.tsv")"',
        'LOG="$OUTDIR/${STAMP}-${LABEL}.tsv"',
        "test_repeated_label_preserves_raw_files",
        "test_units_and_positive_control",
    ),
    (
        "even-median",
        "reduce.awk",
        "(sorted[rounds/2]+sorted[rounds/2+1])/2",
        "sorted[rounds/2]",
        "test_even_median",
        "test_units_and_positive_control",
    ),
    (
        "ms-exponent",
        "reduce.awk",
        'return frac == "" ? whole : whole "." frac',
        'return sprintf("%g", twice / 2000000)',
        "test_large_ms_summary_keeps_fraction",
        "test_units_and_positive_control",
    ),
    (
        "ms-double-whole",
        "reduce.awk",
        'whole = n > 6 ? substr(ns, 1, n - 6) : "0"',
        "whole = int(ns / 1000000)",
        "test_ms_summary_exact_at_tsv_maximum",
        "test_large_ms_summary_keeps_fraction",
    ),
    (
        "half-nanosecond",
        "reduce.awk",
        '(rem ? "5" : "")',
        '""',
        "test_even_median_keeps_half_nanosecond",
        "test_even_median",
    ),
    (
        "decimal-length-order",
        "reduce.awk",
        'return length(a) < length(b) || (length(a) == length(b) && (a "") < (b ""))',
        'return (a "") < (b "")',
        "test_ratio_direction_and_nanosecond_retention",
        "test_even_median",
    ),
    (
        "odd-median-ms",
        "reduce.awk",
        "(rounds+1)/2 : rounds/2",
        "1 : rounds/2",
        "test_per_round_reduction",
        "test_even_median",
    ),
    (
        "even-median-ms",
        "reduce.awk",
        "sorted_ns[rounds%2 ? j : j+1]",
        "sorted_ns[j]",
        "test_even_median_keeps_half_nanosecond",
        "test_per_round_reduction",
    ),
    (
        "min-ms",
        "reduce.awk",
        "if (r==1 || dec_less(ns, lo[i])) lo[i]=ns",
        "if (r==1) lo[i]=ns",
        "test_even_median",
        "test_per_round_reduction",
    ),
    (
        "max-ms",
        "reduce.awk",
        "if (r==1 || dec_less(hi[i], ns)) hi[i]=ns",
        "if (r==1) hi[i]=ns",
        "test_per_round_reduction",
        "test_large_ms_summary_keeps_fraction",
    ),
    (
        "ms-ns-consistency",
        "reduce.awk",
        ' || ("x" expected_ms)!=("x" ms)',
        "",
        "test_reducer_refuses_inconsistent_ms",
        "test_nonpositive_clock_invalid",
    ),
    (
        "nonpositive",
        "reduce.awk",
        "$6<=0 || ",
        "",
        "test_nonpositive_clock_invalid",
        "test_reducer_refuses_inconsistent_ms",
    ),
    (
        "variant-name",
        "reduce.awk",
        ' || ((idx in name) && (name[idx] "")!=($3 ""))',
        "",
        "test_reducer_refuses_renamed_variant",
        "test_reducer_refuses_missing_duplicate_and_malformed_rows",
    ),
    (
        "variant-name-numeric",
        "reduce.awk",
        '(name[idx] "")!=($3 "")',
        "name[idx]!=$3",
        "test_reducer_compares_variant_names_as_text",
        "test_reducer_refuses_renamed_variant",
    ),
    (
        "label",
        "bench.sh",
        "^[A-Za-z0-9][A-Za-z0-9._-]*$",
        ".*",
        "test_label_refused",
        "test_positive_rounds_required",
    ),
    (
        "round-cap",
        "bench.sh",
        ' || [ "${#ROUNDS}" -gt 6 ]',
        "",
        "test_rounds_capped_at_six_digits",
        "test_positive_rounds_required",
    ),
    (
        "newline-command",
        "bench.sh",
        """ || "$cmd" == *$'\\n'*""",
        "",
        "test_tsv_unsafe_command_refused",
        "test_tab_command_refused",
    ),
    (
        "carriage-return-command",
        "bench.sh",
        """ || "$cmd" == *$'\\r'*""",
        "",
        "test_carriage_return_command_refused",
        "test_tsv_unsafe_command_refused",
    ),
    (
        "tab-command",
        "bench.sh",
        """"$cmd" == *$'\\t'* || """,
        "",
        "test_tab_command_refused",
        "test_empty_command_refused",
    ),
    (
        "empty-command",
        "bench.sh",
        ' || -z "$cmd"',
        "",
        "test_empty_command_refused",
        "test_carriage_return_command_refused",
    ),
    (
        "unknown-option",
        "bench.sh",
        '*) echo "bench: unknown option $1" >&2; exit 2 ;;',
        "*) break ;;",
        "test_unknown_option_refused",
        "test_option_values_required",
    ),
    (
        "no-variant",
        "bench.sh",
        "if [ $# -lt 1 ]; then",
        "if false; then",
        "test_no_variant_refused",
        "test_empty_command_refused",
    ),
    (
        "helper-failure-summary",
        "bench.sh",
        "echo 'INVALID: measurement helper failed; raw observations are incomplete.' >\"$SUMMARY\"",
        "true",
        "test_measurement_helper_failure_writes_invalid_summary",
        "test_malformed_elapsed_measurement_refused",
    ),
    (
        "malformed-elapsed",
        "bench.sh",
        '! "$elapsed_ns" =~ ^-?(0|[1-9][0-9]{0,17})$ || ',
        "",
        "test_malformed_elapsed_measurement_refused",
        "test_malformed_exit_measurement_refused",
    ),
    (
        "elapsed-digit-cap",
        "bench.sh",
        "^-?(0|[1-9][0-9]{0,17})$",
        "^-?[0-9]+$",
        "test_overlong_elapsed_measurement_refused",
        "test_malformed_elapsed_measurement_refused",
    ),
    (
        "malformed-exit",
        "bench.sh",
        ' || ! "$rc" =~ ^[0-9]{1,3}$',
        "",
        "test_malformed_exit_measurement_refused",
        "test_malformed_elapsed_measurement_refused",
    ),
    (
        "round-count",
        "reduce.awk",
        "rounds!~/^[1-9][0-9]*$/ || ",
        "",
        "test_reducer_refuses_malformed_round_count",
        "test_reducer_refuses_malformed_variant_count",
    ),
    (
        "variant-count",
        "reduce.awk",
        " || variants!~/^[1-9][0-9]*$/",
        "",
        "test_reducer_refuses_malformed_variant_count",
        "test_reducer_refuses_malformed_round_count",
    ),
    (
        "field-count",
        "reduce.awk",
        "NF!=6 || ",
        "",
        "test_reducer_refuses_wrong_field_count",
        "test_reducer_refuses_malformed_nanoseconds",
    ),
    (
        "round-format",
        "reduce.awk",
        "r!~/^[1-9][0-9]*$/ || ",
        "",
        "test_reducer_refuses_noncanonical_round",
        "test_reducer_refuses_noncanonical_variant",
    ),
    (
        "variant-format",
        "reduce.awk",
        "idx!~/^(0|[1-9][0-9]*)$/ ||",
        "",
        "test_reducer_refuses_noncanonical_variant",
        "test_reducer_refuses_noncanonical_round",
    ),
    (
        "nanosecond-format",
        "reduce.awk",
        "$6!~/^-?(0|[1-9][0-9]*)$/ || ",
        "",
        "test_reducer_refuses_malformed_nanoseconds",
        "test_reducer_refuses_wrong_field_count",
    ),
    (
        "exit-format",
        "reduce.awk",
        "rc!~/^[0-9]+$/ || ",
        "",
        "test_reducer_refuses_malformed_exit_field",
        "test_failed_variant_invalidates_comparison",
    ),
    (
        "round-range",
        "reduce.awk",
        " || r>rounds",
        "",
        "test_reducer_refuses_undeclared_round",
        "test_reducer_refuses_undeclared_variant",
    ),
    (
        "variant-range",
        "reduce.awk",
        " || idx>=variants",
        "",
        "test_reducer_refuses_undeclared_variant",
        "test_reducer_refuses_undeclared_round",
    ),
    (
        "duplicate-row",
        "reduce.awk",
        "seen[r,idx]++ || ",
        "",
        "test_reducer_refuses_duplicate_row_in_place_of_missing_variant",
        "test_reducer_refuses_undeclared_variant",
    ),
    (
        "leading-zero-elapsed",
        "bench.sh",
        "^-?(0|[1-9][0-9]{0,17})$",
        "^-?[0-9]{1,18}$",
        "test_leading_zero_elapsed_measurement_refused",
        "test_malformed_elapsed_measurement_refused",
    ),
    (
        "exit-length",
        "bench.sh",
        "^[0-9]{1,3}$",
        "^[0-9]+$",
        "test_overlong_exit_measurement_refused",
        "test_malformed_exit_measurement_refused",
    ),
]


def run(script, tests):
    return subprocess.run(
        [sys.executable, str(HERE / "test_bench.py"), "-v", *["BenchTest." + t for t in tests]],
        env=dict(os.environ, BENCH_SCRIPT=str(script)),
        text=True,
        capture_output=True,
        timeout=30,
    )


def main():
    out = HERE.parents[1] / ".protogen/perf/mutations"
    out.mkdir(parents=True, exist_ok=True)
    report = {
        "scope": "Units, command failure, input validation, paired reduction, exact millisecond "
        "summaries, retained output and observations",
        "hashes": {
            n: hashlib.sha256((HERE / n).read_bytes()).hexdigest()
            for n in ["bench.sh", "reduce.awk", "measure.py", "test_bench.py", "test_mutations.py"]
        },
        "faults": [],
    }
    baseline = run(HERE / "bench.sh", [])
    (out / "baseline.log").write_text(baseline.stdout + baseline.stderr)
    if baseline.returncode:
        raise SystemExit("baseline failed")
    for name, file, old, new, assertion, control in FAULTS:
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            for n in ["bench.sh", "reduce.awk", "measure.py"]:
                shutil.copy2(HERE / n, dest / n)
            source = (dest / file).read_text()
            if source.count(old) != 1:
                raise SystemExit("mutation anchor mismatch: " + name)
            mutated = source.replace(old, new)
            (dest / file).write_text(mutated)
            # An unlanded fault runs the unmutated copy and would read as a survivor.
            if old in mutated or (dest / file).read_text() != mutated:
                raise SystemExit("mutation did not land: " + name)
            result = run(dest / "bench.sh", [assertion, control])
            log = result.stdout + result.stderr
            (out / (name + ".log")).write_text(log)
            killed = (
                result.returncode == 1
                and "Ran 2 tests" in log
                and "FAILED (failures=" in log
                and "errors=" not in log
                and f"FAIL: {assertion}" in log
                and re.search(
                    rf"{control} \(__main__\.BenchTest(?:\.{control})?\) \.\.\. ok$",
                    log,
                    re.MULTILINE,
                )
                is not None
            )
            report["faults"].append(
                {
                    "name": name,
                    "file": file,
                    "old": old,
                    "new": new,
                    "assertion": assertion,
                    "control": control,
                    "outcome": "killed" if killed else "unresolved",
                    "log_sha256": hashlib.sha256(log.encode()).hexdigest(),
                }
            )
    final = run(HERE / "bench.sh", [])
    (out / "final.log").write_text(final.stdout + final.stderr)
    report["final_baseline_pass"] = final.returncode == 0
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if final.returncode or any(x["outcome"] != "killed" for x in report["faults"]):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
