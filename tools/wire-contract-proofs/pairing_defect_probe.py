#!/usr/bin/env python3
"""The §6 label/block pairing defect in tools/wire_contract_check.py, measured.

WHAT IS BROKEN
--------------
check_repeated_blocks() finds every `**G1 —` / `**G1-B —` label, takes the first
fenced block after it, and asserts:

    r.check(f"{m.group(1)} inline copy reproduces a derived vector",
            got in bare, ...)

`bare` is the SET of every derived vector's bytes. The captured label
(`m.group(1)`) is used in the assertion NAME and nowhere in the assertion. So
the clause tests "the block under this label is SOME derived vector", never
"the block under the G1 label is G1". The same holds for the CW-framed copies
against `framed`.

WHY IT MATTERS
--------------
§9's two vectors are re-derived from the descriptor and compared byte-for-byte,
so §9 is genuinely pinned. §6 carries human-readable COPIES of those same bytes,
and those copies are what a reader of §6 encodes against. A §6 copy can be made
to state a falsehood — the native client's ping is the browser's encoding — and
the gate reports green with a byte-identical assertion report.

This probe never touches a tracked file: the baseline comes from `git show` and
every variant is a scratch temp file passed via --doc.

Exit 0 = the defect reproduced (all variants green). Exit 1 = it did not
reproduce, i.e. the checker has since been fixed and this probe is stale.
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

# Repo root is TWO levels up from tools/wire-contract-proofs/. The depth is
# part of the path: move this file and every path below silently retargets,
# and the legs then fail for a reason unrelated to what is under test -- a red
# indistinguishable from a caught defect.
ROOT = Path(__file__).resolve().parent.parent.parent
_SCRATCH = ROOT / ".fork-scratch"
_SCRATCH.mkdir(exist_ok=True)
CHECKER = ROOT / "tools" / "wire_contract_check.py"
G1 = "08 01 28 02 50 03 e2 01 00"
G1B = "08 01 28 02 50 01 e2 01 00"


def git_doc() -> str:
    return subprocess.run(
        ["git", "-C", str(ROOT), "show", "HEAD:docs/INTERFACE-CONTRACTS.md"],
        capture_output=True, text=True, check=True).stdout


def run(text: str) -> tuple[int, list[str], list[str]]:
    fh = tempfile.NamedTemporaryFile("w", suffix=".md",
                                     dir=_SCRATCH, delete=False)
    fh.write(text)
    fh.close()
    try:
        p = subprocess.run([sys.executable, str(CHECKER), "--doc", fh.name],
                           capture_output=True, text=True)
    finally:
        Path(fh.name).unlink(missing_ok=True)
    ok = [l[7:] for l in p.stdout.splitlines() if l.startswith("  ok   ")]
    bad = [l[7:] for l in p.stdout.splitlines() if l.startswith("  FAIL ")]
    return p.returncode, ok, bad


def main() -> int:
    doc = git_doc()
    rc0, ok0, bad0 = run(doc)
    print(f"BASELINE  exit={rc0}  passed={len(ok0)}  failed={len(bad0)}")
    if rc0 != 0:
        print("baseline is not green — cannot attribute anything below")
        return 2

    s6, s9 = doc.find("## 6. "), doc.find("## 9. ")
    if not 0 <= s6 < s9:
        print("could not locate §6/§9 — probe is stale")
        return 2

    blocks = list(re.finditer(r"^```[^\n]*\n.*?^```", doc, re.S | re.M))
    s6_blocks = [b for b in blocks if s6 < b.start() < s9]

    variants: list[tuple[str, str]] = []

    def sub_in_s6(old: str, new: str) -> str | None:
        i = doc.find(old, s6, s9)
        return None if i < 0 else doc[:i] + new + doc[i + len(old):]

    v = sub_in_s6(G1, G1B)
    if v:
        variants.append(("§6 native-client block given the browser-HUD bytes", v))
    v = sub_in_s6(G1B, G1)
    if v:
        variants.append(("§6 browser-HUD block given the native-client bytes", v))
    for b in s6_blocks:
        body = b.group(0)
        if G1 in body and "09 00 00 00" not in body:
            variants.append(("§6 native-client block deleted outright",
                             doc[:b.start()] + doc[b.end():]))
            break

    if not variants:
        print("no variant could be constructed — the doc's shape changed")
        return 2

    reproduced = 0
    for name, text in variants:
        assert text != doc, f"{name}: mutation did not land"
        rc, ok, bad = run(text)
        identical = sorted(ok) == sorted(ok0)
        if rc == 0:
            reproduced += 1
            print(f"  DEFECT REPRODUCED  {name}")
            print(f"                     green at {len(ok)} assertions; "
                  f"assertion set identical to baseline: {identical}")
        else:
            print(f"  gate refused       {name}  (RED, {len(bad)} failed)")
            for d in bad:
                print(f"                     FAIL {d[:110]}")

    # CONTROL: the identical corruption inside §9, where the vectors are derived
    # from the descriptor rather than matched against a set, must go RED and must
    # NAME the vector. Without this the greens above prove nothing about WHERE
    # the coverage stops.
    i = doc.find(G1, s9)
    rc, ok, bad = run(doc[:i] + G1B + doc[i + len(G1):])
    print(f"\n  CONTROL            same corruption inside §9: "
          f"{'RED' if rc else 'GREEN'} ({len(bad)} failed)")
    for d in bad:
        print(f"                     FAIL {d[:110]}")
    control_ok = rc != 0 and any(d.startswith("§9 cmd.Root{") for d in bad)

    print(f"\nvariants green (defect reproduced): {reproduced}/{len(variants)}")
    print(f"control red and naming the §9 vector: {control_ok}")
    return 0 if reproduced == len(variants) and control_ok else 1


if __name__ == "__main__":
    sys.exit(main())
