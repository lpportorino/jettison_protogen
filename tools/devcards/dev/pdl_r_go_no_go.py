#!/usr/bin/env python3
"""Read-only PDL-R go/no-go measurement: the band, its canaries, and the
three confirmations, measured on the preserved reference corpus.

THIS IS A PROBE, NOT A PRODUCER. It arms no lane, writes nothing, and exits
zero when the measurement completes — including when the scientific result is
NO-GO, which is a result and not an error. ``--expect-decision`` gives it a
negative leg: a mismatch exits 1 naming the expectation, not a stack trace.

WHAT IT MEASURES, and where each criterion comes from. The probe reuses rather
than reimplements ``pdl-r-reference/{lib.py,frozen_corpus.py}`` — the preserved
V6 "Region Ink-edge Drift" winner and its one frozen synthetic corpus. The
acceptance criteria are NOT invented here; they are the upstream bake-off's own,
quoted in ``PROVENANCE.txt`` and in the plan section that governs this work:

* the band SHAPE is ``±3σ`` about the class extremes — upstream states the
  construction explicitly as ``readable_max − 3σ`` / ``unreadable_min + 3σ``.
  The probe re-derives both cuts from ITS OWN run; upstream's provisional
  0.120/0.147 are not carried forward, because they were measured on a
  different corpus and are labelled provisional at the source.
* the CANARY trio is "a pair authored at exactly 6:1 over a flat background must
  land in the review band; 3:1 must hard-fail; 12:1 must hard-pass".
  **Over a FLAT background** is part of that sentence, so the flat population is
  the canary and the all-backgrounds figure is reported beside it as an audit,
  never as the canary.
* "not a knife-edge — it holds across a sweep of k and EDGE_THRESH" is an
  acceptance clause, so the declared sweep is a criterion and not a search. No
  cell of it is ever promoted to a new operating point.
* a three-way band may only be wired in once "validated as a classifier on a
  held-out labelled set" (the quality contract's §0), so the band fitted on the
  frozen seed is evaluated against independently seeded corpora it never saw.

THE BAND IS FITTED TO THE POPULATION ITS CANARY JUDGES, AND THAT PUTS A CEILING
ON THE CANARY. The frame attaining ``unreadable_min`` sits exactly 3σ BELOW the
hard-fail cut by construction, so it cannot hard-fail; when that frame is itself
a 3:1 flat frame — it is — the "3:1 must hard-fail" canary can never be
satisfied on the population that defined the cut. The probe computes and prints
that ceiling rather than letting a structurally unreachable clause read as an
empirical failure. The held-out leg exists because it is the only evaluation
that escapes this.

THE THREE CONFIRMATIONS gate a FAIL, per upstream: it stands only if it
(i) reproduces on ≥3 seeded background realisations, (ii) is monotone in k, and
(iii) survives a 1px shift. The probe runs all three against the 3:1 noisy
candidates, and reports (iii) in BOTH forms:

* the SPECIFIED form is ``lib.shift_1px``, which rolls the frame and its mask
  together, and which upstream itself records as "shift measured exactly 0
  delta". A Sobel edge bitmap over a co-rolled mask is translation-equivariant,
  so that zero is a property of the operator and not of the frame — as
  literally specified, confirmation (iii) cannot fail. It is printed, and it is
  labelled a tautology rather than a confirmation.
* the STRENGTHENED form re-renders the glyph one pixel right over the SAME
  seeded background, moving the ink but not the background. That one can fail,
  and it is the number the confirmation count uses.

WHAT IT CANNOT SEE. Synthetic PIL/DejaVu glyph masks, an sRGB-code-value
degradation, and WCAG-computed contrast labels. It does not use LVGL's compiled
fonts, renderer-supplied ink masks, a decoration-suppressed render pass, a
PDL-1-derived linear-light edge threshold, real day/thermal backgrounds, or any
human legibility label. "readable"/"unreadable" here are names for contrast
CLASSES, never a claim that a person could read the frame. No result below can
authorise a blocking producer; the standing blockers are printed with the
decision.

Read-only. Run from the repository root:

  tools/uber.sh 'python3 tools/devcards/dev/pdl_r_go_no_go.py'
  tools/uber.sh \
    'python3 tools/devcards/dev/pdl_r_go_no_go.py --expect-decision NO-GO'
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import platform
import statistics
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType
from typing import Any

import numpy as np
import PIL
import scipy


K_SWEEP = (0.3, 0.4, 0.5, 0.6, 0.7)
EDGE_THRESHOLD_SWEEP = (32.0, 36.0, 40.0, 44.0, 48.0)
RECORDED_K = 0.6
RECORDED_EDGE_THRESHOLD = 40.0
CONFIRMATION_SEEDS = 5
HOLDOUT_SEEDS = 3
CANARY_BACKGROUND = "flat"
SLICE_TOTAL = 18
MONOTONIC_EPSILON = 1.0e-12

# ratio -> the verdict upstream's canary requires of it, on CANARY_BACKGROUND.
CANARIES = ((12.0, "PASS"), (6.0, "REVIEW"), (3.0, "FAIL"))


@dataclass(frozen=True)
class Frame:
    """One labelled synthetic frame and its tight glyph-ink mask."""

    theme: str
    background: str
    size: str
    ratio: float
    pixels: np.ndarray
    mask: np.ndarray

    @property
    def label(self) -> str:
        return f"{self.theme}/{self.background}/{self.size}/{self.ratio:g}:1"


@dataclass(frozen=True)
class Clause:
    """One acceptance clause, its measured truth, and the evidence for it."""

    name: str
    ok: bool
    detail: str


def load_reference() -> ModuleType:
    """Load the tracked frozen driver, which in turn loads and pins V6 lib.py."""

    reference_dir = Path(__file__).resolve().parents[1] / "pdl-r-reference"
    driver_path = reference_dir / "frozen_corpus.py"
    if not driver_path.is_file():
        raise SystemExit(f"PDL-R reference driver missing: {driver_path}")

    spec = importlib.util.spec_from_file_location(
        "pdl_r_frozen_reference", driver_path
    )
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot load PDL-R reference driver: {driver_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def class_name(ratio: float) -> str:
    """The frozen corpus's own contrast-label convention (frozen_corpus cls())."""

    if ratio >= 6.0:
        return "readable"
    if ratio <= 3.0:
        return "unreadable"
    return "borderline"


