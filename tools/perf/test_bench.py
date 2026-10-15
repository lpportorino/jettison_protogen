#!/usr/bin/env python3
"""Deterministic subprocess contracts: fake clock, real runner and shell commands."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(os.environ.get("BENCH_SCRIPT", Path(__file__).with_name("bench.sh"))).resolve()


class BenchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        script = self.root / "tools/perf/bench.sh"
        script.parent.mkdir(parents=True)
        for name in ["bench.sh", "reduce.awk", "measure.py"]:
            shutil.copy2(SCRIPT.with_name(name), script.with_name(name))
        self.script = script
        self.bin = self.root / "bin"
        self.bin.mkdir()
        date = self.bin / "date"
        date.write_text("#!/bin/sh\necho 20000101T000000Z\n")
        site = self.bin / "sitecustomize.py"
        site.write_text("""import os
from pathlib import Path
import time
def clock():
 p=Path(os.environ['CLOCK_FILE']); lines=p.read_text().splitlines()
 value=int(lines[0]); p.write_text('\\n'.join(lines[1:])+'\\n'); return value
time.monotonic_ns=clock
""")
        date.chmod(0o755)

    def run_bench(self, durations, args):
        clock = self.root / "clock"
        now = 1_000_000_000
        stamps = []
        for ms in durations:
            stamps += [str(now), str(now + int(ms * 1_000_000))]
            now += int(abs(ms) * 1_000_000) + 1_000_000
        clock.write_text("\n".join(stamps) + "\n")
        env = dict(
            os.environ,
            PATH=str(self.bin) + ":" + os.environ["PATH"],
            CLOCK_FILE=str(clock),
            PYTHONPATH=str(self.bin),
        )
        proc = subprocess.run(
            ["bash", str(self.script), *args],
            cwd=self.root,
            env=env,
            text=True,
            capture_output=True,
            timeout=15,
        )
        logs = [
            Path(line[len("bench: log -> ") :])
            for line in proc.stdout.splitlines()
            if line.startswith("bench: log -> ")
        ]
        summaries = [p.with_suffix(".summary.md") for p in logs]
        return (
            proc,
            logs[-1].read_text() if logs else "",
            summaries[-1].read_text() if summaries and summaries[-1].exists() else "",
        )

    def run_reducer(self, raw, rounds, variants):
        return subprocess.run(
            [
                "awk",
                "-F\t",
                "-v",
                f"rounds={rounds}",
                "-v",
                f"variants={variants}",
                "-f",
                str(self.script.with_name("reduce.awk")),
            ],
            input=raw,
            text=True,
            capture_output=True,
            timeout=5,
        )

    def assert_reducer_refuses(self, raw, rounds, variants):
        p = self.run_reducer(raw, rounds, variants)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("INVALID", p.stdout)
        self.assertNotIn("median_share_pct", p.stdout)

    def assert_refused_before_writing(self, args, message):
        p, _, _ = self.run_bench([10], args)
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn(message, p.stderr)
        self.assertFalse((self.root / ".protogen").exists())

    def test_units_and_positive_control(self):
        p, raw, summary = self.run_bench([1250], ["-n", "1", "--", "true"])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("round\tvariant_idx\tvariant\telapsed_ms\texit", raw)
        self.assertIn("1\t0\ttrue\t1250\t0", raw)
        self.assertIn("1250", summary)

    def test_failed_variant_invalidates_comparison(self):
        p, raw, summary = self.run_bench([10, 20], ["-n", "1", "--", "exit 7", "true"])
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("1\t0\texit 7\t10\t7", raw)
        self.assertIn("1\t1\ttrue\t20\t0", raw)
        self.assertIn("INVALID", summary)
        self.assertNotIn("median_share_pct", summary)

    def test_positive_rounds_required(self):
        for n in ["0", "-1", "abc", "1.5", ""]:
            with self.subTest(n=n):
                p, _, _ = self.run_bench([], ["-n", n, "--", "true"])
                self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
                self.assertIn("positive integer", p.stderr)

    def test_option_values_required(self):
        for opt in ["-n", "-l"]:
            with self.subTest(opt=opt):
                p, _, _ = self.run_bench([], [opt])
                self.assertEqual(p.returncode, 2)
                self.assertIn("requires a value", p.stderr)

    def test_per_round_reduction(self):
        # A/B = 1/9, 20/20, 90/10: median A share is 50%, while share
        # of medians is 20/(20+10)=66.7%. Median paired B/A ratio is 1.
        p, _, summary = self.run_bench([1, 9, 20, 20, 90, 10], ["-n", "3", "--", "true", ":"])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("median_share_pct", summary)
        self.assertIn("| 0 | 50.000 | 1.000 | 20 | 1 | 90 | 3 |", summary)
        self.assertIn("| 1 | 50.000 | 1.000 | 10 | 9 | 20 | 3 |", summary)

    def test_ratio_direction_and_nanosecond_retention(self):
        p, raw, summary = self.run_bench([10, 30, 20, 100, 40, 280], ["-n", "3", "--", "true", ":"])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("| 1 | 83.333 | 5.000 | 100 | 30 | 280 | 3 |", summary)
        self.assertIn("1\t0\ttrue\t10\t0\t10000000", raw)
        self.assertIn("# clock\t", raw)
        self.assertIn("monotonic=True", raw)

    def test_fractional_ms_ratios_use_raw_ns(self):
        p, raw, summary = self.run_bench([1.01, 1.99], ["-n", "1", "--", "true", ":"])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("1\t0\ttrue\t1\t0\t1010000", raw)
        self.assertIn("| 0 | 33.667 | 1.000 | 1.01 | 1.01 | 1.01 | 1 |", summary)
        self.assertIn("| 1 | 66.333 | 1.970 | 1.99 | 1.99 | 1.99 | 1 |", summary)

    def test_signal_and_cwd_are_preserved(self):
        marker = self.root / "a quoted file"
        marker.write_text("retained")
        p, raw, summary = self.run_bench(
            [10, 20], ["-n", "1", "--", "kill -TERM $$", 'cat "a quoted file"']
        )
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("1\t0\tkill -TERM $$\t10\t143", raw)
        self.assertIn("INVALID", summary)
        outputs = list((self.root / ".protogen/perf").glob("*.runs/round-1-v1.log"))
        self.assertEqual(outputs[0].read_text(), "retained")

    def test_large_ns_consistency_is_decimal(self):
        reducer = self.script.with_name("reduce.awk")
        raw = "1\t0\ttrue\t999999999999\t0\t999999999999999999\n"
        p = subprocess.run(
            ["awk", "-F\t", "-v", "rounds=1", "-v", "variants=1", "-f", str(reducer)],
            input=raw,
            text=True,
            capture_output=True,
            timeout=5,
        )
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("median_share_pct", p.stdout)

    def test_even_median(self):
        p, _, summary = self.run_bench([10, 30, 30, 10], ["-n", "2", "--", "true", ":"])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("| 0 | 50.000 | 1.000 | 20 | 10 | 30 | 2 |", summary)
        self.assertIn("| 1 | 50.000 | 1.667 | 20 | 10 | 30 | 2 |", summary)

    def test_nonpositive_clock_invalid(self):
        for elapsed in [0, -1]:
            with self.subTest(elapsed=elapsed):
                p, raw, summary = self.run_bench([elapsed], ["-n", "1", "--", "true"])
                self.assertNotEqual(p.returncode, 0)
                self.assertIn("INVALID", summary)
                self.assertIn(f"1\t0\ttrue\t{elapsed}\t0", raw)

    def test_tsv_unsafe_command_refused(self):
        p, _, _ = self.run_bench([], ["-n", "1", "--", "true\nfalse"])
        self.assertEqual(p.returncode, 2)
        self.assertIn("tabs or newlines", p.stderr)

    def test_command_output_preserved(self):
        p, _, _ = self.run_bench([10], ["-n", "1", "--", "echo diagnostic >&2; exit 7"])
        self.assertNotEqual(p.returncode, 0)
        logs = list((self.root / ".protogen/perf").glob("*.runs/*.log"))
        self.assertEqual(len(logs), 1)
        self.assertEqual(logs[0].read_text(), "diagnostic\n")

    def test_reducer_refuses_missing_duplicate_and_malformed_rows(self):
        reducer = self.script.with_name("reduce.awk")
        valid = "1\t0\ttrue\t10\t0\t10000000\n1\t1\t:\t20\t0\t20000000\n"
        cases = [
            "",
            valid.splitlines()[0] + "\n",
            valid + valid,
            valid.replace("\t20\t", "\tbad\t"),
            valid.replace("1\t1\t", "1\t2\t"),
            valid.replace("1\t0\t", "01\t0\t"),
            valid.replace("1\t0\t", "1\t00\t"),
        ]
        for raw in cases:
            with self.subTest(raw=raw):
                p = subprocess.run(
                    ["awk", "-F\t", "-v", "rounds=1", "-v", "variants=2", "-f", str(reducer)],
                    input=raw,
                    text=True,
                    capture_output=True,
                    timeout=5,
                )
                self.assertEqual(p.returncode, 1)
                self.assertIn("INVALID", p.stdout)
                self.assertNotIn("median_share_pct", p.stdout)
        control = subprocess.run(
            ["awk", "-F\t", "-v", "rounds=1", "-v", "variants=2", "-f", str(reducer)],
            input=valid,
            text=True,
            capture_output=True,
            timeout=5,
        )
        self.assertEqual(control.returncode, 0, control.stderr)
        self.assertIn("median_share_pct", control.stdout)

    def test_large_ms_summary_keeps_fraction(self):
        # 1234567.891 ms; six significant digits would print 1.23457e+06.
        p = self.run_reducer("1\t0\ttrue\t1234567\t0\t1234567891000\n", 1, 1)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn(
            "| 0 | 100.000 | 1.000 | 1234567.891 | 1234567.891 | 1234567.891 | 1 |", p.stdout
        )

    def test_ms_summary_exact_at_tsv_maximum(self):
        # Eighteen nanosecond digits exceed a double's exact integer range.
        p = self.run_reducer("1\t0\ttrue\t999999999999\t0\t999999999999999999\n", 1, 1)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn(
            "| 999999999999.999999 | 999999999999.999999 | 999999999999.999999 | 1 |", p.stdout
        )

    def test_even_median_keeps_half_nanosecond(self):
        raw = "1\t0\ttrue\t1\t0\t1000001\n2\t0\ttrue\t1\t0\t1000002\n"
        p = self.run_reducer(raw, 2, 1)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("| 0 | 100.000 | 1.000 | 1.0000015 | 1.000001 | 1.000002 | 2 |", p.stdout)

    def test_reducer_refuses_inconsistent_ms(self):
        # elapsed_ms must be elapsed_ns truncated: 11 ms disagrees with 10000000 ns.
        self.assert_reducer_refuses("1\t0\ttrue\t11\t0\t10000000\n", 1, 1)

    def test_sub_millisecond_interval_reported_exactly(self):
        # A positive interval that truncates to 0 in elapsed_ms is still an observation.
        p, raw, summary = self.run_bench([0.5], ["-n", "1", "--", "true"])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("1\t0\ttrue\t0\t0\t500000", raw)
        self.assertIn("| 0 | 100.000 | 1.000 | 0.5 | 0.5 | 0.5 | 1 |", summary)

    def test_reducer_refuses_renamed_variant(self):
        raw = "1\t0\ttrue\t10\t0\t10000000\n2\t0\tfalse\t10\t0\t10000000\n"
        self.assert_reducer_refuses(raw, 2, 1)

    def test_reducer_compares_variant_names_as_text(self):
        # Numerically equal but textually different commands are different variants.
        raw = "1\t0\t10\t10\t0\t10000000\n2\t0\t1e1\t10\t0\t10000000\n"
        self.assert_reducer_refuses(raw, 2, 1)

    def test_label_refused(self):
        for label in ["", "../escape", ".hidden", "-x", "a/b", "a b"]:
            with self.subTest(label=label):
                self.assert_refused_before_writing(
                    ["-n", "1", "-l", label, "--", "true"], "label must contain only"
                )

    def test_rounds_capped_at_six_digits(self):
        self.assert_refused_before_writing(["-n", "1000000", "--", "true"], "at most 999999")

    def test_tab_command_refused(self):
        self.assert_refused_before_writing(["-n", "1", "--", "true\tfalse"], "tabs or newlines")

    def test_carriage_return_command_refused(self):
        self.assert_refused_before_writing(["-n", "1", "--", "true\r"], "tabs or newlines")

    def test_empty_command_refused(self):
        self.assert_refused_before_writing(["-n", "1", "--", ""], "must be nonempty")

    def stub_measure(self, body):
        self.script.with_name("measure.py").write_text(body)

    def test_unknown_option_refused(self):
        self.assert_refused_before_writing(["-x", "--", "true"], "unknown option -x")

    def test_no_variant_refused(self):
        self.assert_refused_before_writing(["-n", "1", "--"], "need at least one variant")

    def test_measurement_helper_failure_writes_invalid_summary(self):
        self.stub_measure("raise SystemExit(3)\n")
        p, raw, summary = self.run_bench([], ["-n", "1", "--", "true"])
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("INVALID: measurement helper failed", summary)
        self.assertIn("round\tvariant_idx", raw)

    def test_malformed_elapsed_measurement_refused(self):
        self.stub_measure('print("abc\\t0")\n')
        p, _, summary = self.run_bench([], ["-n", "1", "--", "true"])
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("INVALID: malformed measurement", summary)

    def test_overlong_elapsed_measurement_refused(self):
        # Nineteen digits can exceed the shell's 64-bit arithmetic.
        self.stub_measure('print("1234567890123456789\\t0")\n')
        p, _, summary = self.run_bench([], ["-n", "1", "--", "true"])
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("INVALID: malformed measurement", summary)

    def test_malformed_exit_measurement_refused(self):
        self.stub_measure('print("1000000\\tx")\n')
        p, _, summary = self.run_bench([], ["-n", "1", "--", "true"])
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("INVALID: malformed measurement", summary)

    def test_leading_zero_elapsed_measurement_refused(self):
        # Shell arithmetic reads a leading zero as octal: 0089 aborts, 0100000000 reads as 16 ms.
        self.stub_measure('print("0089\\t0")\n')
        p, _, summary = self.run_bench([], ["-n", "1", "--", "true"])
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("INVALID: malformed measurement", summary)

    def test_overlong_exit_measurement_refused(self):
        # An exit status has at most three digits; a longer field is a malformed measurement,
        # not a failed command, so it must be refused before the reducer sees it.
        self.stub_measure('print("1000000\\t1234")\n')
        p, _, summary = self.run_bench([], ["-n", "1", "--", "true"])
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("INVALID: malformed measurement", summary)

    def test_reducer_refuses_malformed_round_count(self):
        p = self.run_reducer("1\t0\ttrue\t10\t0\t10000000\n", "01", 1)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("INVALID", p.stdout)

    def test_reducer_refuses_malformed_variant_count(self):
        p = self.run_reducer("1\t0\ttrue\t10\t0\t10000000\n", 1, "01")
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("INVALID", p.stdout)

    def test_reducer_refuses_wrong_field_count(self):
        self.assert_reducer_refuses("1\t0\ttrue\t10\t0\t10000000\textra\n", 1, 1)

    def test_reducer_refuses_malformed_nanoseconds(self):
        # An exponent form whose millisecond truncation still reads as 0.
        self.assert_reducer_refuses("1\t0\ttrue\t0\t0\t1e7\n", 1, 1)

    def test_reducer_refuses_malformed_exit_field(self):
        # Numerically zero, but not the decimal exit status bench.sh writes.
        self.assert_reducer_refuses("1\t0\ttrue\t10\t0.0\t10000000\n", 1, 1)

    def test_reducer_refuses_undeclared_round(self):
        self.assert_reducer_refuses("2\t0\ttrue\t10\t0\t10000000\n", 1, 1)

    def test_reducer_refuses_undeclared_variant(self):
        self.assert_reducer_refuses("1\t1\ttrue\t10\t0\t10000000\n", 1, 1)

    def test_reducer_refuses_duplicate_row_in_place_of_missing_variant(self):
        # Two copies of one observation fill the expected count, so only the
        # duplicate check can refuse; without it the missing variant is invented.
        row = "1\t0\ttrue\t10\t0\t10000000\n"
        self.assert_reducer_refuses(row + row, 1, 2)

    def test_reducer_refuses_noncanonical_round(self):
        self.assert_reducer_refuses("01\t0\ttrue\t10\t0\t10000000\n", 1, 1)

    def test_reducer_refuses_noncanonical_variant(self):
        self.assert_reducer_refuses("1\t00\ttrue\t10\t0\t10000000\n", 1, 1)

    def test_repeated_label_preserves_raw_files(self):
        for _ in range(2):
            p, _, _ = self.run_bench([10], ["-n", "1", "-l", "same", "--", "true"])
            self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(len(list((self.root / ".protogen/perf").glob("*.tsv"))), 2)


if __name__ == "__main__":
    unittest.main()
