#!/usr/bin/env python3
"""selftest_verdict - the both-directions proof over the two fixture reports.

  python3 selftest_verdict.py <bad/report.json> <good/report.json>

PASS only when (a) every detector D1..D12 fired at error or warn severity on
fixture_bad, and (b) fixture_good has zero slide errors, zero slide warnings,
zero deck errors, zero deck warnings and no warning/error finding of any detector. A detector that
does not go red on the bad deck is not a detector; one that does not go green on
the good deck is a noise generator. Exit 0 on PASS, 1 on FAIL, with the reasons.
"""

import json
import sys

DETECTORS = [f"d{i}" for i in range(1, 13)]


def main():
    bad = json.load(open(sys.argv[1]))
    good = json.load(open(sys.argv[2]))
    bad_by = bad["totals"]["by_detector"]
    good_by = good["totals"]["by_detector"]
    reasons = []
    for d in DETECTORS:
        fired = [
            k
            for k, count in bad_by.items()
            if count > 0 and k.startswith(d + "-") and k.split("/")[-1] in ("error", "warn")
        ]
        if not fired:
            reasons.append(f"{d} did not fire on fixture_bad")
    gt = good["totals"]
    for k in ("slide_errors", "slide_warnings", "deck_errors", "deck_warnings"):
        if gt.get(k, 0):
            reasons.append(f"fixture_good has {k}={gt[k]}")
    errs = [k for k, count in good_by.items() if count and k.endswith(("/error", "/warn"))]
    if errs:
        reasons.append(
            "fixture_good has warning/error findings: {}".format(", ".join(sorted(errs)))
        )
    records = [("deck", finding) for finding in good.get("deck_findings", [])]
    records.extend(
        ("slide", finding)
        for slide in good.get("slides", [])
        for finding in slide.get("findings", [])
    )
    for scope, finding in records:
        if finding.get("severity") in ("warn", "error"):
            reasons.append(
                f"fixture_good has {scope} finding {finding.get('detector')}/{finding['severity']}"
            )
    print("fixture_bad  by detector:", json.dumps(bad_by, sort_keys=True))
    print("fixture_good by detector:", json.dumps(good_by, sort_keys=True))
    if reasons:
        print("=== press selftest: FAIL ===")
        for r in reasons:
            print("  -", r)
        return 1
    print("=== press selftest: PASS (every detector red on the bad deck, the good deck clean) ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
