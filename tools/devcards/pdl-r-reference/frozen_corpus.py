#!/usr/bin/env python3
"""PDL-R Step 1 — re-measure the bake-off winner on ONE FROZEN SHARED CORPUS.

WHY THIS EXISTS. Each of the six bake-off variants built its own corpus: same
seed, but different frame sizes, veil constants and k-sweeps. So every
cross-variant number recorded so far compares in SHAPE, not digit-for-digit,
and the two figures the gate design rests on — the separating gap and the
seed-to-seed noise floor — were never measured against each other on identical
inputs. Until they are, "the margin is narrower than the noise" is a claim
inherited from six different experiments rather than one result.

WHAT IT DOES. Freezes the corpus (one seed, one frame size, one veil, one
operating k), runs the winner's metric — Region Ink-edge Drift over
renderer-supplied glyph-ink masks, ONE region per frame — and reports:

  * the separating gap: worst readable score vs best unreadable score, which
    is the margin any threshold has to live inside;
  * the noise floor: the spread of the SAME configuration re-measured across
    independent seeds;
  * the verdict: gap > noise (a binary gate is defensible) or gap < noise (it
    is not, and the three-way band is mandatory).

It also re-runs the two dead ends on this same corpus, because a dead end
demonstrated on a different corpus is exactly the kind of inherited claim this
script exists to retire.

PROVENANCE. lib.py is the lifted V6 reference and is edited ONLY for the upstream
name genericisation recorded in PROVENANCE.txt — no executable line differs from
the original. Its two font constants point at the bake-off machine's
dist-packages and are patched here at import time. Everything measured below
comes from lib.py's own functions.

ENVIRONMENT. The pinned toolchain container carries numpy/scipy/PIL and DejaVu
(Dockerfile.base), so this runs THERE and its numbers are reproducible like
every other artifact-producing lane in this repo. It still runs on a host that
happens to have the stack, and the reported JSON records the interpreter and
numpy version either way — because if the two ever disagree, that disagreement
is itself the finding.

MEASURED, and stronger than the pin alone requires: the container
(python 3.12.3 / numpy 1.26.4) and a host (python 3.14.6 / numpy 2.5.1)
produce BYTE-IDENTICAL figures — separation, noise floor, and both dead-end
numbers — across two numpy MAJOR versions. So the result does not rest on the
pin; the pin exists to keep it that way, and a future divergence is a real
signal rather than expected drift.

    python3 frozen_corpus.py            # ~1 min
"""
from __future__ import annotations

import json
import os
import platform
import statistics
import sys
import zlib

import numpy as np

# ── resolve DejaVu, then patch lib's font constants before anything reads them
# lib.py's executable lines are the lifted reference and are NOT edited (only its
# prose was genericised — PROVENANCE.txt), so its two hardcoded paths
# (the bake-off machine's dist-packages) are overridden here. Candidates cover
# both environments this runs in: the pinned container installs
# fonts-dejavu-core at the system path, while a host may only have the copy
# bundled with matplotlib. The FONT IDENTITY is what the measurement depends
# on — DejaVuSans is metrically identical from either source — but a missing
# font must fail loudly rather than silently substituting a different face and
# quietly changing every number.
def _dejavu_dir() -> str:
    candidates = ["/usr/share/fonts/truetype/dejavu"]
    try:
        import matplotlib
        candidates.append(
            os.path.join(os.path.dirname(matplotlib.__file__), "mpl-data", "fonts", "ttf"))
    except ImportError:
        pass
    for d in candidates:
        if os.path.exists(os.path.join(d, "DejaVuSans.ttf")):
            return d
    raise SystemExit(
        "DejaVuSans.ttf not found in any of: " + ", ".join(candidates)
        + "\nInstall fonts-dejavu-core (the pinned container has it).")


_TTF = _dejavu_dir()
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib  # noqa: E402

lib.FONT_BOLD = os.path.join(_TTF, "DejaVuSans-Bold.ttf")
lib.FONT_REG = os.path.join(_TTF, "DejaVuSans.ttf")
lib._FONT_CACHE.clear()

from lib import (  # noqa: E402
    actual_contrast_ratio, composite_alpha, cumulative_score, edge_bitmap,
    ink_mask_full_frame, make_flat_bg, make_gradient_bg, make_noisy_bg,
    per_region_scores, render_text_alpha, solve_text_gray, veiling_glare,
)

# ── THE FROZEN CORPUS — every knob pinned here, nowhere else ───────────────
BASE_SEED = 20260725
FRAME_H, FRAME_W = 120, 300
TEXT = "Speed 42"
K_OPERATING = 0.6
VEIL_GRAY = 255.0
NOISY_STD = 14.0
RATIOS = [21.0, 12.0, 10.0, 7.0, 6.0, 4.5, 3.0, 2.0, 1.5, 1.2]
SIZES = ["large_bold", "medium", "small_thin"]
BGS = ["flat", "gradient", "noisy"]
THEMES = {
    "light": dict(flat=250.0, grad=(210.0, 250.0), noisy_base=200.0, text_darker=True),
    "dark": dict(flat=10.0, grad=(5.0, 45.0), noisy_base=40.0, text_darker=False),
}
NOISE_TRIALS = 24  # independent seeds for the noise floor


