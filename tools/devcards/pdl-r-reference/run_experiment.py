"""
V6 bake-off: PER-REGION (TILED, worst-tile) vs CUMULATIVE scoring, on top of
an edge-bitmap-drift pixel metric (self-referential: same frame, before vs
after a veiling-glare degradation; Sobel-magnitude edges, absolute-clamped
to a boolean bitmap; score = fraction of edge pixels that did not survive).

Deterministic corpus generation: every RNG draw is seeded from
(BASE_SEED, canonical-key) via crc32 (stable across processes/interpreters,
unlike Python's hash() for strings under hash randomization).

Run: docker run --rm -v <worktree>:/w -w /w eudoxia/mega:latest python3 run_experiment.py
"""
import sys, json, zlib, time
sys.path.insert(0, "/w")
import numpy as np
from lib import (
    solve_text_gray, actual_contrast_ratio, gray_luminance,
    make_flat_bg, make_gradient_bg, make_noisy_bg,
    render_text_alpha, composite_alpha, ink_mask_full_frame,
    add_drop_shadow, add_glow, shift_1px, add_mild_noise,
    veiling_glare, cumulative_score, per_region_scores, worst_tile_score,
    edge_bitmap, EDGE_THRESH,
)

BASE_SEED = 20260725  # today's date (see currentDate) - fixed, not re-drawn
OUT = "/w/v6_results.json"

RATIOS = [21.0, 12.0, 10.0, 7.0, 6.0, 4.5, 3.0, 2.0, 1.5, 1.2]
SIZES = ["large_bold", "medium", "small_thin"]
K_OPERATING = 0.6   # the gate's canonical washout level for headline numbers
K_SWEEP = [0.3, 0.4, 0.5, 0.6, 0.7]

THEMES = {
    "light": dict(flat=250.0, grad=(210.0, 250.0), noisy_base=200.0, text_darker=True),
    "dark": dict(flat=10.0, grad=(5.0, 45.0), noisy_base=40.0, text_darker=False),
}
NOISY_STD = 14.0
VEIL_GRAY = 255.0


def frame_seed(*parts):
    key = "|".join(str(p) for p in parts)
    return (BASE_SEED ^ zlib.crc32(key.encode())) & 0xFFFFFFFF


def label(ratio):
    if ratio >= 6.0:
        return "readable"
    if ratio <= 3.0:
        return "unreadable"
    return "grey"


def make_bg(bg_type, theme_cfg, h, w, rng):
    if bg_type == "flat":
        return make_flat_bg(h, w, theme_cfg["flat"])
    if bg_type == "gradient":
        lo, hi = theme_cfg["grad"]
        return make_gradient_bg(h, w, lo, hi)
    if bg_type == "noisy":
        return make_noisy_bg(h, w, theme_cfg["noisy_base"], NOISY_STD, rng)
    raise ValueError(bg_type)


def local_bg_gray(bg_type, theme_cfg):
    """Gray value the contrast ratio is authored against — the LOCAL value
    under the label (gradient/noisy vary spatially; we author against their
    representative/base level, same convention a real compositor would use
    picking a token pair against a nominal background)."""
    if bg_type == "flat":
        return theme_cfg["flat"]
    if bg_type == "gradient":
        lo, hi = theme_cfg["grad"]
        return (lo + hi) / 2.0
    if bg_type == "noisy":
        return theme_cfg["noisy_base"]


