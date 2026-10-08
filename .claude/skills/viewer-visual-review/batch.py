#!/usr/bin/env python3
"""Resolve a viewer visual-review batch: the captures that CHANGED, with priors.

usage: batch.py CURRENT_RUN PRIOR_RUN OUT_PREFIX [--parts N] [--vanished NAME]...

CURRENT_RUN and PRIOR_RUN are evidence directories written by
tools/gate-graph/browser/run.sh. For each engine, every top-level capture in
CURRENT_RUN is compared with the same-named capture in PRIOR_RUN:

  identical          same bytes AND same review brief -> inherits the prior review
  pixel-equivalent   Chromium only, max channel delta <= 2, same brief -> raster
                     jitter, skipped and reported, never "identical"
  changed / new      anything else -- including unchanged pixels whose sidecar
                     `expect` list changed, since new checklist items were never
                     judged -> in the batch
  vanished           in PRIOR_RUN but not CURRENT_RUN -> CANNOT RUN, unless a
                     `--vanished NAME` names that exact capture as removed on
                     purpose; a --vanished naming nothing that vanished is
                     refused too, so a waiver cannot outlive its capture

CURRENT_RUN must be GREEN: each engine's manifest.json must record a completed
run with no failure. A red run stops at its first failure, so every later
capture is simply absent, and batching it would hand reviewers a fraction of
the states while reporting nothing wrong.

Exit 0 batches written (possibly empty); 3 CANNOT RUN (a red or incomplete
run, a vanished capture, a missing or malformed run directory or sidecar, or
Pillow missing when pixels must be compared). Never globs a gallery.
"""

import hashlib
import json
import sys
from pathlib import Path

JITTER = 2  # max channel delta Chromium was measured to vary by between runs
JITTER_ENGINES = {"chromium"}  # measured there only; WebKit captures must be identical


def cannot_run(message):
    print(f"[viewer-visual-review] CANNOT RUN — {message}", file=sys.stderr)
    sys.exit(3)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def max_delta(a, b):
    try:
        from PIL import Image, ImageChops
    except ImportError:
        cannot_run("Pillow is needed to tell raster jitter from change (pip install pillow)")
    left, right = Image.open(a).convert("RGB"), Image.open(b).convert("RGB")
    if left.size != right.size:
        return 255
    extrema = ImageChops.difference(left, right).getextrema()
    return max(high for _low, high in extrema)


def read_json(path, what):
    """A JSON object from `path`, or CANNOT RUN naming WHAT it should have been."""
    try:
        value = json.loads(path.read_text())
    except (OSError, ValueError) as error:
        cannot_run(f"{path} is not a readable JSON {what}: {error}")
    if not isinstance(value, dict):
        cannot_run(f"{path} is not a JSON object; it is not a {what}")
    return value


def read_brief(path):
    """A sidecar as a dict, or CANNOT RUN: a malformed brief is not a review input."""
    return read_json(path, "review brief")


def main(argv):
    import argparse

    parser = argparse.ArgumentParser(prog="batch.py", add_help=True)
    parser.add_argument("current")
    parser.add_argument("prior")
    parser.add_argument("prefix")
    parser.add_argument("--parts", type=int, default=1)
    parser.add_argument("--vanished", action="append", default=[], metavar="NAME")
    try:
        opts = parser.parse_args(argv)
    except SystemExit as exit_:
        if exit_.code == 0:
            raise
        cannot_run(
            "usage: batch.py CURRENT_RUN PRIOR_RUN OUT_PREFIX [--parts N] [--vanished NAME]..."
        )
    if opts.parts < 1:
        cannot_run("--parts must be at least 1")
    current, prior, prefix, parts = Path(opts.current), Path(opts.prior), opts.prefix, opts.parts
    for run in (current, prior):
        if not run.is_dir():
            cannot_run(f"{run} is not an evidence directory")
    waived = set()
    for engine in ("chromium", "webkit"):
        manifest = read_json(current / engine / "manifest.json", "run manifest")
        if manifest.get("completed") is not True or manifest.get("failure"):
            failure = manifest.get("failure") or {}
            cannot_run(
                f"{current / engine} is not a green run ({failure.get('kind', 'incomplete')}: "
                f"{failure.get('message', 'completed is not true')}); review a green run"
            )
        images = sorted(p for p in (current / engine).glob("*.png") if ".tile-" not in p.name)
        if not images:
            cannot_run(f"{current / engine} holds no captures")
        current_names = {p.name for p in images}
        vanished = sorted(
            p.name
            for p in (prior / engine).glob("*.png")
            if ".tile-" not in p.name and p.name not in current_names
        )
        for gone in vanished:
            print(f"  VANISHED (in the prior run, not this one): {gone}")
        unexplained = [name for name in vanished if name not in opts.vanished]
        if unexplained:
            cannot_run(
                f"{len(unexplained)} {engine} capture(s) in the prior run are absent; "
                "pass --vanished NAME for each state removed on purpose"
            )
        waived.update(name for name in vanished if name in opts.vanished)
        rows, identical, equivalent = [], 0, []
        for image in images:
            sidecar = image.with_name(image.name + ".json")
            if not sidecar.exists():
                cannot_run(f"{sidecar} is missing; every capture owes its review brief")
            before = prior / engine / image.name
            brief = read_brief(sidecar)
            if "tiles" not in brief or "expect" not in brief:
                cannot_run(f"{sidecar} lacks tiles/expect; it is not a review brief")
            prior_sidecar = before.with_name(before.name + ".json")
            same_brief = (
                prior_sidecar.exists()
                and read_brief(prior_sidecar).get("expect") == brief["expect"]
            )
            if before.exists() and same_brief and digest(before) == digest(image):
                identical += 1
                continue
            if (
                before.exists()
                and same_brief
                and engine in JITTER_ENGINES
                and max_delta(before, image) <= JITTER
            ):
                equivalent.append(image.name)
                continue
            tiles = [str(image.with_name(t)) for t in brief["tiles"]]
            rows.append(
                {
                    "image": str(image),
                    "prior": str(before) if before.exists() else None,
                    "sidecar": str(sidecar),
                    "tiles": tiles,
                }
            )
        size = max(1, -(-len(rows) // parts))
        chunks = [rows[i : i + size] for i in range(0, len(rows), size)] or [[]]
        for n, chunk in enumerate(chunks):
            suffix = f"-{chr(ord('a') + n)}" if len(chunks) > 1 else ""
            out = Path(f"{prefix}-{engine}{suffix}.json")
            out.write_text(json.dumps(chunk, indent=1) + "\n")
            print(f"{engine}{suffix}: {len(chunk)} to review -> {out}")
        print(
            f"{engine}: {identical} identical, {len(equivalent)} pixel-equivalent (<= {JITTER}/255)"
        )
        for name in equivalent:
            print(f"  pixel-equivalent: {name}")
    stale = sorted(set(opts.vanished) - waived)
    if stale:
        cannot_run(f"--vanished names captures that did not vanish: {', '.join(stale)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