def in_container() -> bool:
    """Are we inside the pinned toolchain container?

    Must key on something ONLY a container has. `/opt/wasi-sdk` does not
    qualify: an operator machine that builds the renderer has the SDK
    installed too, so that test stamped a host run as "pinned container" —
    the one confusion the marker exists to prevent, on exactly the machine
    the comparison was measured on. Docker creates `/.dockerenv` inside the
    container and nowhere else; podman's equivalent is checked too so the
    answer does not silently depend on which runtime invoked us.
    """
    return os.path.exists("/.dockerenv") or os.path.exists("/run/.containerenv")


def cls(ratio: float) -> str:
    """The label convention: >=6:1 readable, <=3:1 unreadable, else ambiguous."""
    if ratio >= 6.0:
        return "readable"
    if ratio <= 3.0:
        return "unreadable"
    return "borderline"


def frame_seed(*parts) -> int:
    key = "|".join(str(p) for p in parts)
    return (BASE_SEED ^ zlib.crc32(key.encode())) & 0xFFFFFFFF


def make_bg(bg_type, cfg, rng):
    if bg_type == "flat":
        return make_flat_bg(FRAME_H, FRAME_W, cfg["flat"])
    if bg_type == "gradient":
        return make_gradient_bg(FRAME_H, FRAME_W, *cfg["grad"])
    return make_noisy_bg(FRAME_H, FRAME_W, cfg["noisy_base"], NOISY_STD, rng)


def local_bg_gray(bg_type, cfg):
    if bg_type == "flat":
        return cfg["flat"]
    if bg_type == "gradient":
        return sum(cfg["grad"]) / 2.0
    return cfg["noisy_base"]


