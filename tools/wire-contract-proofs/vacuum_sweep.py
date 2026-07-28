#!/usr/bin/env python3
"""Hunt for a VACUOUS GREEN in tools/wire_contract_check.py.

The failure shape under test: a gate whose PASS value is indistinguishable from
its NOTHING-RAN value. Concretely, two variants of it:

  TOTAL   an input on which the gate reports GREEN having asserted nothing.
  PARTIAL an input on which the gate reports GREEN having asserted STRICTLY
          FEWER things than the baseline — a silent coverage drop. CI reads the
          exit code, not the count, so a partial vacuum ships as a pass.

Method: perturb the DOC (never the tracked copy — the baseline text comes from
`git show`), run the checker exactly as CI does but with --doc pointed at the
perturbed copy, and classify by (exit code, assertions passed).

Nothing here mutates a tracked file.
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHECKER = ROOT / "tools" / "wire_contract_check.py"
SUMMARY = re.compile(
    r"wire-contract check: (GREEN — (\d+) assertions held|RED — (\d+) failed, (\d+) passed)")


def baseline_doc() -> str:
    return subprocess.run(
        ["git", "-C", str(ROOT), "show", "HEAD:docs/INTERFACE-CONTRACTS.md"],
        capture_output=True, text=True, check=True).stdout


def run(doc_text: str) -> tuple[int, int, int]:
    """(exit code, assertions passed, assertions failed) for one doc variant."""
    with tempfile.NamedTemporaryFile("w", suffix=".md", dir=ROOT / ".fork-scratch",
                                     delete=True) as fh:
        fh.write(doc_text)
        fh.flush()
        p = subprocess.run(
            [sys.executable, str(CHECKER), "--doc", fh.name, "--quiet"],
            capture_output=True, text=True)
    m = SUMMARY.search(p.stdout)
    if not m:
        # No summary line at all => the script died before reporting (traceback
        # or exit 2). That is a red, and a loud one; record it as such.
        return p.returncode, -1, -1
    if m.group(2) is not None:
        return p.returncode, int(m.group(2)), 0
    return p.returncode, int(m.group(4)), int(m.group(3))


def main() -> int:
    doc = baseline_doc()
    lines = doc.splitlines(keepends=True)
    base_rc, base_pass, base_fail = run(doc)
    print(f"BASELINE: exit={base_rc} passed={base_pass} failed={base_fail}")
    if base_rc != 0:
        print("baseline is not green; aborting sweep")
        return 2

    print(f"\nsweeping {len(lines)} single-line deletions ...")
    total_vacuum, partial_vacuum, red = [], [], 0
    for i in range(len(lines)):
        variant = "".join(lines[:i] + lines[i + 1:])
        rc, npass, nfail = run(variant)
        if rc != 0:
            red += 1
            continue
        if npass == 0:
            total_vacuum.append((i + 1, lines[i].rstrip("\n")))
        elif npass < base_pass:
            partial_vacuum.append((i + 1, npass, lines[i].rstrip("\n")))
        # npass == base_pass => the deleted line carried no assertion. Expected
        # for prose; it is not a finding.

    print(f"\n  RED (gate refused the perturbed doc)      : {red}")
    print(f"  GREEN, assertion count unchanged           : "
          f"{len(lines) - red - len(total_vacuum) - len(partial_vacuum)}")
    print(f"  GREEN, TOTAL vacuum (0 assertions)         : {len(total_vacuum)}")
    print(f"  GREEN, PARTIAL vacuum (count dropped)      : {len(partial_vacuum)}")

    if total_vacuum:
        print("\nTOTAL VACUUM — gate reported a pass having asserted nothing:")
        for ln, text in total_vacuum:
            print(f"  doc line {ln}: {text!r}")
    if partial_vacuum:
        print(f"\nPARTIAL VACUUM — GREEN with fewer than {base_pass} assertions:")
        for ln, npass, text in partial_vacuum:
            print(f"  doc line {ln}: {base_pass} -> {npass}  {text!r}")

    return 0 if not (total_vacuum or partial_vacuum) else 1


if __name__ == "__main__":
    sys.exit(main())
