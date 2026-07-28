"""
V6 bake-off entry: PER-REGION (TILED) SCORING vs CUMULATIVE.

Core pixel-metric shape (the operator's revised idea): render each frame,
apply a veiling-glare degradation (self-referential — same frame, before vs
after), edge-detect BOTH as a bitmap (Sobel magnitude, absolute-thresholded
-> boolean bitmask, i.e. the "clamp + reduce to bitmap" the operator asked
about), and score by how much edge signal is LOST between the two ("drift").

V6's own axis: compute that identical score two ways —
  - CUMULATIVE: one loss fraction over the whole frame's edge population.
  - PER-REGION: one loss fraction per known label bbox; report the WORST
    (max) region score for the frame.
and measure whether cumulative masks a single vanished label that per-region
catches.

Deterministic: every RNG draw goes through a seeded np.random.Generator
passed in explicitly; nothing reads global random state or wall clock.
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

FONT_BOLD = "/usr/local/lib/python3.12/dist-packages/matplotlib/mpl-data/fonts/ttf/DejaVuSans-Bold.ttf"
FONT_REG = "/usr/local/lib/python3.12/dist-packages/matplotlib/mpl-data/fonts/ttf/DejaVuSans.ttf"

# ---------------------------------------------------------------------------
# WCAG relative luminance (sRGB gray channel, since all bg/text here is
# achromatic: R=G=B so the 0.2126/0.7152/0.0722 weighted sum collapses to
# the single linearized channel value).
# ---------------------------------------------------------------------------

def srgb_to_lin(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(L):
    L = np.clip(L, 0.0, 1.0)
    return np.where(L <= 0.0031308, L * 12.92, 1.055 * np.power(L, 1.0 / 2.4) - 0.055)


def gray_luminance(g):
    """g in 0..255 -> WCAG relative luminance in 0..1."""
    return float(srgb_to_lin(np.asarray(g, dtype=np.float64) / 255.0))


def solve_text_gray(bg_gray, ratio, text_darker):
    """Solve for the gray value that makes contrast(text, bg) == ratio,
    per the WCAG formula (L1+0.05)/(L2+0.05), text on the darker or
    lighter side of bg as requested. Clips to [0,255] gray range —
    caller should re-derive the ACTUAL achieved ratio from the returned
    gray (extreme ratios like 21:1 may not be exactly reachable and we
    never want a nominal number to silently diverge from what got drawn)."""
    L_bg = gray_luminance(bg_gray)
    if text_darker:
        L_text = (L_bg + 0.05) / ratio - 0.05
    else:
        L_text = ratio * (L_bg + 0.05) - 0.05
    L_text = float(np.clip(L_text, 0.0, 1.0))
    g = float(lin_to_srgb(np.array(L_text)) * 255.0)
    return float(np.clip(round(g), 0, 255))


def actual_contrast_ratio(gray_a, gray_b):
    La, Lb = gray_luminance(gray_a), gray_luminance(gray_b)
    hi, lo = max(La, Lb), min(La, Lb)
    return (hi + 0.05) / (lo + 0.05)


# ---------------------------------------------------------------------------
# Backgrounds
# ---------------------------------------------------------------------------

def make_flat_bg(h, w, gray):
    return np.full((h, w), float(gray), dtype=np.float32)


def make_gradient_bg(h, w, g_lo, g_hi):
    ramp = np.linspace(g_lo, g_hi, w, dtype=np.float32)
    return np.tile(ramp, (h, 1))


def make_noisy_bg(h, w, base_gray, std, rng):
    """Stands in for live day/thermal video texture: correlated (mildly
    blurred) gaussian noise around a base gray, not pure per-pixel static."""
    noise = rng.normal(0, std, size=(h, w)).astype(np.float32)
    noise = ndimage.gaussian_filter(noise, sigma=1.2)
    return np.clip(base_gray + noise, 0, 255).astype(np.float32)


# ---------------------------------------------------------------------------
# Text rendering (real glyphs via PIL/TrueType, anti-aliased alpha mask)
# ---------------------------------------------------------------------------

SIZE_CATALOG = {
    # name: (font_path, pt_size, bold-ish visual weight)
    "large_bold": (FONT_BOLD, 40),
    "medium": (FONT_BOLD, 24),
    "small_thin": (FONT_REG, 14),  # thin strokes + small: hardest legibility case
}

_FONT_CACHE = {}


def get_font(size_cat):
    key = SIZE_CATALOG[size_cat]
    if key not in _FONT_CACHE:
        path, pt = key
        _FONT_CACHE[key] = ImageFont.truetype(path, pt)
    return _FONT_CACHE[key]


def render_text_alpha(text, size_cat, pad=6):
    font = get_font(size_cat)
    tmp = Image.new("L", (1, 1), 0)
    d = ImageDraw.Draw(tmp)
    bbox = d.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0] + 2 * pad
    th = bbox[3] - bbox[1] + 2 * pad
    img = Image.new("L", (tw, th), 0)
    d = ImageDraw.Draw(img)
    d.text((pad - bbox[0], pad - bbox[1]), text, font=font, fill=255)
    alpha = np.asarray(img).astype(np.float32) / 255.0
    return alpha  # (th, tw) in [0,1]


def composite_alpha(bg, alpha, x, y, gray_value):
    th, tw = alpha.shape
    region = bg[y:y + th, x:x + tw]
    bg[y:y + th, x:x + tw] = region * (1.0 - alpha) + gray_value * alpha
    return (x, y, tw, th)


INK_ALPHA_THRESH = 0.15
INK_DILATE_PX = 2


def ink_mask_full_frame(frame_shape, alpha, x, y, dilate_px=INK_DILATE_PX):
    """Boolean mask, same shape as the full frame, true where actual glyph
    ink (+ a small dilation for anti-aliased edge halo) sits — as opposed
    to the padded rectangular bbox, which also contains plain background.
    This is what per-region scoring uses: scoring only pixels that are
    ACTUALLY part of the label keeps a noisy/video background's own edges
    (which also gain/lose Sobel response under degradation) out of the
    text-legibility measurement. Mirrors the per-uid visible-pixel idea
    already planned for the UPSTREAM's presentation layer — "this repo" in
    the original meant that repo, not this one, which has no such layer."""
    th, tw = alpha.shape
    full = np.zeros(frame_shape, dtype=bool)
    full[y:y + th, x:x + tw] = alpha > INK_ALPHA_THRESH
    if dilate_px > 0:
        full = ndimage.binary_dilation(full, iterations=dilate_px)
    return full


# ---------------------------------------------------------------------------
# Nuisances — must NOT trip the gate
# ---------------------------------------------------------------------------

def add_drop_shadow(bg, alpha, x, y, shadow_gray, dx=3, dy=3, blur_sigma=2.0, strength=0.5):
    th, tw = alpha.shape
    canvas = np.zeros_like(bg)
    sx0, sy0 = x + dx, y + dy
    sx1, sy1 = sx0 + tw, sy0 + th
    H, W = bg.shape
    cx0, cy0 = max(sx0, 0), max(sy0, 0)
    cx1, cy1 = min(sx1, W), min(sy1, H)
    if cx1 > cx0 and cy1 > cy0:
        ax0, ay0 = cx0 - sx0, cy0 - sy0
        ax1, ay1 = ax0 + (cx1 - cx0), ay0 + (cy1 - cy0)
        canvas[cy0:cy1, cx0:cx1] = alpha[ay0:ay1, ax0:ax1]
    canvas = ndimage.gaussian_filter(canvas, sigma=blur_sigma) * strength
    bg[:] = bg * (1 - canvas) + shadow_gray * canvas
    return bg


def add_glow(bg, alpha, x, y, glow_gray, blur_sigma=3.0, strength=0.6):
    th, tw = alpha.shape
    canvas = np.zeros_like(bg)
    canvas[y:y + th, x:x + tw] = alpha
    canvas = ndimage.gaussian_filter(canvas, sigma=blur_sigma) * strength
    bg[:] = bg * (1 - canvas) + glow_gray * canvas
    return bg


def shift_1px(frame, dx=1, dy=0):
    return np.roll(np.roll(frame, dy, axis=0), dx, axis=1)


def add_mild_noise(frame, std, rng):
    return np.clip(frame + rng.normal(0, std, size=frame.shape), 0, 255).astype(np.float32)


# ---------------------------------------------------------------------------
# Degradation: veiling-glare washout (+ optional blur), applied identically
# for every variant in the bake-off.
# ---------------------------------------------------------------------------

def veiling_glare(frame, k, veil_gray=235.0, blur_sigma=0.0):
    out = frame * (1.0 - k) + veil_gray * k
    if blur_sigma > 0:
        out = ndimage.gaussian_filter(out, sigma=blur_sigma)
    return out


# ---------------------------------------------------------------------------
# The metric: edge-bitmap drift, cumulative vs per-region.
# ---------------------------------------------------------------------------

EDGE_THRESH = 40.0  # absolute Sobel-magnitude threshold ("clamp to bitmap")


def edge_bitmap(gray_frame, thresh=EDGE_THRESH):
    gx = ndimage.sobel(gray_frame, axis=1)
    gy = ndimage.sobel(gray_frame, axis=0)
    mag = np.hypot(gx, gy)
    return mag > thresh


def loss_fraction(edge_orig, edge_deg, region=None, min_edge_pixels=8):
    """1 - (surviving edge pixels / original edge pixels) over `region`
    (a boolean mask, a (slice,slice) tuple, or None for the whole frame).
    Returns None if the region had too little edge content to judge
    (blank background — nothing there to lose)."""
    if region is None:
        o, d = edge_orig, edge_deg
    else:
        o, d = edge_orig[region], edge_deg[region]
    orig_count = int(o.sum())
    if orig_count < min_edge_pixels:
        return None
    surviving = int((o & d).sum())
    return 1.0 - (surviving / orig_count)


def cumulative_score(frame_orig, frame_deg, thresh=EDGE_THRESH):
    """The naive baseline this variant argues AGAINST: one loss fraction
    over every edge pixel in the whole frame."""
    eo = edge_bitmap(frame_orig, thresh)
    ed = edge_bitmap(frame_deg, thresh)
    return loss_fraction(eo, ed, None)


def per_region_scores(frame_orig, frame_deg, regions, thresh=EDGE_THRESH):
    """`regions`: list of boolean masks (preferred — tight glyph-ink
    footprint, see ink_mask_full_frame) or (slice,slice) bbox tuples."""
    eo = edge_bitmap(frame_orig, thresh)
    ed = edge_bitmap(frame_deg, thresh)
    return [loss_fraction(eo, ed, r) for r in regions]


def worst_tile_score(frame_orig, frame_deg, regions, thresh=EDGE_THRESH):
    """V6's own metric: the max (worst) per-region loss fraction — a
    single vanished label must not be averaged away by clean neighbors."""
    scores = per_region_scores(frame_orig, frame_deg, regions, thresh)
    valid = [s for s in scores if s is not None]
    if not valid:
        return None, scores
    return max(valid), scores