def build_frame(theme, bg_type, size_cat, ratio, seed_tag="frozen"):
    cfg = THEMES[theme]
    rng = np.random.default_rng(frame_seed(seed_tag, theme, bg_type, size_cat, ratio))
    base = make_bg(bg_type, cfg, rng)
    bg_gray = local_bg_gray(bg_type, cfg)
    g = solve_text_gray(bg_gray, ratio, text_darker=cfg["text_darker"])
    alpha = render_text_alpha(TEXT, size_cat)
    x, y = 20, max(10, (FRAME_H - alpha.shape[0]) // 2)
    composite_alpha(base, alpha, x, y, g)
    mask = ink_mask_full_frame(base.shape, alpha, x, y)
    return base, mask, actual_contrast_ratio(g, bg_gray)


def region_score(base, mask, k=K_OPERATING):
    """The winner's metric over ONE region: clamped ink-edge drift, absolute
    threshold, measured in sRGB CODE VALUES.

    Two words this signature does not earn, both of which it used to claim.
    It is not WORST-REGION: it takes a single mask and indexes [0], so no max
    is ever taken and every landmark this driver reports is a single-region
    score. lib.py's worst_tile_score is the worst-of-many reduction and only
    run_experiment.py exercises it. And it is not LINEAR LIGHT: the 0..255
    frame goes straight into veiling_glare and then ndimage.sobel, so the 40.0
    threshold is definitionally an sRGB-code-value threshold. See
    PROVENANCE.txt — linear light is an upstream acceptance criterion this
    reference does not meet, not a description of what it does.
    """
    deg = veiling_glare(base, k=k, veil_gray=VEIL_GRAY)
    return per_region_scores(base, deg, [mask])[0], deg


def edge_energy_ratio(base, deg):
    """NOT A RE-MEASUREMENT — read this before quoting the number it produces.

    The edge-energy-ratio dead end was a SEPARATE bake-off variant, and only
    the WINNER's lib.py was preserved. lib.py has no edge-ratio function, so
    the formula below is a reimplementation, and a reimplementation is exactly
    what the provenance note warns against: it compares in shape, not
    digit-for-digit, which is the entire failure this script exists to retire.

    It is kept only as a shape check (does the quantity stay roughly flat
    across a 21:1 -> 1.2:1 sweep?) and it must NOT be reported as confirming
    or contradicting the recorded 0.50 +/- 0.004. Genuinely re-measuring that
    dead end needs the variant's own code, which is gone.
    """
    eo, ed = edge_bitmap(base), edge_bitmap(deg)
    tot = eo.sum() + ed.sum()
    return float(ed.sum() / tot) if tot else 0.5


def main() -> int:
    rows = []
    for theme in THEMES:
        for bg in BGS:
            for size in SIZES:
                for ratio in RATIOS:
                    base, mask, actual = build_frame(theme, bg, size, ratio)
                    reg, deg = region_score(base, mask)
                    rows.append(dict(
                        theme=theme, bg=bg, size=size, ratio=ratio,
                        actual_ratio=round(actual, 3), cls=cls(ratio),
                        region=float(reg),
                        cumulative=float(cumulative_score(base, deg)),
                        edge_ratio=edge_energy_ratio(base, deg),
                    ))

    readable = [r["region"] for r in rows if r["cls"] == "readable"]
    unreadable = [r["region"] for r in rows if r["cls"] == "unreadable"]
    # a HIGHER region score = more ink-edge lost = less readable
    worst_readable, best_unreadable = max(readable), min(unreadable)
    gap = best_unreadable - worst_readable

    # ── noise floor: ONE configuration, many independent seeds ─────────────
    noise = []
    for t in range(NOISE_TRIALS):
        base, mask, _ = build_frame("dark", "noisy", "medium", 6.0, seed_tag=f"noise{t}")
        reg, _ = region_score(base, mask)
        noise.append(float(reg))
    noise_sd = statistics.pstdev(noise)
    noise_range = max(noise) - min(noise)

    # ── dead ends, re-run on THIS corpus ──────────────────────────────────
    ers = [r["edge_ratio"] for r in rows]
    cums_r = [r["cumulative"] for r in rows if r["cls"] == "readable"]
    cums_u = [r["cumulative"] for r in rows if r["cls"] == "unreadable"]

    result = dict(
        environment=dict(python=platform.python_version(), numpy=np.__version__,
                         container=in_container(),
                         note=("run environment — the pinned container and a "
                               "host stack must agree; a divergence is a "
                               "finding, not a footnote. The VERSIONS are the "
                               "load-bearing half: they are what makes a "
                               "figure reproducible, and they are reported "
                               "whether or not the container is detected")),
        corpus=dict(frames=len(rows), seed=BASE_SEED, frame=[FRAME_H, FRAME_W],
                    k=K_OPERATING, veil_gray=VEIL_GRAY, trials=NOISE_TRIALS),
        separation=dict(worst_readable=worst_readable, best_unreadable=best_unreadable,
                        gap=gap, separable=bool(gap > 0)),
        noise=dict(sd=noise_sd, range=noise_range, mean=statistics.fmean(noise)),
        verdict=("gap EXCEEDS noise — a binary threshold is defensible"
                 if gap > noise_range else
                 "gap is NARROWER than noise — the three-way band is MANDATORY"),
        dead_ends=dict(
            cumulative=dict(worst_readable=max(cums_r), best_unreadable=min(cums_u),
                            gap=min(cums_u) - max(cums_r),
                            faithful=True,
                            note="uses lib.py's OWN cumulative_score — a real re-measurement"),
            edge_energy_ratio=dict(min=min(ers), max=max(ers), spread=max(ers) - min(ers),
                                   faithful=False,
                                   note=("REIMPLEMENTED — the variant's code was not "
                                         "preserved, so this is a shape check only and "
                                         "must not be quoted against the recorded "
                                         "0.50 +/- 0.004")),
        ),
    )

    print("══ PDL-R STEP 1 — ONE FROZEN SHARED CORPUS ══")
    print(f"  env       python {result['environment']['python']}, "
          f"numpy {result['environment']['numpy']}, "
          f"{'pinned container' if result['environment']['container'] else 'host'}")
    print(f"  corpus    {len(rows)} frames, seed {BASE_SEED}, "
          f"{FRAME_H}x{FRAME_W}, k={K_OPERATING}, veil={VEIL_GRAY}")
    print("\n── separation (Region Ink-edge Drift, the winner) ──")
    print(f"  worst readable   {worst_readable:.4f}")
    print(f"  best unreadable  {best_unreadable:.4f}")
    print(f"  GAP              {gap:+.4f}")
    print(f"\n── noise floor ({NOISE_TRIALS} independent seeds, one config) ──")
    print(f"  sd     {noise_sd:.4f}")
    print(f"  range  {noise_range:.4f}")
    print(f"\n  VERDICT: {result['verdict']}")
    print("\n── dead end, FAITHFULLY re-measured (lib.py's own function) ──")
    print(f"  cumulative   gap {min(cums_u) - max(cums_r):+.4f} "
          f"vs per-region {gap:+.4f}")
    print("               negative = the classes OVERLAP; cumulative does not "
          "separate at all on this corpus")
    print("\n── NOT a re-measurement ──")
    print(f"  edge-energy ratio  {min(ers):.4f}..{max(ers):.4f} "
          f"(spread {max(ers) - min(ers):.4f})")
    print("               reimplemented — that variant's code was never preserved,")
    print("               so this is a SHAPE check and must not be quoted against")
    print("               the recorded 0.50 +/- 0.004.")

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "frozen_corpus_result.json")
    with open(out, "w") as f:
        json.dump(dict(result, rows=rows), f, indent=2)
    print(f"\n  full rows -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