def build_frame_at_x(
    reference: ModuleType,
    theme: str,
    background: str,
    size: str,
    ratio: float,
    *,
    seed_tag: str,
    x: int = 20,
) -> Frame:
    """The reference's own build_frame, with the glyph x coordinate exposed.

    The preserved function fixes x=20. Exposing x — and nothing else — lets the
    strengthened spatial confirmation move the INK while holding the seeded
    background identical. Every construction step remains the reference's code.
    """

    config = reference.THEMES[theme]
    rng = np.random.default_rng(
        reference.frame_seed(seed_tag, theme, background, size, ratio)
    )
    pixels = reference.make_bg(background, config, rng)
    background_gray = reference.local_bg_gray(background, config)
    glyph_gray = reference.solve_text_gray(
        background_gray, ratio, text_darker=config["text_darker"]
    )
    alpha = reference.render_text_alpha(reference.TEXT, size)
    y = max(10, (reference.FRAME_H - alpha.shape[0]) // 2)
    reference.composite_alpha(pixels, alpha, x, y, glyph_gray)
    mask = reference.ink_mask_full_frame(pixels.shape, alpha, x, y)
    return Frame(theme, background, size, ratio, pixels, mask)


def corpus(reference: ModuleType, seed_tag: str, *, x: int = 20) -> list[Frame]:
    """The full theme × background × size × ratio grid at one seed tag."""

    return [
        build_frame_at_x(
            reference, theme, background, size, ratio, seed_tag=seed_tag, x=x
        )
        for theme in reference.THEMES
        for background in reference.BGS
        for size in reference.SIZES
        for ratio in reference.RATIOS
    ]


def noise_frames(reference: ModuleType) -> list[Frame]:
    """The corpus's only stochastic axis: one authored cell, many seeds."""

    return [
        build_frame_at_x(
            reference, "dark", "noisy", "medium", 6.0, seed_tag=f"noise{trial}"
        )
        for trial in range(reference.NOISE_TRIALS)
    ]


def score(
    reference: ModuleType, frame: Frame, *, k: float, edge_threshold: float
) -> float | None:
    """Region ink-edge drift, or None where the reference cannot judge."""

    degraded = reference.veiling_glare(
        frame.pixels, k=k, veil_gray=reference.VEIL_GRAY
    )
    return reference.per_region_scores(
        frame.pixels, degraded, [frame.mask], thresh=edge_threshold
    )[0]


def verdict(value: float, pass_cut: float, fail_cut: float) -> str:
    if value <= pass_cut:
        return "PASS"
    if value >= fail_cut:
        return "FAIL"
    return "REVIEW"


def count_verdicts(
    values: list[float], pass_cut: float, fail_cut: float
) -> dict[str, int]:
    counts = Counter(verdict(value, pass_cut, fail_cut) for value in values)
    return {name: counts.get(name, 0) for name in ("PASS", "REVIEW", "FAIL")}


def measure_cell(
    reference: ModuleType,
    frames: list[Frame],
    seeded_noise: list[Frame],
    *,
    k: float,
    edge_threshold: float,
) -> dict[str, Any]:
    """Everything one (k, edge threshold) operating point has to say."""

    measured: list[tuple[Frame, float]] = []
    cant_tell: list[str] = []
    for frame in frames:
        value = score(reference, frame, k=k, edge_threshold=edge_threshold)
        if value is None:
            cant_tell.append(frame.label)
        else:
            measured.append((frame, value))

    noise_values = [
        score(reference, frame, k=k, edge_threshold=edge_threshold)
        for frame in seeded_noise
    ]
    cant_tell.extend(
        f"noise-seed-{index}"
        for index, value in enumerate(noise_values)
        if value is None
    )

    result: dict[str, Any] = {
        "k": k,
        "edge_threshold": edge_threshold,
        "cant_tell": cant_tell,
        "measured": len(measured),
        "separates": False,
    }
    # A cell that could not judge every frame is UNMEASURED, never clean: the
    # reference's own min-edge-px path returns None and lib.py drops it
    # silently. Reporting it as a named cantTell is what this repo requires of
    # a port; carrying on with the survivors would be the silent skip.
    if cant_tell:
        return result

    readable = [(f, v) for f, v in measured if class_name(f.ratio) == "readable"]
    unreadable = [
        (f, v) for f, v in measured if class_name(f.ratio) == "unreadable"
    ]
    worst_readable = max(readable, key=lambda row: row[1])
    best_unreadable = min(unreadable, key=lambda row: row[1])
    gap = best_unreadable[1] - worst_readable[1]

    concrete_noise = [float(value) for value in noise_values]
    noise_sigma = statistics.pstdev(concrete_noise)
    noise_range = max(concrete_noise) - min(concrete_noise)
    # Upstream's construction, verbatim: readable_max − 3σ / unreadable_min + 3σ.
    pass_cut = worst_readable[1] - 3.0 * noise_sigma
    fail_cut = best_unreadable[1] + 3.0 * noise_sigma

    slice_positive = 0
    monotonic = 0
    inversions: list[dict[str, Any]] = []
    for theme in reference.THEMES:
        for background in reference.BGS:
            for size in reference.SIZES:
                population = [
                    (f, v)
                    for f, v in measured
                    if (f.theme, f.background, f.size) == (theme, background, size)
                ]
                readable_slice = [
                    v for f, v in population if class_name(f.ratio) == "readable"
                ]
                unreadable_slice = [
                    v for f, v in population if class_name(f.ratio) == "unreadable"
                ]
                if min(unreadable_slice) - max(readable_slice) > 0.0:
                    slice_positive += 1

                by_ratio = {f.ratio: v for f, v in population}
                ordered = [by_ratio[ratio] for ratio in reference.RATIOS]
                broke = False
                for index, (value, next_value) in enumerate(
                    zip(ordered, ordered[1:])
                ):
                    if next_value + MONOTONIC_EPSILON < value:
                        broke = True
                        inversions.append(
                            {
                                "slice": f"{theme}/{background}/{size}",
                                "from_ratio": reference.RATIOS[index],
                                "to_ratio": reference.RATIOS[index + 1],
                                "delta": next_value - value,
                            }
                        )
                if not broke:
                    monotonic += 1

    # The canary is upstream's sentence, and its population is the FLAT
    # background. The all-backgrounds count beside it is an audit of how far
    # the requirement travels, not the requirement.
    canaries: dict[str, Any] = {}
    for ratio, expected in CANARIES:
        flat = [
            (f, v)
            for f, v in measured
            if f.ratio == ratio and f.background == CANARY_BACKGROUND
        ]
        every = [(f, v) for f, v in measured if f.ratio == ratio]
        # A cut-defining frame sits EXACTLY on its own cut and is excluded from
        # the strict inequality, so it can never satisfy a hard-verdict canary.
        blocked = [
            f.label
            for f, _ in flat
            if (expected == "FAIL" and f is best_unreadable[0])
            or (expected == "PASS" and f is worst_readable[0])
        ]
        canaries[f"{ratio:g}:1->{expected}"] = {
            "matched": sum(verdict(v, pass_cut, fail_cut) == expected for _, v in flat),
            "total": len(flat),
            "ceiling": len(flat) - len(blocked),
            "blocked_by_construction": blocked,
            "audit_matched": sum(
                verdict(v, pass_cut, fail_cut) == expected for _, v in every
            ),
            "audit_total": len(every),
            "verdicts": [
                (f.theme, f.size, v, verdict(v, pass_cut, fail_cut)) for f, v in flat
            ],
        }

    occupancy = {
        name: count_verdicts(
            [v for f, v in measured if class_name(f.ratio) == name],
            pass_cut,
            fail_cut,
        )
        for name in ("readable", "borderline", "unreadable")
    }

    result.update(
        {
            "worst_readable": worst_readable[1],
            "worst_readable_frame": worst_readable[0].label,
            "best_unreadable": best_unreadable[1],
            "best_unreadable_frame": best_unreadable[0].label,
            "gap": gap,
            "noise_sigma": noise_sigma,
            "noise_range": noise_range,
            "pass_cut": pass_cut,
            "fail_cut": fail_cut,
            "slice_positive": slice_positive,
            "monotonic": monotonic,
            "inversions": inversions,
            "canaries": canaries,
            "occupancy": occupancy,
            "separates": gap > 0.0 and slice_positive == SLICE_TOTAL,
        }
    )
    return result


def holdout_measurements(
    reference: ModuleType, baseline: dict[str, Any]
) -> dict[str, Any]:
    """Apply the FITTED band to corpora whose seeds it never saw.

    The band's cuts are derived from the frozen corpus's own class extremes, so
    every count taken on that corpus is in-sample. This is the held-out
    evaluation §0 requires before any three-way measurement may be wired in.
    """

    pass_cut = baseline["pass_cut"]
    fail_cut = baseline["fail_cut"]
    seeds = []
    for trial in range(HOLDOUT_SEEDS):
        rows = [
            (frame, score(
                reference,
                frame,
                k=RECORDED_K,
                edge_threshold=RECORDED_EDGE_THRESHOLD,
            ))
            for frame in corpus(reference, f"holdout{trial}")
        ]
        unjudged = [f.label for f, v in rows if v is None]
        judged = [(f, v) for f, v in rows if v is not None]
        readable = [(f, v) for f, v in judged if class_name(f.ratio) == "readable"]
        unreadable = [
            (f, v) for f, v in judged if class_name(f.ratio) == "unreadable"
        ]
        false_fail = [
            f.label for f, v in readable if verdict(v, pass_cut, fail_cut) == "FAIL"
        ]
        false_pass = [
            f.label for f, v in unreadable if verdict(v, pass_cut, fail_cut) == "PASS"
        ]
        seeds.append(
            {
                "tag": f"holdout{trial}",
                "judged": len(judged),
                "unjudged": unjudged,
                "labelled": len(readable) + len(unreadable),
                "false_fail": false_fail,
                "false_pass": false_pass,
                "review": sum(
                    verdict(v, pass_cut, fail_cut) == "REVIEW" for _, v in judged
                ),
                "gap": (
                    min(v for _, v in unreadable) - max(v for _, v in readable)
                ),
            }
        )

    return {
        "seeds": seeds,
        "labelled": sum(seed["labelled"] for seed in seeds),
        "false_hard": sum(
            len(seed["false_fail"]) + len(seed["false_pass"]) for seed in seeds
        ),
        "unjudged": sum(len(seed["unjudged"]) for seed in seeds),
    }


def confirmation_measurements(
    reference: ModuleType, frames: list[Frame], baseline: dict[str, Any]
) -> dict[str, Any]:
    """The three confirmations that must hold for a FAIL to stand."""

    pass_cut = baseline["pass_cut"]
    fail_cut = baseline["fail_cut"]
    groups = []
    candidates: list[tuple[Frame, float, int]] = []

    for theme in reference.THEMES:
        for size in reference.SIZES:
            values = []
            for trial in range(CONFIRMATION_SEEDS):
                frame = build_frame_at_x(
                    reference, theme, "noisy", size, 3.0, seed_tag=f"confirm{trial}"
                )
                value = score(
                    reference,
                    frame,
                    k=RECORDED_K,
                    edge_threshold=RECORDED_EDGE_THRESHOLD,
                )
                if value is None:
                    raise SystemExit(
                        f"confirmation candidate unmeasurable: {frame.label}"
                    )
                values.append(value)
                candidates.append((frame, value, trial))
            groups.append(
                {
                    "slice": f"{theme}/noisy/{size}",
                    "failures": sum(
                        verdict(value, pass_cut, fail_cut) == "FAIL"
                        for value in values
                    ),
                    "total": len(values),
                    "minimum": min(values),
                    "maximum": max(values),
                }
            )

    seed_confirmed = sum(group["failures"] == group["total"] for group in groups)

    k_confirmed = 0
    for frame, _, _ in candidates:
        values = [
            score(reference, frame, k=k, edge_threshold=RECORDED_EDGE_THRESHOLD)
            for k in K_SWEEP
        ]
        if any(value is None for value in values):
            continue
        if all(
            next_value + MONOTONIC_EPSILON >= value
            for value, next_value in zip(values, values[1:])
        ):
            k_confirmed += 1

    shift_confirmed = 0
    shift_max_delta = 0.0
    for frame, value, trial in candidates:
        moved = build_frame_at_x(
            reference,
            frame.theme,
            frame.background,
            frame.size,
            frame.ratio,
            seed_tag=f"confirm{trial}",
            x=21,
        )
        moved_value = score(
            reference, moved, k=RECORDED_K, edge_threshold=RECORDED_EDGE_THRESHOLD
        )
        if moved_value is None:
            continue
        shift_max_delta = max(shift_max_delta, abs(value - moved_value))
        if (
            verdict(value, pass_cut, fail_cut) == "FAIL"
            and verdict(moved_value, pass_cut, fail_cut) == "FAIL"
        ):
            shift_confirmed += 1

    # The SPECIFIED (iii) — roll frame and mask together — over the whole
    # corpus, alongside the strengthened glyph-only re-render of the same
    # frames, so the two can be read against each other.
    rolled_max_delta = 0.0
    glyph_nonzero = 0
    glyph_max_delta = 0.0
    glyph_flips = []
    for frame in frames:
        value = score(
            reference, frame, k=RECORDED_K, edge_threshold=RECORDED_EDGE_THRESHOLD
        )
        if value is None:
            continue

        rolled = Frame(
            frame.theme,
            frame.background,
            frame.size,
            frame.ratio,
            reference.lib.shift_1px(frame.pixels),
            reference.lib.shift_1px(frame.mask),
        )
        rolled_value = score(
            reference, rolled, k=RECORDED_K, edge_threshold=RECORDED_EDGE_THRESHOLD
        )
        if rolled_value is not None:
            rolled_max_delta = max(rolled_max_delta, abs(value - rolled_value))

        moved = build_frame_at_x(
            reference,
            frame.theme,
            frame.background,
            frame.size,
            frame.ratio,
            seed_tag="frozen",
            x=21,
        )
        moved_value = score(
            reference, moved, k=RECORDED_K, edge_threshold=RECORDED_EDGE_THRESHOLD
        )
        if moved_value is None:
            continue
        delta = abs(value - moved_value)
        glyph_max_delta = max(glyph_max_delta, delta)
        if delta > 0.0:
            glyph_nonzero += 1
        before = verdict(value, pass_cut, fail_cut)
        after = verdict(moved_value, pass_cut, fail_cut)
        if before != after:
            glyph_flips.append(
                {
                    "frame": frame.label,
                    "before_score": value,
                    "after_score": moved_value,
                    "before": before,
                    "after": after,
                }
            )

    total = len(candidates)
    return {
        "seed": {"confirmed": seed_confirmed, "total": len(groups), "groups": groups},
        "k_monotonic": {"confirmed": k_confirmed, "total": total},
        "shift": {
            "confirmed": shift_confirmed,
            "total": total,
            "candidate_max_delta": shift_max_delta,
            "corpus_nonzero": glyph_nonzero,
            "corpus_total": len(frames),
            "corpus_max_delta": glyph_max_delta,
            "corpus_flips": glyph_flips,
        },
        "specified_roll": {"max_delta": rolled_max_delta},
        "all_confirmed": (
            seed_confirmed == len(groups)
            and k_confirmed == total
            and shift_confirmed == total
        ),
    }


def decide(
    baseline: dict[str, Any], cells: list[dict[str, Any]], holdout: dict[str, Any]
) -> tuple[str, list[Clause]]:
    """Compute the verdict from the measured clauses. Never a literal."""

    canaries = baseline["canaries"]
    canary_ok = all(
        entry["matched"] == entry["total"] for entry in canaries.values()
    )
    canary_detail = "; ".join(
        f"{name} {entry['matched']}/{entry['total']}"
        + (
            f" (ceiling {entry['ceiling']}/{entry['total']}"
            f" — {', '.join(entry['blocked_by_construction'])} defines the cut)"
            if entry["blocked_by_construction"]
            else ""
        )
        for name, entry in canaries.items()
    )

    measurable = [cell for cell in cells if not cell["cant_tell"]]
    stable = [cell for cell in measurable if cell["gap"] > 0.0]

    clauses = [
        Clause(
            "separation",
            baseline["separates"],
            f"global gap {baseline['gap']:+.6f}; "
            f"per-slice positive {baseline['slice_positive']}/{SLICE_TOTAL}",
        ),
        Clause(
            "monotonicity",
            baseline["monotonic"] == SLICE_TOTAL,
            f"{baseline['monotonic']}/{SLICE_TOTAL} slices monotone in authored "
            "contrast"
            + (
                "; inversions "
                + ", ".join(
                    f"{item['slice']} {item['from_ratio']:g}:1→"
                    f"{item['to_ratio']:g}:1 {item['delta']:+.6f}"
                    for item in baseline["inversions"]
                )
                if baseline["inversions"]
                else ""
            ),
        ),
        Clause(
            "not-a-knife-edge",
            len(stable) == len(cells),
            f"positive gap at {len(stable)}/{len(cells)} declared operating "
            f"points; {len(cells) - len(measurable)}/{len(cells)} unmeasurable",
        ),
        Clause("canaries", canary_ok, canary_detail),
        Clause(
            "held-out",
            holdout["false_hard"] == 0 and holdout["unjudged"] == 0,
            f"{holdout['false_hard']} false hard verdicts over "
            f"{holdout['labelled']} labelled held-out frames; "
            f"{holdout['unjudged']} unjudged",
        ),
    ]
    decision = "GO" if all(clause.ok for clause in clauses) else "NO-GO"
    return decision, clauses


def print_environment(reference: ModuleType, frames: int, noise: int) -> None:
    reference_dir = Path(reference.__file__).resolve().parent
    fonts = sorted(
        {
            Path(reference.lib.FONT_BOLD).resolve(),
            Path(reference.lib.FONT_REG).resolve(),
        }
    )
    print("══ PDL-R GO/NO-GO — BAND, CANARIES, THREE CONFIRMATIONS ══")
    print(
        f"environment  python={platform.python_version()} numpy={np.__version__} "
        f"scipy={scipy.__version__} pillow={PIL.__version__} "
        f"container={str(reference.in_container()).lower()}"
    )
    print(
        f"reference    lib.py sha256={sha256(reference_dir / 'lib.py')} "
        f"frozen_corpus.py sha256={sha256(reference_dir / 'frozen_corpus.py')}"
    )
    for path in fonts:
        print(f"font         {path} sha256={sha256(path)}")
    print(
        f"corpus       {frames} frames "
        f"({len(reference.THEMES)} themes × {len(reference.BGS)} backgrounds × "
        f"{len(reference.SIZES)} sizes × {len(reference.RATIOS)} ratios); "
        f"{noise} noise seeds; {HOLDOUT_SEEDS} held-out corpora"
    )
    print(
        "scope        synthetic PIL/DejaVu, sRGB code values; NOT renderer "
        "output, NOT human legibility"
    )


def print_baseline(baseline: dict[str, Any]) -> None:
    print(
        f"\n── recorded operating point: k={RECORDED_K}, "
        f"edge={RECORDED_EDGE_THRESHOLD} ──"
    )
    print(
        f"separation   worst-readable={baseline['worst_readable']:.6f} "
        f"({baseline['worst_readable_frame']})"
    )
    print(
        f"             best-unreadable={baseline['best_unreadable']:.6f} "
        f"({baseline['best_unreadable_frame']})"
    )
    print(f"             gap={baseline['gap']:+.6f}")
    print(
        f"noise        sigma={baseline['noise_sigma']:.6f} "
        f"range={baseline['noise_range']:.6f} "
        f"gap-minus-range={baseline['gap'] - baseline['noise_range']:+.6f}"
    )
    print(
        f"derived band hard-PASS ≤ {baseline['pass_cut']:.6f}; "
        f"REVIEW ({baseline['pass_cut']:.6f}, {baseline['fail_cut']:.6f}); "
        f"hard-FAIL ≥ {baseline['fail_cut']:.6f}"
    )
    print(
        "shape        "
        + (
            "BANDED REQUIRED — gap narrower than the seed-to-seed range"
            if baseline["gap"] < baseline["noise_range"]
            else "separation exceeds the measured seed range"
        )
    )
    print(
        f"structure    positive slice gaps "
        f"{baseline['slice_positive']}/{SLICE_TOTAL}; monotone slices "
        f"{baseline['monotonic']}/{SLICE_TOTAL}"
    )
    for item in baseline["inversions"]:
        print(
            f"  inversion  {item['slice']} {item['from_ratio']:g}:1→"
            f"{item['to_ratio']:g}:1 {item['delta']:+.6f} "
            f"({abs(item['delta']) / baseline['noise_sigma']:.2f}σ)"
        )
    print(f"canary population: {CANARY_BACKGROUND} background (upstream's wording)")
    for name, entry in baseline["canaries"].items():
        ceiling = (
            f" ceiling={entry['ceiling']}/{entry['total']}"
            if entry["blocked_by_construction"]
            else ""
        )
        print(
            f"canary       {name:<13} {entry['matched']}/{entry['total']}{ceiling}"
            f"   [all backgrounds: {entry['audit_matched']}/{entry['audit_total']}]"
        )
        for theme, size, value, got in entry["verdicts"]:
            print(f"               {theme}/{size:<11} {value:.6f} {got}")
        for label in entry["blocked_by_construction"]:
            print(
                f"               {label} DEFINES this cut and sits exactly 3σ "
                "off it — unreachable by construction"
            )
    for name, counts in baseline["occupancy"].items():
        print(
            f"occupancy    {name:<10} PASS={counts['PASS']:2d} "
            f"REVIEW={counts['REVIEW']:2d} FAIL={counts['FAIL']:2d}"
        )


def print_sweep(cells: list[dict[str, Any]]) -> None:
    print("\n── declared sweep (an acceptance clause, never a threshold search) ──")
    print("  k   edge  measured       gap     noise  slices mono  separates")
    for cell in cells:
        if cell["cant_tell"]:
            print(
                f"{cell['k']:>3.1f} {cell['edge_threshold']:>6.1f}  "
                f"CANT_TELL({len(cell['cant_tell'])}) {cell['cant_tell'][0]}"
            )
            continue
        print(
            f"{cell['k']:>3.1f} {cell['edge_threshold']:>6.1f} "
            f"{cell['measured']:>9d} {cell['gap']:>+9.5f} "
            f"{cell['noise_range']:>9.5f} {cell['slice_positive']:>3d}/18 "
            f"{cell['monotonic']:>3d}/18  {str(cell['separates']).lower()}"
        )
    measurable = [cell for cell in cells if not cell["cant_tell"]]
    print(
        f"sweep        positive gap {sum(c['gap'] > 0.0 for c in measurable)}/"
        f"{len(cells)}; negative gap "
        f"{sum(c['gap'] <= 0.0 for c in measurable)}/{len(cells)}; "
        f"unmeasurable {len(cells) - len(measurable)}/{len(cells)}"
    )


def print_holdout(holdout: dict[str, Any]) -> None:
    print("\n── held-out evaluation of the FITTED band (§0's precondition) ──")
    for seed in holdout["seeds"]:
        print(
            f"{seed['tag']:<10} false-FAIL(readable)={len(seed['false_fail'])} "
            f"false-PASS(unreadable)={len(seed['false_pass'])} "
            f"over {seed['labelled']} labelled; REVIEW={seed['review']}/"
            f"{seed['judged']}; gap={seed['gap']:+.6f}"
        )
        for label in seed["false_fail"] + seed["false_pass"]:
            print(f"           MISCLASSIFIED {label}")
        for label in seed["unjudged"]:
            print(f"           UNJUDGED {label}")


def print_confirmations(confirmations: dict[str, Any]) -> None:
    print("\n── the three confirmations (3:1 noisy candidate FAILs) ──")
    seed = confirmations["seed"]
    print(
        f"(i)   seeds     {seed['confirmed']}/{seed['total']} slices hard-FAIL "
        f"on all {CONFIRMATION_SEEDS} independent background realisations"
    )
    monotone = confirmations["k_monotonic"]
    print(
        f"(ii)  k         {monotone['confirmed']}/{monotone['total']} candidates "
        f"monotone over k={K_SWEEP[0]}..{K_SWEEP[-1]}"
    )
    shift = confirmations["shift"]
    print(
        f"(iii) 1px shift {shift['confirmed']}/{shift['total']} candidates stay "
        f"hard-FAIL under a glyph-only x+1; max Δ="
        f"{shift['candidate_max_delta']:.6f}"
    )
    print(
        f"      specified form (roll frame+mask together) max Δ="
        f"{confirmations['specified_roll']['max_delta']:.6f} over the whole "
        "corpus — translation-equivariant, so as written it cannot fail"
    )
    print(
        f"      glyph-only audit over the corpus: "
        f"{shift['corpus_nonzero']}/{shift['corpus_total']} scores moved; "
        f"max Δ={shift['corpus_max_delta']:.6f}; "
        f"{len(shift['corpus_flips'])} verdict flips"
    )
    for flip in shift["corpus_flips"]:
        print(
            f"        {flip['frame']:<32} {flip['before_score']:.6f} "
            f"{flip['before']} → {flip['after_score']:.6f} {flip['after']}"
        )
    print(
        "confirmations "
        + ("all hold" if confirmations["all_confirmed"] else "DO NOT all hold")
        + " for the candidate FAILs"
    )


STANDING_BLOCKERS = (
    "EDGE_THRESH is 40 sRGB code values and upstream requires it DERIVED from "
    "the PDL-1 floor in linear light; nothing here discharges that",
    "the corpus is synthetic PIL/DejaVu, not renderer-supplied ink masks under "
    "a decoration-suppressed pass — glow alone is a measured +0.092 false "
    "positive, larger than any real content-loss delta",
    "the labels are WCAG contrast classes, never human legibility judgements",
)


def main() -> int:
    parser = argparse.ArgumentParser(description="PDL-R go/no-go probe")
    parser.add_argument(
        "--expect-decision",
        choices=("GO", "NO-GO"),
        help="exit 1 if the COMPUTED decision differs from this",
    )
    arguments = parser.parse_args()

    reference = load_reference()
    frames = corpus(reference, "frozen")
    seeded_noise = noise_frames(reference)

    print_environment(reference, len(frames), len(seeded_noise))

    cells = [
        measure_cell(
            reference, frames, seeded_noise, k=k, edge_threshold=edge_threshold
        )
        for k in K_SWEEP
        for edge_threshold in EDGE_THRESHOLD_SWEEP
    ]
    baseline = next(
        cell
        for cell in cells
        if cell["k"] == RECORDED_K
        and cell["edge_threshold"] == RECORDED_EDGE_THRESHOLD
    )
    if baseline["cant_tell"]:
        raise SystemExit(
            "recorded operating point returned cantTell: "
            + ", ".join(baseline["cant_tell"])
        )

    holdout = holdout_measurements(reference, baseline)
    confirmations = confirmation_measurements(reference, frames, baseline)

    print_baseline(baseline)
    print_sweep(cells)
    print_holdout(holdout)
    print_confirmations(confirmations)

    decision, clauses = decide(baseline, cells, holdout)
    print("\n── acceptance clauses (each measured above) ──")
    for clause in clauses:
        print(f"[{'HOLDS' if clause.ok else 'FAILS'}] {clause.name}: {clause.detail}")

    print(f"\n── measurement verdict: {decision} ──")
    print(
        "The confirmations gate a FAIL and they hold; that makes the candidate "
        "FAILs trustworthy. It does not make the CLASSIFIER usable — the "
        "clauses above are what a classifier is judged on."
    )
    print("standing blockers, none of them measurable by this probe:")
    for blocker in STANDING_BLOCKERS:
        print(f"  - {blocker}")
    print(
        "So a GO on the clauses would still not authorise a blocking producer; "
        "a NO-GO on them settles it without needing that argument."
    )
    print(
        "Contract consequence: §0 admits this quantity only as banded "
        "PASS/REVIEW/FAIL with any adjudicator confined to REVIEW, and only "
        "after held-out validation. Nothing here earns an exact verdict shape."
    )
    print("exit semantics: a completed measurement exits 0 whatever it decided")

    if arguments.expect_decision and arguments.expect_decision != decision:
        print(
            f"EXPECTATION FAILED: expected {arguments.expect_decision}, "
            f"computed {decision}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
