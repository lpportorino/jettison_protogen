"""The deck selftest must reject every warning/error, including deck-wide findings."""

import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

VERDICT = Path(__file__).resolve().parents[1] / "fixtures" / "selftest_verdict.py"


def report():
    """A report with no findings; each negative test changes one field."""
    return {
        "totals": {
            "slide_errors": 0,
            "slide_warnings": 0,
            "deck_errors": 0,
            "deck_warnings": 0,
            "by_detector": {},
        },
        "slides": [],
        "deck_findings": [],
    }


class SelftestVerdictTest(unittest.TestCase):
    def run_verdict(self, good, bad=None):
        """Run the real entry point on isolated report files, returning its verdict."""
        if bad is None:
            bad = report()
            bad["totals"]["by_detector"] = {f"d{i}-planted/error": 1 for i in range(1, 13)}
        with tempfile.TemporaryDirectory() as tmp:
            paths = [Path(tmp) / "bad.json", Path(tmp) / "good.json"]
            for path, contents in zip(paths, [bad, good], strict=True):
                path.write_text(json.dumps(contents), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(VERDICT), *map(str, paths)],
                capture_output=True,
                text=True,
                check=False,
            )

    def assert_rejected(self, good, message):
        """An attributed failure, not a crashed harness, is the negative result."""
        result = self.run_verdict(good)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn(message, result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_clean_control_and_info_only_control(self):
        good = report()
        for with_info in (False, True):
            with self.subTest(with_info=with_info):
                if with_info:
                    good["totals"]["by_detector"] = {"d10-external/info": 1}
                    good["deck_findings"] = [{"detector": "d10-external", "severity": "info"}]
                result = self.run_verdict(good)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("PASS", result.stdout)

    def test_each_summary_count_is_enforced(self):
        for key in ("slide_errors", "slide_warnings", "deck_errors", "deck_warnings"):
            with self.subTest(key=key):
                good = report()
                good["totals"][key] = 1
                self.assert_rejected(good, f"fixture_good has {key}=1")

    def test_detector_warning_is_rejected_even_if_totals_are_zero(self):
        for severity in ("warn", "error"):
            with self.subTest(severity=severity):
                good = report()
                good["totals"]["by_detector"] = {f"d6-font-fallback/{severity}": 1}
                self.assert_rejected(good, f"d6-font-fallback/{severity}")

    def test_recorded_findings_are_enforced_independently_of_aggregates(self):
        for scope in ("deck", "slide"):
            for severity in ("warn", "error"):
                with self.subTest(scope=scope, severity=severity):
                    good = report()
                    finding = {"detector": "d6-font-fallback", "severity": severity}
                    if scope == "deck":
                        good["deck_findings"] = [finding]
                    else:
                        good["slides"] = [{"n": 1, "findings": [finding]}]
                    self.assert_rejected(good, f"{scope} finding d6-font-fallback/{severity}")

    def test_missing_bad_detector_remains_a_failure(self):
        bad = copy.deepcopy(report())
        bad["totals"]["by_detector"] = {f"d{i}-planted/error": 1 for i in range(2, 13)}
        result = self.run_verdict(report(), bad)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("d1 did not fire on fixture_bad", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_nonpositive_bad_count_does_not_fire(self):
        for count in (0, -1):
            with self.subTest(count=count):
                bad = report()
                bad["totals"]["by_detector"] = {f"d{i}-planted/error": 1 for i in range(1, 13)}
                bad["totals"]["by_detector"]["d1-planted/error"] = count
                result = self.run_verdict(report(), bad)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn("d1 did not fire on fixture_bad", result.stdout)
                self.assertNotIn("Traceback", result.stderr)
