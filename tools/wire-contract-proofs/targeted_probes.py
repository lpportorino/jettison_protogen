#!/usr/bin/env python3
"""Targeted vacuous-green probes against tools/wire_contract_check.py.

The line-deletion sweep (vacuum_sweep.py) is a weak perturbation class: it
cannot express "the doc was reformatted", "a whole block moved", or the two
SKIP PATHS visible by reading the checker's source:

  SKIP-1  check_repeated_blocks() `continue`s past any labelled G1 block that
          matches SPEC, on the grounds that check_golden_specs already asserted
          it. But check_golden_specs only scans §9. A §6 inline copy rewritten
          into SPEC form is therefore asserted by NEITHER — while the
          `found(labelled, 4)` floor still counts it.
  SKIP-2  the `found(specs, 2)` floor is a COUNT. Two copies of ONE vector
          satisfy it, so deleting a distinct vector and duplicating another is
          the classic way past a `>= N` floor.

Each probe states what it is trying to make the gate do. A probe that FAILS to
produce a vacuum is a positive result about the gate, and is reported as such.

Nothing here mutates a tracked file: the baseline text comes from `git show`
and every variant is written to a scratch temp file passed via --doc.
"""
from __future__ import annotations

import json
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
SCRATCH = _SCRATCH
SUMMARY = re.compile(
    r"wire-contract check: (GREEN — (\d+) assertions held|RED — (\d+) failed, (\d+) passed)")


def git_show(rev_path: str) -> str:
    return subprocess.run(["git", "-C", str(ROOT), "show", rev_path],
                          capture_output=True, text=True, check=True).stdout


def run(doc_text: str, descriptors: str | None = None) -> tuple[int, int, int, str]:
    args = [sys.executable, str(CHECKER), "--quiet"]
    files = []
    fh = tempfile.NamedTemporaryFile("w", suffix=".md", dir=SCRATCH, delete=False)
    fh.write(doc_text)
    fh.close()
    files.append(Path(fh.name))
    args += ["--doc", fh.name]
    if descriptors is not None:
        dh = tempfile.NamedTemporaryFile("w", suffix=".json", dir=SCRATCH, delete=False)
        dh.write(descriptors)
        dh.close()
        files.append(Path(dh.name))
        args += ["--descriptors", dh.name]
    try:
        p = subprocess.run(args, capture_output=True, text=True)
    finally:
        for f in files:
            f.unlink(missing_ok=True)
    m = SUMMARY.search(p.stdout)
    if not m:
        tail = (p.stderr or p.stdout).strip().splitlines()
        return p.returncode, -1, -1, (tail[-1] if tail else "<no output>")
    if m.group(2) is not None:
        return p.returncode, int(m.group(2)), 0, "GREEN"
    return p.returncode, int(m.group(4)), int(m.group(3), ), "RED"


DOC = git_show("HEAD:docs/INTERFACE-CONTRACTS.md")
BASE_RC, BASE_PASS, _, _ = run(DOC)

RESULTS: list[tuple[str, str, int, int]] = []


def probe(name: str, doc: str, descriptors: str | None = None) -> None:
    rc, npass, nfail, note = run(doc, descriptors)
    if rc == 0 and npass == 0:
        verdict = "*** TOTAL VACUUM ***"
    elif rc == 0 and npass < BASE_PASS:
        verdict = f"*** PARTIAL VACUUM ({BASE_PASS} -> {npass}) ***"
    elif rc == 0:
        verdict = "green, full coverage (no vacuum)"
    elif npass < 0:
        verdict = f"refused hard (exit {rc}): {note}"
    else:
        verdict = f"RED ({nfail} failed, {npass} passed)"
    RESULTS.append((name, verdict, rc, npass))
    print(f"{name:<58} {verdict}")


# --- degenerate documents -------------------------------------------------
probe("empty doc", "")
probe("headings only", "".join(f"## {n}. x\n\n" for n in (5, 6, 8, 9)))
probe("doc with every fenced block emptied",
      re.sub(r"^```[^\n]*\n.*?^```", "```\n```", DOC, flags=re.S | re.M))
probe("doc with every table row stripped",
      "\n".join(l for l in DOC.splitlines() if not l.lstrip().startswith("|")))

