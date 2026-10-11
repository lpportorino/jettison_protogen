#!/usr/bin/env python3
"""selftest_pdf_verdict - the both-directions proof over deck_pdf's paged fixtures.

  python3 selftest_pdf_verdict.py <bad/pdf_report.json> <good/pdf_report.json>

PASS only when the BAD page reports its print-hidden anchor as a dead PDF link
AND its href to a nonexistent id as unresolved in the DOM (Chromium prints no
annotation for that one, so the PDF audit alone is blind to it), and the GOOD page (the same
mark, its anchor printed on page 2) reports at least one named link, at least
one defined destination and zero dead. The same two pages prove the overflow
check: the BAD page's unbreakable line is named, the GOOD page reports none,
and the printable width is the fixture's own @page (210 - 2 x 18 = 174 mm).
Both pages also carry an inline svg whose one text line is far wider than the
page in viewBox units yet fits once scaled; the GOOD page reporting nothing is
the proof that svg content is exempt (the svg root clips it). An audit that never goes red is not an
audit; one that goes red on a resolving link is a noise generator. Exit 0 on
PASS, 1 on FAIL, with the reasons.
"""

import json
import sys


def main():
    bad_rep, good_rep = json.load(open(sys.argv[1])), json.load(open(sys.argv[2]))
    bad, good = bad_rep["links"], good_rep["links"]
    reasons = []
    bo, go = bad_rep.get("overflow") or {}, good_rep.get("overflow") or {}
    if not any(o["what"] == "div.toowide" for o in bo.get("offenders", [])):
        reasons.append(
            "paged_bad: the over-wide element was not reported (offenders={})".format(
                bo.get("offenders")
            )
        )
    if go.get("offenders"):
        reasons.append(
            "paged_good: an element was reported over-wide on a page that fits: {}".format(
                go["offenders"]
            )
        )
    if not bo.get("page_rule") or abs(bo.get("width_mm", 0) - 174.0) > 0.01:
        reasons.append(f"paged_bad: the printable width was not derived from @page (got {bo})")
    if not bad["dead_links"]:
        reasons.append("paged_bad: the audit saw no dead link (dead_links=0)")
    if "nowhere" not in bad["html_unresolved"]:
        reasons.append(
            "paged_bad: the href to a missing id was not reported unresolved "
            "(Chromium prints no annotation for it, so only the DOM check can see it)"
        )
    if good["html_unresolved"]:
        reasons.append(
            "paged_good: an href that resolves was reported unresolved: {}".format(
                good["html_unresolved"]
            )
        )
    if "src-S1" not in bad["dead"]:
        reasons.append("paged_bad: the link to a print-hidden anchor was not reported dead")
    if good["named"] < 1:
        reasons.append(f"paged_good: no named link was printed at all (named={int(good['named'])})")
    if good["defined"] < 1:
        reasons.append(f"paged_good: no destination was defined (defined={int(good['defined'])})")
    if good["dead_links"]:
        reasons.append("paged_good: a resolving link was reported dead: {}".format(good["dead"]))
    for name, r in (("paged_bad", bad), ("paged_good", good)):
        if r["parsed_objects"] == 0:
            reasons.append(f"{name}: the PDF parsed to zero objects -- the audit read nothing")
    print("paged_bad  links:", json.dumps(bad, sort_keys=True))
    print("paged_good links:", json.dumps(good, sort_keys=True))
    if reasons:
        print("=== deck_pdf paged selftest: FAIL ===")
        for r in reasons:
            print("  -", r)
        return 1
    print(
        "=== deck_pdf paged selftest: PASS (the bad page's dead links and over-wide line are red; the good page resolves and fits, its svg line exempt) ==="
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