def build_single_label_frame(theme, bg_type, size_cat, ratio, h=120, w=300, text="Speed 42"):
    theme_cfg = THEMES[theme]
    seed = frame_seed("single", theme, bg_type, size_cat, ratio)
    rng = np.random.default_rng(seed)
    base = make_bg(bg_type, theme_cfg, h, w, rng)
    bg_gray = local_bg_gray(bg_type, theme_cfg)
    g = solve_text_gray(bg_gray, ratio, text_darker=theme_cfg["text_darker"])
    actual_ratio = actual_contrast_ratio(g, bg_gray)
    alpha = render_text_alpha(text, size_cat)
    x, y = 20, max(10, (h - alpha.shape[0]) // 2)
    composite_alpha(base, alpha, x, y, g)
    mask = ink_mask_full_frame(base.shape, alpha, x, y)
    return base, mask, actual_ratio


def score_frame(base, mask, k=K_OPERATING):
    deg = veiling_glare(base, k=k, veil_gray=VEIL_GRAY)
    cum = cumulative_score(base, deg)
    reg = per_region_scores(base, deg, [mask])[0]
    return cum, reg, deg


# ---------------------------------------------------------------------------
# 1. SEPARATION + THRESHOLD STABILITY sweep
# ---------------------------------------------------------------------------

def run_sweep():
    rows = []
    for theme in THEMES:
        for bg_type in ["flat", "gradient", "noisy"]:
            for size_cat in SIZES:
                for ratio in RATIOS:
                    base, mask, actual_ratio = build_single_label_frame(theme, bg_type, size_cat, ratio)
                    cum, reg, _ = score_frame(base, mask, K_OPERATING)
                    rows.append(dict(
                        theme=theme, bg=bg_type, size=size_cat,
                        ratio=ratio, actual_ratio=round(actual_ratio, 3),
                        cls=label(ratio),
                        cumulative=cum, region=reg,
                    ))
    return rows


# ---------------------------------------------------------------------------
# 2. FLAKINESS
# ---------------------------------------------------------------------------

def run_flakiness():
    out = {}

    # (a) bit-exact regen: same config generated twice -> identical arrays + identical score
    base1, mask1, _ = build_single_label_frame("dark", "flat", "medium", 6.0)
    base2, mask2, _ = build_single_label_frame("dark", "flat", "medium", 6.0)
    frames_identical = bool(np.array_equal(base1, base2)) and bool(np.array_equal(mask1, mask2))
    cum1, reg1, _ = score_frame(base1, mask1)
    cum2, reg2, _ = score_frame(base2, mask2)
    out["regen_bytes_identical"] = frames_identical
    out["regen_score_identical"] = (cum1 == cum2) and (reg1 == reg2)
    out["regen_score_delta"] = dict(cumulative=abs(cum1 - cum2), region=abs(reg1 - reg2))

    # (a2) metric re-run on the SAME in-memory arrays twice -> bit-stable?
    cum1b, reg1b, _ = score_frame(base1, mask1)
    out["rerun_same_array_identical"] = (cum1 == cum1b) and (reg1 == reg1b)

    # (b) seed-to-seed variance for a noisy background at FIXED authored content
    # (same ratio/size/theme, 8 independent noise draws -- models re-rendering
    # the same UI frame against different live-video noise instances)
    variants = []
    theme_cfg = THEMES["dark"]
    for trial in range(8):
        seed = frame_seed("flake-noisy-variance", trial)
        rng = np.random.default_rng(seed)
        base = make_bg("noisy", theme_cfg, 120, 300, rng)
        g = solve_text_gray(theme_cfg["noisy_base"], 6.0, text_darker=False)
        alpha = render_text_alpha("Speed 42", "medium")
        x, y = 20, 40
        composite_alpha(base, alpha, x, y, g)
        mask = ink_mask_full_frame(base.shape, alpha, x, y)
        cum, reg, _ = score_frame(base, mask)
        variants.append(dict(cumulative=cum, region=reg))
    cums = [v["cumulative"] for v in variants]
    regs = [v["region"] for v in variants]
    out["noisy_seed_variance_ratio6"] = dict(
        cumulative=dict(min=min(cums), max=max(cums), spread=max(cums) - min(cums), std=float(np.std(cums))),
        region=dict(min=min(regs), max=max(regs), spread=max(regs) - min(regs), std=float(np.std(regs))),
    )

    # (c) nuisance sensitivity vs real-content-loss sensitivity
    # baseline configs: a clearly-readable (ratio=21) and at-floor (ratio=6) label,
    # dark theme, flat bg, medium size.
    nuisance_rows = []
    for base_ratio in [21.0, 6.0]:
        theme_cfg = THEMES["dark"]
        seed = frame_seed("nuisance-base", base_ratio)
        rng = np.random.default_rng(seed)
        h, w = 120, 300
        clean = make_bg("flat", theme_cfg, h, w, rng)
        g = solve_text_gray(theme_cfg["flat"], base_ratio, text_darker=False)
        alpha = render_text_alpha("Speed 42", "medium")
        x, y = 20, 40
        composite_alpha(clean, alpha, x, y, g)
        mask = ink_mask_full_frame(clean.shape, alpha, x, y)
        cum0, reg0, _ = score_frame(clean, mask)

        def variant(name, frame, m=mask):
            cum, reg, _ = score_frame(frame, m)
            return dict(name=name, cumulative=cum, region=reg,
                        d_cumulative=cum - cum0, d_region=reg - reg0)

        # shadow
        f_shadow = clean.copy()
        add_drop_shadow(f_shadow, alpha, x, y, shadow_gray=0.0, dx=3, dy=3, blur_sigma=2.0, strength=0.5)
        nuisance_rows.append(variant("shadow", f_shadow))

        # glow (light theme convention: glow brighter than bg; here dark theme -> glow near-white)
        f_glow = clean.copy()
        add_glow(f_glow, alpha, x, y, glow_gray=255.0, blur_sigma=3.0, strength=0.35)
        nuisance_rows.append(variant("glow", f_glow))

        # 1px shift (mask must shift WITH the frame — same physical label, shifted)
        f_shift = shift_1px(clean, dx=1, dy=0)
        m_shift = shift_1px(mask, dx=1, dy=0)
        nuisance_rows.append(variant("shift_1px", f_shift, m_shift))

        # mild additive noise
        seed_n = frame_seed("nuisance-noise", base_ratio)
        rng_n = np.random.default_rng(seed_n)
        f_noise = add_mild_noise(clean, std=3.0, rng=rng_n)
        nuisance_rows.append(variant("mild_noise", f_noise))

        for r in nuisance_rows[-4:]:
            r["base_ratio"] = base_ratio
            r["baseline_cumulative"] = cum0
            r["baseline_region"] = reg0

    # real-content-loss deltas for comparison: ratio 21->6 and 6->3 at same theme/bg/size
    theme_cfg = THEMES["dark"]
    base21, mask21, _ = build_single_label_frame("dark", "flat", "medium", 21.0)
    base6, mask6, _ = build_single_label_frame("dark", "flat", "medium", 6.0)
    base3, mask3, _ = build_single_label_frame("dark", "flat", "medium", 3.0)
    c21, r21, _ = score_frame(base21, mask21)
    c6, r6, _ = score_frame(base6, mask6)
    c3, r3, _ = score_frame(base3, mask3)
    out["content_loss_delta_21_to_6"] = dict(cumulative=c6 - c21, region=r6 - r21)
    out["content_loss_delta_6_to_3"] = dict(cumulative=c3 - c6, region=r3 - r6)
    out["nuisance_rows"] = nuisance_rows

    return out


# ---------------------------------------------------------------------------
# 3. THE MASKING TEST — cumulative vs per-region on a planted low-contrast
#    label inside an otherwise-clean multi-label frame.
# ---------------------------------------------------------------------------

def build_multi_label_canvas(bg_type, theme, planted_indices, good_ratio=12.0, bad_ratio=2.0,
                              rows=5, cols=6, W=1920, H=1080):
    """planted_indices: iterable of cell indices to render at bad_ratio;
    everything else renders at good_ratio. Same background RNG draw for a
    given (bg_type, theme, rows, cols, W, H) regardless of which indices are
    planted, so benign vs planted canvases differ ONLY in the planted
    label(s) — isolating the effect being measured."""
    planted_indices = set(planted_indices)
    theme_cfg = THEMES[theme]
    seed = frame_seed("multi", bg_type, theme, rows, cols, W, H)
    rng = np.random.default_rng(seed)
    base = make_bg(bg_type, theme_cfg, H, W, rng)
    bg_gray = local_bg_gray(bg_type, theme_cfg)

    cell_w, cell_h = W // cols, H // rows
    masks = []
    idx = 0
    for r in range(rows):
        for c in range(cols):
            ratio = bad_ratio if idx in planted_indices else good_ratio
            g = solve_text_gray(bg_gray, ratio, text_darker=theme_cfg["text_darker"])
            alpha = render_text_alpha(f"LBL-{idx:02d}", "medium")
            x = c * cell_w + 24
            y = r * cell_h + (cell_h - alpha.shape[0]) // 2
            composite_alpha(base, alpha, x, y, g)
            masks.append(ink_mask_full_frame(base.shape, alpha, x, y))
            idx += 1
    return base, masks


def run_masking_test():
    out = {}
    n_labels = 30  # rows*cols below
    for bg_type in ["flat", "noisy"]:
        for theme in ["dark"]:
            planted_idx = 14  # roughly center of the 5x6 grid
            benign, benign_masks = build_multi_label_canvas(bg_type, theme, planted_indices=[])
            planted, planted_masks = build_multi_label_canvas(bg_type, theme, planted_indices=[planted_idx])

            deg_b = veiling_glare(benign, k=K_OPERATING, veil_gray=VEIL_GRAY)
            b_cum = cumulative_score(benign, deg_b)
            b_worst, b_all = worst_tile_score(benign, deg_b, benign_masks)

            deg_p = veiling_glare(planted, k=K_OPERATING, veil_gray=VEIL_GRAY)
            p_cum = cumulative_score(planted, deg_p)
            p_worst, p_all = worst_tile_score(planted, deg_p, planted_masks)
            p_planted_region = p_all[planted_idx]

            out[f"{theme}_{bg_type}"] = dict(
                n_labels=n_labels,
                benign_cumulative=b_cum,
                planted_cumulative=p_cum,
                cumulative_delta=p_cum - b_cum,
                benign_worst_tile=b_worst,
                planted_worst_tile=p_worst,
                planted_label_own_region_score=p_planted_region,
                worst_tile_delta=p_worst - b_worst,
            )
    return out


def run_dilution_test(bg_type="flat", theme="dark"):
    """How many of the 30 labels must go unreadable before the CUMULATIVE
    score crosses the threshold that a SINGLE bad label crosses instantly
    under worst-tile scoring? Quantifies "how badly" cumulative masks."""
    counts = [0, 1, 2, 3, 5, 8, 12, 16, 20, 24, 28, 30]
    rows = []
    for n_bad in counts:
        planted = list(range(n_bad))  # first n_bad cells go bad, deterministic
        frame, masks = build_multi_label_canvas(bg_type, theme, planted_indices=planted)
        deg = veiling_glare(frame, k=K_OPERATING, veil_gray=VEIL_GRAY)
        cum = cumulative_score(frame, deg)
        worst, _ = worst_tile_score(frame, deg, masks)
        rows.append(dict(n_bad=n_bad, frac_bad=n_bad / 30, cumulative=cum, worst_tile=worst))
    return rows


# ---------------------------------------------------------------------------
# 4. THRESHOLD ANALYSIS + FALSE RATES on the sweep
# ---------------------------------------------------------------------------

def analyze_threshold(rows, score_key):
    readable = [r[score_key] for r in rows if r["cls"] == "readable" and r[score_key] is not None]
    unreadable = [r[score_key] for r in rows if r["cls"] == "unreadable" and r[score_key] is not None]
    if not readable or not unreadable:
        return dict(error="insufficient data")
    r_lo, r_hi = min(readable), max(readable)
    u_lo, u_hi = min(unreadable), max(unreadable)
    gap = u_lo - r_hi  # positive => clean global separation with a free-floating threshold
    # sweep candidate thresholds, pick the one minimizing FP+FN
    candidates = sorted(set(readable + unreadable))
    best = None
    for t in candidates:
        fp = sum(1 for s in readable if s >= t)     # readable wrongly flagged unreadable
        fn = sum(1 for s in unreadable if s < t)     # unreadable wrongly passed as readable
        total_err = fp + fn
        if best is None or total_err < best[0]:
            best = (total_err, t, fp, fn)
    total_err, best_t, fp, fn = best
    return dict(
        readable_range=(r_lo, r_hi), unreadable_range=(u_lo, u_hi), gap=gap,
        n_readable=len(readable), n_unreadable=len(unreadable),
        best_threshold=best_t, fp=fp, fn=fn,
        fp_rate=fp / len(readable), fn_rate=fn / len(unreadable),
    )


def analyze_threshold_per_slice(rows, score_key):
    """Per (theme,bg) slice — checks whether ONE threshold could hold, by
    reporting each slice's own best split point; large spread across
    slices == no single stable threshold."""
    slices = {}
    for r in rows:
        key = (r["theme"], r["bg"])
        slices.setdefault(key, []).append(r)
    out = {}
    for key, sub in slices.items():
        out["_".join(key)] = analyze_threshold(sub, score_key)
    return out


# ---------------------------------------------------------------------------
def main():
    t0 = time.time()
    print("=== V6: per-region (tiled/worst-tile) vs cumulative edge-drift ===")
    print(f"EDGE_THRESH={EDGE_THRESH}  K_OPERATING={K_OPERATING}  veil_gray={VEIL_GRAY}")

    print("\n--- running sweep (theme x bg x size x ratio) ---")
    rows = run_sweep()
    print(f"{len(rows)} frames scored")

    print("\n--- flakiness ---")
    flake = run_flakiness()

    print("\n--- masking test (planted single low-contrast label) ---")
    masking = run_masking_test()

    print("\n--- dilution test (how many bad labels to move cumulative) ---")
    dilution = run_dilution_test()
    for r in dilution:
        print(r)

    print("\n--- threshold analysis: CUMULATIVE ---")
    th_cum = analyze_threshold(rows, "cumulative")
    print(json.dumps(th_cum, indent=2, default=str))
    th_cum_slices = analyze_threshold_per_slice(rows, "cumulative")

    print("\n--- threshold analysis: REGION (== worst-tile for single-label frames) ---")
    th_reg = analyze_threshold(rows, "region")
    print(json.dumps(th_reg, indent=2, default=str))
    th_reg_slices = analyze_threshold_per_slice(rows, "region")

    result = dict(
        base_seed=BASE_SEED, edge_thresh=EDGE_THRESH, k_operating=K_OPERATING,
        veil_gray=VEIL_GRAY, ratios=RATIOS, sizes=SIZES,
        sweep_rows=rows,
        flakiness=flake,
        masking_test=masking,
        dilution_test=dilution,
        threshold_cumulative=th_cum,
        threshold_cumulative_per_slice=th_cum_slices,
        threshold_region=th_reg,
        threshold_region_per_slice=th_reg_slices,
        elapsed_s=time.time() - t0,
    )
    with open(OUT, "w") as f:
        json.dump(result, f, indent=2, default=str)
    print(f"\nwrote {OUT}  ({time.time()-t0:.1f}s)")


if __name__ == "__main__":
    main()