# --- whole-section deletion ----------------------------------------------
marks = list(re.finditer(r"^## (\d+)\. .*$", DOC, re.M))
for i, m in enumerate(marks):
    if m.group(1) not in ("5", "6", "8", "9"):
        continue
    end = marks[i + 1].start() if i + 1 < len(marks) else len(DOC)
    probe(f"delete whole section §{m.group(1)}", DOC[:m.start()] + DOC[end:])

# --- one fenced block at a time ------------------------------------------
blocks = list(re.finditer(r"^```[^\n]*\n.*?^```", DOC, re.S | re.M))
greens = []
for i, b in enumerate(blocks):
    rc, npass, _, _ = run(DOC[:b.start()] + DOC[b.end():])
    if rc == 0:
        greens.append((i, npass, b.group(0).splitlines()[1][:52]))
print(f"\ndelete one fenced block at a time ({len(blocks)} blocks): "
      f"{len(blocks) - len(greens)} RED, {len(greens)} green")
for i, npass, head in greens:
    flag = "  <-- PARTIAL VACUUM" if npass < BASE_PASS else ""
    print(f"    block {i:>2} stayed green at {npass} assertions: {head!r}{flag}")

# --- structural reformats the docstring claims to catch -------------------
probe("§9 heading loses its dot (`## 9 Golden`)",
      DOC.replace("## 9. Golden vectors", "## 9 Golden vectors"))
probe("all fences ``` -> ~~~", DOC.replace("```", "~~~"))
probe("table separator rows reformatted (|---| -> |:--|)",
      re.sub(r"^\s*\|[\s:|-]+\|\s*$",
             lambda mm: mm.group(0).replace("-", ":"), DOC, flags=re.M))
probe("every fenced block indented by two spaces",
      re.sub(r"^```", "  ```", DOC, flags=re.M))

# --- SKIP-1: rewrite a §6 inline G1 copy into SPEC form -------------------
# check_repeated_blocks() `continue`s on SPEC.match, trusting check_golden_specs
# to have asserted it -- but that function only ever reads §9.
s6_start = DOC.find("## 6. ")
s9_start = DOC.find("## 9. ")
inline = re.search(r"\*\*G1 —.*?```\n(08 01 28 02 50 03 e2 01 00[^\n]*)\n```",
                   DOC[s6_start:s9_start], re.S)
if inline:
    abs_lo = s6_start + inline.start(1)
    abs_hi = s6_start + inline.end(1)
    spec_form = ("cmd.Root{protocol_version=1, client_type=2, client_app=3, ping={}}\n"
                 "= de ad be ef")
    probe("SKIP-1: §6 inline G1 copy rewritten into SPEC form",
          DOC[:abs_lo] + spec_form + DOC[abs_hi:])
else:
    print("SKIP-1 probe: could not locate the §6 inline G1 copy — probe not run")

# --- SKIP-2: satisfy the `>= 2 specs` floor with two copies of ONE vector --
g1b = re.search(r"cmd\.Root\{protocol_version=1, client_type=2, client_app=1, ping=\{\}\}\n"
                r"= [0-9a-f ]+\s*\(\d+ bytes?\)", DOC)
if g1b:
    dup = ("cmd.Root{protocol_version=1, client_type=2, client_app=3, ping={}}\n"
           "= 08 01 28 02 50 03 e2 01 00          (9 bytes)")
    probe("SKIP-2: G1-B spec replaced by a duplicate of G1",
          DOC[:g1b.start()] + dup + DOC[g1b.end():])
else:
    print("SKIP-2 probe: could not locate the G1-B spec block — probe not run")

# --- descriptor-side degeneracy ------------------------------------------
probe("descriptor set = {\"file\": []}", DOC, json.dumps({"file": []}))
probe("descriptor set = {}", DOC, json.dumps({}))

print(f"\nbaseline: exit={BASE_RC} passed={BASE_PASS}")
vacua = [r for r in RESULTS if "VACUUM" in r[1]]
print(f"probes run: {len(RESULTS)}   vacua found: {len(vacua)}")
for name, verdict, _, _ in vacua:
    print(f"  {name}: {verdict}")
sys.exit(1 if vacua else 0)
