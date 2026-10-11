#!/usr/bin/env python3
"""deck_qa - automated visual-defect detection for a single-file HTML slide deck.

Drives the deck in headless Chromium exactly as a reader does (window.show / #N),
waits for it to settle, and runs twelve detectors per slide.  Writes one PNG per
slide, a numbered contact sheet, report.json and report.md into --out, and
nothing anywhere else.

  python3 deck_qa.py --html deck.html --out out/deck [--only 3,7,29] [--strict]

Detectors, and the one-line reason each exists:

  D1  overflow      content does not fit the slide            (cropped content)
  D2  text-overlap  two text runs' GLYPH rects intersect      (element collision)
  D3  truncation    the browser silently deleted text         (abnormal ellipsis)
  D4  occlusion     something is painted on top of text       (container overlap)
  D5  small-text    unreadable at presentation distance
  D6  font-fallback the inlined @font-face did not apply
  D7  images        decode failures, upscaling, duplicate bytes
  D8  video         playable / broken / codec-unsupported-HERE  (see --- codec ---)
  D9  contrast      WCAG ratio over the ACTUAL rendered background
  D10 external      the deck reached outside itself (route + offline + static)
  D11 console       errors, uncaught exceptions, CSP violations
  D12 offscreen     an element sits entirely outside the viewport

Per-slide opt-outs are data-* attributes on the <section class="slide">:
  data-qa-scroll          this slide is meant to scroll   -> D1 y-overflow to info
  data-qa-allow-overlap   -> D2 to info      data-qa-allow-occlusion -> D4 to info
  data-qa-min-font="10"   -> D5 threshold for this slide
  data-qa-skip="d2,d9"    -> those detectors off for this slide
"""

import os
import re
import sys
import json
import glob
import time
import hashlib
import argparse
import datetime
from playwright.sync_api import sync_playwright
from PIL import Image, ImageDraw, ImageFont, ImageChops

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deck_qa_js as J


# =============================================================== WCAG colour math
def _lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


_LIN = [_lin(i) for i in range(256)]


def rel_lum(rgb):
    """WCAG 2.x relative luminance of an 8-bit sRGB triple."""
    r, g, b = rgb[:3]
    return 0.2126 * _LIN[r] + 0.7152 * _LIN[g] + 0.0722 * _LIN[b]


def contrast(rgb1, rgb2):
    a, b = rel_lum(rgb1), rel_lum(rgb2)
    if a < b:
        a, b = b, a
    return (a + 0.05) / (b + 0.05)


_RGB = re.compile(r"rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,/\s]+([\d.%]+))?")


def parse_color(s):
    """'rgb(24, 34, 48)' / 'rgba(…, .5)' -> ((r,g,b), alpha).  None if unparseable."""
    m = _RGB.search(s or "")
    if not m:
        return None
    r, g, b = (int(round(float(m.group(i)))) for i in (1, 2, 3))
    a = m.group(4)
    if a is None:
        alpha = 1.0
    elif a.endswith("%"):
        alpha = float(a[:-1]) / 100.0
    else:
        alpha = float(a)
    return (max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b))), alpha


def over(fg, alpha, bg):
    """Source-over composite of a translucent fg on an opaque bg."""
    if alpha >= 0.999:
        return fg
    return tuple(int(round(fg[i] * alpha + bg[i] * (1 - alpha))) for i in range(3))


def wcag_threshold(font_px, weight):
    """WCAG 1.4.3: large text is >=18pt (24px), or >=14pt (18.66px) when bold."""
    try:
        w = int(weight)
    except (TypeError, ValueError):
        w = 700 if str(weight).lower() in ("bold", "bolder") else 400
    large = font_px >= 24.0 or (font_px >= 18.66 and w >= 700)
    return (3.0, "large") if large else (4.5, "normal")


# =============================================================== findings
SEV_ORDER = {"error": 0, "warn": 1, "info": 2}


def F(det, sev, msg, **kw):
    d = {"detector": det, "severity": sev, "message": msg}
    d.update(kw)
    return d


# =============================================================== D10 static scan
# Streamed, because the flowviz deck is 141 MB and must never be slurped.
_PATS = [
    ("src", re.compile(rb"""\bsrc\s*=\s*["']([^"']{0,300})""", re.I)),
    ("href", re.compile(rb"""\bhref\s*=\s*["']([^"']{0,300})""", re.I)),
    ("poster", re.compile(rb"""\bposter\s*=\s*["']([^"']{0,300})""", re.I)),
    ("srcset", re.compile(rb"""\bsrcset\s*=\s*["']([^"']{0,600})""", re.I)),
    ("data-src", re.compile(rb"""\bdata-(?:src|href|url)\s*=\s*["']([^"']{0,300})""", re.I)),
    ("css-url", re.compile(rb"""\burl\(\s*["']?([^)"']{0,300})""", re.I)),
    ("@import", re.compile(rb"""@import\s+(?:url\()?\s*["']([^"']{0,300})""", re.I)),
]
_API = re.compile(
    rb"\b(fetch|XMLHttpRequest|importScripts|EventSource|WebSocket|"
    rb"sendBeacon|navigator\.sendBeacon|navigator\.geolocation)\s*\("
)
_EXTERNAL = re.compile(rb"^(?:https?:|ftp:|ws{1,2}:|//)", re.I)
_INLINE_OK = re.compile(rb"^(?:data:|blob:|about:|javascript:|#|mailto:|tel:)", re.I)

_DATA_URI = re.compile(rb"(data:[a-z]+/[a-z0-9.+-]+;base64,)([A-Za-z0-9+/=]+)")


def duplicate_data_uris(path, min_bytes=4096):
    """Every base64 payload of at least `min_bytes` decoded bytes that occurs more
    than once in the FILE: [{bytes, occurrences, src_head}], biggest first.
    Reads the whole file (a deck is at most a few hundred MB) because a payload
    routinely spans the chunk boundary the streaming scan uses."""
    with open(path, "rb") as fh:
        blob = fh.read()
    seen = {}
    for m in _DATA_URI.finditer(blob):
        payload = m.group(2)
        if len(payload) * 3 // 4 < min_bytes:
            continue
        k = hashlib.sha256(payload).hexdigest()
        e = seen.setdefault(
            k,
            {
                "bytes": len(payload) * 3 // 4,
                "occurrences": 0,
                "src_head": (m.group(1) + payload[:40]).decode("ascii"),
            },
        )
        e["occurrences"] += 1
    return sorted((e for e in seen.values() if e["occurrences"] > 1), key=lambda d: -d["bytes"])


def static_scan(path, chunk=4 << 20, overlap=2048):
    """Every URL-bearing attribute / CSS function in the file, classified."""
    counts, samples, api = {}, [], {}
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        tail = b""
        while True:
            buf = f.read(chunk)
            if not buf:
                break
            blob = tail + buf
            for kind, pat in _PATS:
                for m in pat.finditer(blob):
                    v = m.group(1).strip()
                    if not v:
                        continue
                    if _INLINE_OK.match(v):
                        cls = "inline"
                    elif _EXTERNAL.match(v):
                        cls = "EXTERNAL"
                    else:
                        cls = "RELATIVE"  # needs a sibling file -> not self-contained
                    key = f"{kind}:{cls}"
                    counts[key] = counts.get(key, 0) + 1
                    if cls != "inline" and len(samples) < 40:
                        samples.append(
                            {"kind": kind, "class": cls, "value": v[:200].decode("utf8", "replace")}
                        )
            for m in _API.finditer(blob):
                k = m.group(1).decode("ascii", "replace")
                api[k] = api.get(k, 0) + 1
            tail = blob[-overlap:]
    external = sum(v for k, v in counts.items() if k.endswith(":EXTERNAL"))
    relative = sum(v for k, v in counts.items() if k.endswith(":RELATIVE"))
    return {
        "bytes": size,
        "counts": counts,
        "network_apis": api,
        "external_refs": external,
        "relative_refs": relative,
        "samples": samples,
    }


# =============================================================== D9 contrast, host side
SENTINEL = (1, 2, 3)


def ink_mask(shot, plate, thresh=10):
    """Binary mask of the pixels the GLYPHS painted: where shot and plate differ.

    plate is shot re-rendered with every text fill forced transparent, so layout
    is identical and the only difference is ink.  This is what lets the contrast
    check see the real background - including a photo or a gradient, the case
    axe-core declines to evaluate and returns as `incomplete`.
    """
    d = ImageChops.difference(shot.convert("RGB"), plate.convert("RGB")).convert("L")
    return d.point(lambda v: 255 if v >= thresh else 0)


def bg_colors_under(plate, mask, box, max_colors=4096, quant=24):
    """Distinct background colours beneath the ink inside `box`, with counts."""
    x, y, w, h = box["x"], box["y"], box["w"], box["h"]
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(plate.width, x + w), min(plate.height, y + h)
    if x1 - x0 < 2 or y1 - y0 < 2:
        return [], 0
    crop = (x0, y0, x1, y1)
    m = mask.crop(crop)
    ink = m.histogram()[255]
    if ink < 3:
        return [], ink
    sent = Image.new("RGB", m.size, SENTINEL)
    masked = Image.composite(plate.crop(crop), sent, m)
    cols = masked.getcolors(max_colors)
    if cols is None:  # text over a photo/gradient
        masked = masked.quantize(colors=quant, method=Image.MEDIANCUT).convert("RGB")
        cols = masked.getcolors(max_colors) or []
    return [(n, c) for n, c in cols if c != SENTINEL], ink


def visible_boxes(rects, w, h):
    """The parts of `rects` that are actually inside the viewport, clipped.

    A run below the fold of a scrolling slide has no pixels at all; asking what
    contrast it has is asking about ink that was never painted."""
    out = []
    for b in rects or []:
        x0, y0 = max(0, b["x"]), max(0, b["y"])
        x1, y1 = min(w, b["x"] + b["w"]), min(h, b["y"] + b["h"])
        if x1 - x0 >= 2 and y1 - y0 >= 2:
            out.append({"x": x0, "y": y0, "w": x1 - x0, "h": y1 - y0})
    return out


def contrast_from_plates(runs, shot, plate, min_ink=8):
    """Per text run: the WORST WCAG ratio against any background pixel it sits on."""
    mask = ink_mask(shot, plate)
    W, H = plate.size
    out = []
    for r in runs:
        fgp = parse_color(r.get("color"))
        if not fgp:
            continue
        vis = visible_boxes(r.get("rects"), W, H)
        if not vis:
            continue  # nothing of this run is on screen
        fg, fa = fgp
        fa *= float(r.get("opacity") or 1.0)
        worst = None
        tot_ink = 0
        dominant = None
        for box in vis:
            cols, ink = bg_colors_under(plate, mask, box)
            tot_ink += ink
            if not cols:
                continue
            cols.sort(reverse=True)
            if dominant is None:
                dominant = cols[0][1]
            for _n, bg in cols:
                eff = over(fg, fa, bg)
                c = contrast(eff, bg)
                if worst is None or c < worst[0]:
                    worst = (c, bg, eff)
        thr, klass = wcag_threshold(r.get("font_px") or 0, r.get("font_weight"))
        rec = {
            "text": r["text"],
            "sel": r["sel"],
            "svg": bool(r.get("svg")),
            "font_px": r.get("font_px"),
            "font_weight": r.get("font_weight"),
            "size_class": klass,
            "threshold": thr,
            "fg": list(fg),
            "fg_alpha": round(fa, 3),
            "ink_px": tot_ink,
            "css_bg": r.get("css_bg"),
            "box": vis[0],
        }
        glyphs = len((r.get("text") or "").strip())
        if tot_ink < min_ink and glyphs >= 3:
            # ink below the floor: the glyphs painted (almost) nothing.  That is a
            # finding in its own right - invisible text - not a contrast number.
            rec.update({"ratio": None, "verdict": "no-ink"})
        elif worst is None:
            rec.update({"ratio": None, "verdict": "no-bg-sample"})
        elif tot_ink < min_ink:
            rec.update({"ratio": None, "verdict": "too-little-ink"})
        else:
            c, bg, eff = worst
            rec.update(
                {
                    "ratio": round(c, 2),
                    "bg": list(bg),
                    "fg_effective": list(eff),
                    "bg_dominant": list(dominant) if dominant else None,
                    "verdict": "fail" if c < thr else "pass",
                }
            )
        out.append(rec)
    return out


# =============================================================== contact sheet
def a_font(size):
    for p in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
    ):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


SEV_RGB = {
    "error": (214, 74, 82),
    "warn": (224, 162, 58),
    "info": (96, 126, 160),
    None: (44, 54, 68),
}


def contact_sheet(out, cols, thumb_w, worst_by_slide, pad=10):
    """Grid of every slideNNN.png, slide number burned in, cell framed by severity.

    The frame colour is what makes the sheet a TRIAGE surface rather than a
    thumbnail dump: a VLM (or a human) sees which slides to open.
    """
    files = sorted(glob.glob(os.path.join(out, "slide*.png")))
    if not files:
        return None
    ims, nums = [], []
    for f in files:
        im = Image.open(f).convert("RGB")
        ims.append(
            im.resize((thumb_w, max(1, round(im.height * thumb_w / im.width))), Image.LANCZOS)
        )
        m = re.search(r"(\d+)", os.path.basename(f))
        nums.append(m.group(1) if m else "?")
    tw, th = ims[0].width, max(i.height for i in ims)
    rows = (len(ims) + cols - 1) // cols
    sheet = Image.new(
        "RGB", (cols * tw + pad * (cols + 1), rows * th + pad * (rows + 1)), (26, 30, 38)
    )
    d = ImageDraw.Draw(sheet)
    font = a_font(max(14, thumb_w // 20))
    for k, im in enumerate(ims):
        x = pad + (k % cols) * (tw + pad)
        y = pad + (k // cols) * (th + pad)
        sheet.paste(im, (x, y))
        sev = worst_by_slide.get(int(nums[k])) if nums[k].isdigit() else None
        col = SEV_RGB.get(sev, SEV_RGB[None])
        d.rectangle([x - 3, y - 3, x + tw + 2, y + im.height + 2], outline=col, width=3)
        d.rectangle([x, y, x + font.size * 2 + 14, y + font.size + 10], fill=col)
        d.text((x + 7, y + 4), nums[k], fill=(255, 255, 255), font=font)
    p = os.path.join(out, "contact.png")
    sheet.save(p)
    return p, len(ims), sheet.size


# =============================================================== D8 video verdict
# MediaError.code 4 = MEDIA_ERR_SRC_NOT_SUPPORTED is returned BOTH for "this
# Chromium was built without that decoder" and for "these bytes are not a video".
# Conflating them makes the checker useless on either side: it either cries wolf
# on every H.264 deck (this image's Chromium is the open-source build: no H.264,
# no HEVC, no AAC) or it goes blind to genuine corruption.  The discriminator is
# the CONTAINER, read from the bytes: the ISO-BMFF sample-entry fourcc is a fact
# about the file, canPlayType is a fact about the browser.  Only when the browser
# says it CAN play the codec the file actually contains is an error the file's
# fault.
SOFT_CODECS = {"avc1", "avc3", "hev1", "hvc1"}  # commonly absent from OSS builds
OPEN_CODECS = {"av01", "vp09", "vp08"}


def video_verdict(v):
    err, rs = v.get("error_code"), v.get("ready_state") or 0
    fourcc, cpa = v.get("codec_fourcc"), v.get("can_play_actual_codec")
    checker_blind = (cpa == "") or (cpa is None and fourcc in SOFT_CODECS)
    frames = v.get("decoded_frames")
    if err is None and rs >= 2 and (v.get("video_w") or 0) > 0 and (frames or 0) > 0:
        return (
            "ok",
            "info",
            f"decoded here: {int(v.get('video_w'))}x{int(v.get('video_h'))}, {v.get('duration')}s, {int(frames)} frame(s) decoded",
        )
    if err is None and (v.get("video_w") or 0) > 0 and not frames:
        # metadata parsed (so dimensions are known) but nothing decoded: the exact
        # shape of a file whose moov survived and whose sample data did not.
        return (
            "no-frames",
            "error",
            f"metadata says {int(v.get('video_w'))}x{int(v.get('video_h'))} but readyState={rs} and the decoder produced {frames} frames "
            "- the header is intact and the sample data is not",
        )
    if err == 4 or (err is None and rs == 0):
        why = "MediaError 4 (SRC_NOT_SUPPORTED)" if err == 4 else "readyState stayed 0 after load()"
        if fourcc and checker_blind:
            return (
                "codec-unsupported-by-checker",
                "warn",
                "{}; container says codec={} (ftyp={}) and this Chromium reports "
                'canPlayType({})="" -> the CHECKER cannot decode it, so this is NOT '
                "evidence the asset is broken. It IS evidence the deck is not portable: "
                "any Chromium/Firefox build without proprietary codecs shows a blank box.".format(
                    why, fourcc, v.get("container_ftyp"), v.get("codec_query")
                ),
            )
        if fourcc and cpa in ("maybe", "probably"):
            return (
                "broken",
                "error",
                f"{why}, but the container says codec={fourcc} and this Chromium reports "
                f'canPlayType="{cpa}" -> the browser CAN decode this codec, so the bytes '
                "are bad.",
            )
        return (
            "unsniffable",
            "warn",
            "{} and the container could not be sniffed ({}) -> indeterminate".format(
                why, v.get("sniff_error") or "no moov in the sniff window"
            ),
        )
    if err in (1, 2, 3):
        return ("broken", "error", "MediaError {}: {}".format(err, v.get("error_message")))
    if err is None and rs >= 1 and (v.get("video_w") or 0) == 0:
        return ("no-video-track", "error", "metadata loaded but videoWidth is 0")
    return ("unknown", "warn", f"error={err} readyState={rs}")


# =============================================================== per-slide judging
def judge_slide(s, args):
    """Turn the raw per-slide probe output into severity-tagged findings."""
    f, opts = [], s.get("opts") or {}
    skip = {x.strip().lower() for x in (opts.get("qaSkip") or "").split(",") if x.strip()}

    def on(d):
        return d not in skip

    # ---- D1 overflow
    if on("d1"):
        scroll_ok = "qaScroll" in opts
        oy, ox = s["overflow_y_px"], s["overflow_x_px"]
        if oy > args.tol:
            sev = "info" if scroll_ok else "error"
            f.append(
                F(
                    "d1-overflow",
                    sev,
                    f"slide content is {int(oy)} px taller than the viewport{' (opted in to scrolling via data-qa-scroll)' if scroll_ok else ' - the reader sees a scrollbar and a projector cuts it'}",
                    overflow_y_px=oy,
                    offenders=s["offenders"][:6],
                )
            )
        if ox > args.tol:
            f.append(
                F(
                    "d1-overflow",
                    "info" if scroll_ok else "error",
                    f"slide content is {int(ox)} px wider than the viewport",
                    overflow_x_px=ox,
                    offenders=s["offenders"][:6],
                )
            )
        for sc in s.get("scrollers", []):
            f.append(
                F(
                    "d1-inner-scroller",
                    "warn",
                    f"inner scroller <{sc['sel']}> hides {int(sc['dx'])}x{int(sc['dy'])} px; reachable with a mouse, "
                    "invisible on a projector",
                    **sc,
                )
            )

    # ---- D2 text overlap
    if on("d2"):
        allow = "qaAllowOverlap" in opts
        for p in s.get("overlap", {}).get("pairs", []):
            f.append(
                F(
                    "d2-text-overlap",
                    "info" if allow else "error",
                    f"two text runs overlap by {int(p['overlap_px'])} px^2 ({100 * p['frac_of_smaller']:.0f}% of the smaller): "
                    f"{p['a']['text'][:45]!r} over {p['b']['text'][:45]!r}",
                    **p,
                )
            )

    # ---- D3 truncation
    if on("d3"):
        for t in s.get("truncation", []):
            sev = "error" if t["kind"] in ("ellipsis", "line-clamp", "nowrap-clip") else "warn"
            f.append(
                F(
                    "d3-truncation",
                    sev,
                    f"{t['kind']}: <{t['sel']}> shows {int(t['client_w'])} of {int(t['scroll_w'])} px of its text - {t['text_head'][:60]!r}",
                    **t,
                )
            )

    # ---- D4 occlusion
    if on("d4"):
        allow = "qaAllowOcclusion" in opts
        for o in s.get("occlusion", []):
            f.append(
                F(
                    "d4-occlusion",
                    "info" if allow else "error",
                    f"text {o['text'][:45]!r} is painted over by <{o['covered_by']}> (position:{o['covered_by_position']} z:{o['covered_by_z']}) at {int(o['points_covered'])} of {int(o['points_tested'])} probe points",
                    **o,
                )
            )

    # ---- D5 small text
    if on("d5"):
        thr = float(opts.get("qaMinFont") or args.min_font)
        for t in s.get("small_text", {}).get("small", []):
            sev = "error" if t["font_px"] < thr else "warn"
            f.append(
                F(
                    "d5-small-text",
                    sev,
                    "text renders at {:.1f} px (floor {:.1f} px, comfort {:.1f} px): {!r}".format(
                        t["font_px"], thr, args.comfort_font, t["text"][:50]
                    ),
                    **t,
                )
            )

    # ---- D7 images
    if on("d7"):
        for im in s.get("images", []):
            if im["broken"]:
                f.append(
                    F(
                        "d7-image",
                        "error",
                        "image failed to decode (naturalWidth 0): {}".format(im["src_head"]),
                        **im,
                    )
                )
            elif im["natural_w"] == 0:
                f.append(
                    F("d7-image", "warn", "image never loaded: {}".format(im["src_head"]), **im)
                )
            elif im["upscale"] and im["upscale"] > args.upscale:
                f.append(
                    F(
                        "d7-image",
                        "warn",
                        f"image is upscaled {im['upscale']:.2f}x (native {int(im['natural_w'])}x{int(im['natural_h'])}, rendered {int(im['render_w'])}x{int(im['render_h'])}) - it will look soft",
                        **im,
                    )
                )
            if im["scheme"] not in ("data", "blob"):
                f.append(
                    F(
                        "d10-external",
                        "error",
                        "image is not inlined (scheme={}): {}".format(im["scheme"], im["src_head"]),
                        **im,
                    )
                )

    # ---- D8 video
    if on("d8"):
        for v in s.get("videos", []):
            verdict, sev, why = video_verdict(v)
            f.append(F("d8-video", sev, f"{verdict} - {why}", verdict=verdict, **v))
            if v.get("scheme") not in ("data", "blob"):
                f.append(
                    F(
                        "d10-external",
                        "error",
                        "video is not inlined (scheme={})".format(v.get("scheme")),
                        **v,
                    )
                )

    # ---- D9 contrast
    if on("d9"):
        for c in s.get("contrast", []):
            if c["verdict"] == "fail":
                f.append(
                    F(
                        "d9-contrast",
                        "error",
                        "contrast {:.2f}:1 < {:.1f}:1 required for {} text ({:.1f} px, weight {}): {!r}".format(
                            c["ratio"],
                            c["threshold"],
                            c["size_class"],
                            c["font_px"] or 0,
                            c["font_weight"],
                            c["text"][:50],
                        ),
                        **c,
                    )
                )
            elif c["verdict"] == "no-ink":
                f.append(
                    F(
                        "d9-contrast",
                        "warn",
                        f"text painted no ink ({int(c['ink_px'])} px differ between shot and plate) - "
                        f"invisible, fully transparent, or covered: {c['text'][:50]!r}",
                        **c,
                    )
                )

    # ---- D12 offscreen
    if on("d12"):
        scrolls_y = s["overflow_y_px"] > args.tol or "qaScroll" in opts
        scrolls_x = s["overflow_x_px"] > args.tol
        for o in s.get("offscreen", []):
            if o["why"] == "below-viewport" and scrolls_y:
                continue  # already reported once, as d1-overflow
            if o["why"] == "right-of-viewport" and scrolls_x:
                continue
            f.append(
                F(
                    "d12-offscreen",
                    "error",
                    "element sits entirely {}: <{}> at {}".format(o["why"], o["sel"], o["box"]),
                    **o,
                )
            )
    return f


# =============================================================== the run
def run(args):
    html = os.path.abspath(args.html)
    if not os.path.exists(html):
        sys.exit(f"no such deck: {html}")
    os.makedirs(args.out, exist_ok=True)
    url = "file://" + html
    time.time()

    rep = {
        "tool": "deck_qa",
        "version": 1,
        "generated": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "html": html,
        "out": os.path.abspath(args.out),
        "viewport": [args.width, args.height],
        "scale": args.scale,
        "thresholds": {
            "overflow_tol_px": args.tol,
            "min_font_px": args.min_font,
            "max_upscale": args.upscale,
            "overlap_min_px2": args.overlap_min_px,
            "overlap_min_frac": args.overlap_min_frac,
            "wcag": "normal 4.5:1, large 3:1 (>=24px, or >=18.66px bold)",
        },
        "timings": {},
        "slides": [],
    }
    print(f"static scan of {os.path.basename(html)} ...", flush=True)
    t0 = time.time()
    rep["static"] = static_scan(html)
    rep["timings"]["static_scan_s"] = round(time.time() - t0, 1)
    print(
        f"  {rep['static']['bytes'] / 1e6:.1f} MB, external refs={int(rep['static']['external_refs'])} relative refs={int(rep['static']['relative_refs'])}  ({rep['timings']['static_scan_s']:.1f}s)",
        flush=True,
    )

    console, requests, failed, _csp = [], [], [], []
    with sync_playwright() as pw:
        br = pw.chromium.launch(
            args=[
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--force-color-profile=srgb",
                "--hide-scrollbars",
            ]
        )
        ctx = br.new_context(
            viewport={"width": args.width, "height": args.height},
            device_scale_factor=args.scale,
            reduced_motion="reduce",
        )
        ctx.set_default_timeout(args.timeout)
        page = ctx.new_page()

        # ---- D10 runtime: intercept EVERYTHING except the document itself.  Routing
        # the 141 MB main document through the driver would buffer it, so it is
        # excluded by URL predicate rather than by glob.
        def _route(route):
            r = route.request
            requests.append(
                {
                    "url": r.url[:200],
                    "method": r.method,
                    "resource_type": r.resource_type,
                    "routed": True,
                }
            )
            route.continue_()

        page.route(lambda u: u != url, _route)
        page.on(
            "request",
            lambda r: requests.append(
                {
                    "url": r.url[:200],
                    "method": r.method,
                    "resource_type": r.resource_type,
                    "routed": False,
                }
            ),
        )
        page.on(
            "requestfailed",
            lambda r: failed.append({"url": r.url[:200], "failure": (r.failure or "")[:120]}),
        )
        page.on(
            "console",
            lambda m: (
                console.append({"type": m.type, "text": m.text[:300]})
                if m.type in ("error", "warning")
                else None
            ),
        )
        page.on("pageerror", lambda e: console.append({"type": "pageerror", "text": str(e)[:300]}))
        page.add_init_script("""addEventListener('securitypolicyviolation',
            e => (window.__csp = window.__csp || []).push(
                {directive: e.violatedDirective, blocked: String(e.blockedURI).slice(0,160)}));""")

        print(f"loading {url} ...", flush=True)
        t0 = time.time()
        page.goto(url, wait_until="load")
        page.wait_for_selector(".slide.on", state="attached")
        rep["timings"]["load_s"] = round(time.time() - t0, 1)
        page.evaluate(J.HELPERS)
        info = page.evaluate(J.DECK_INFO)
        rep["deck"] = info
        total = info["slides"]
        want = parse_only(args.only, total)
        pad = max(3, len(str(total)))
        print(
            f"  {total} slides (show()={info['has_show']}, dots={int(info['nav_dots'])}) loaded in {rep['timings']['load_s']:.1f}s; shooting {len(want)} at {int(args.width)}x{int(args.height)}@{args.scale:g}x",
            flush=True,
        )

        rep["fonts"] = page.evaluate(J.FONTS, [args.font_family])
        scroll_ok = {int(x) for x in args.scroll_slides.replace(" ", "").split(",") if x}

        t0 = time.time()
        for idx, n in enumerate(want, 1):
            page.evaluate(J.GOTO, [n])
            page.wait_for_function(
                "n => document.querySelectorAll('.slide')[n-1].classList.contains('on')", arg=n
            )
            st = page.evaluate(J.SETTLE, [args.video_wait])
            # freeze media so shot and plate are pixel-identical apart from ink
            page.evaluate("""() => { document.querySelectorAll('video').forEach(v => {
                try { v.pause(); v.currentTime = 0; } catch (e) {} }); }""")
            page.wait_for_timeout(args.freeze_ms)

            s = page.evaluate(J.OVERFLOW, [n, args.tol])
            s["n"] = n
            if n in scroll_ok:
                s.setdefault("opts", {})["qaScroll"] = "cli"
            s["imgs"], s["img_failed"] = st["imgs"], st["img_failed"]
            s["overlap"] = page.evaluate(
                J.TEXT_OVERLAP, [args.overlap_min_frac, args.overlap_min_px]
            )
            s["truncation"] = page.evaluate(J.TRUNCATION, [args.tol])
            s["occlusion"] = page.evaluate(J.OCCLUSION, [args.occl_samples])
            s["small_text"] = page.evaluate(J.SMALL_TEXT, [max(args.min_font, args.comfort_font)])
            s["offscreen"] = page.evaluate(J.OFFSCREEN, [args.tol])
            s["images"] = page.evaluate(J.IMAGES, None)
            s["videos"] = page.evaluate(J.VIDEOS, [args.sniff_bytes])

            name = f"slide{n:0{pad}d}.png"
            shot_path = os.path.join(args.out, name)
            page.screenshot(path=shot_path, animations="disabled", caret="hide")
            s["file"] = name

            if not args.no_contrast:
                geom = page.evaluate(J.CONTRAST_GEOM, None)
                page.add_style_tag(content=J.INK_OFF_CSS)
                plate_bytes = page.screenshot(animations="disabled", caret="hide")
                page.evaluate("""() => { const ss = [...document.querySelectorAll('style')];
                    const last = ss[ss.length - 1]; if (last) last.remove(); }""")
                import io

                shot = Image.open(shot_path).convert("RGB")
                plate = Image.open(io.BytesIO(plate_bytes)).convert("RGB")
                if args.keep_plates:
                    plate.save(os.path.join(args.out, f"plate{n:0{pad}d}.png"))
                s["contrast"] = contrast_from_plates(geom, shot, plate)
            else:
                s["contrast"] = []

            s["findings"] = judge_slide(s, args)
            rep["slides"].append(s)
            if idx % 10 == 0 or idx == len(want):
                print(f"  {idx}/{len(want)} slides ({time.time() - t0:.0f}s)", flush=True)
        rep["timings"]["shoot_s"] = round(time.time() - t0, 1)
        rep["csp_violations"] = page.evaluate("() => window.__csp || []")

        # ---- D10 offline proof
        if not args.no_offline:
            print("offline pass (context.set_offline(True) + reload) ...", flush=True)
            t0 = time.time()
            off_console, off_failed = [], []
            page.on(
                "console",
                lambda m: (
                    off_console.append({"type": m.type, "text": m.text[:200]})
                    if m.type in ("error", "warning")
                    else None
                ),
            )
            page.on(
                "requestfailed",
                lambda r: off_failed.append(
                    {"url": r.url[:200], "failure": (r.failure or "")[:120]}
                ),
            )
            ctx.set_offline(True)
            page.reload(wait_until="load")
            page.wait_for_selector(".slide.on", state="attached")
            page.evaluate(J.HELPERS)
            off_info = page.evaluate(J.DECK_INFO)
            off_media = []
            for n in want[: args.offline_slides] or [1]:
                page.evaluate(J.GOTO, [n])
                st = page.evaluate(J.SETTLE, [args.video_wait])
                off_media.append({"n": n, **st})
            rep["offline"] = {
                "slides": off_info["slides"],
                "slides_match": off_info["slides"] == total,
                "has_show": off_info["has_show"],
                "sampled_slides": off_media,
                "img_failed_total": sum(m["img_failed"] for m in off_media),
                "console": off_console[:20],
                "requestfailed": off_failed[:20],
                "seconds": round(time.time() - t0, 1),
            }
            ctx.set_offline(False)
        ctx.close()
        br.close()

    rep["console"] = console[:60]
    rep["requests"] = requests[:80]
    rep["requestfailed"] = failed[:40]
    return rep, want, pad


# =============================================================== deck-level findings
def judge_deck(rep, args):
    f = []
    # ---- D6 font fallback
    ft = rep.get("fonts") or {}
    for p in ft.get("probes", []):
        if not p["applied"]:
            f.append(
                F(
                    "d6-font-fallback",
                    "error",
                    "font {!r} did not apply: the same string measures identically with and "
                    "without it against {} - the deck is rendering in a fallback, so every "
                    "metric below (overflow, truncation, line breaks) is measured on the "
                    "WRONG font".format(
                        p["family"], " and ".join(e["fallback"] for e in p["evidence"])
                    ),
                    **p,
                )
            )
    declared = ft.get("declared", [])
    for d in declared:
        if d["status"] not in ("loaded",):
            f.append(
                F(
                    "d6-font-fallback",
                    "warn",
                    "@font-face {} {} is {}".format(d["family"], d["weight"], d["status"]),
                    **d,
                )
            )
    if not declared:
        f.append(
            F(
                "d6-font-fallback",
                "warn",
                "the document declares no @font-face at all - it depends on fonts installed "
                "on the reader's machine, so it is not self-contained for text metrics",
            )
        )

    # ---- D10 self-containment
    st = rep["static"]
    if st["external_refs"]:
        f.append(
            F(
                "d10-external",
                "error",
                f"{int(st['external_refs'])} absolute http(s)/ws/protocol-relative references in the file",
                samples=[s for s in st["samples"] if s["class"] == "EXTERNAL"][:10],
            )
        )
    if st["relative_refs"]:
        f.append(
            F(
                "d10-external",
                "error",
                f"{int(st['relative_refs'])} relative references - the file needs sibling files to render",
                samples=[s for s in st["samples"] if s["class"] == "RELATIVE"][:10],
            )
        )
    net = {k: v for k, v in st["network_apis"].items() if k.lower() != "import"}
    if net:
        f.append(
            F(
                "d10-external",
                "warn",
                "inline script mentions network APIs: {} (static mention, not proof of a call)".format(
                    ", ".join(f"{k} x{int(v)}" for k, v in sorted(net.items()))
                ),
                apis=net,
            )
        )
    offdoc = [r for r in rep["requests"] if not r["url"].startswith(("data:", "about:", "file://"))]
    if offdoc:
        f.append(
            F(
                "d10-external",
                "error",
                f"{len(offdoc)} request(s) left the document at runtime",
                requests=offdoc[:10],
            )
        )
    else:
        f.append(
            F(
                "d10-external",
                "info",
                f"route interception saw {len(rep['requests'])} request(s), none off-document",
                schemes=sorted({r["url"].split(":")[0] for r in rep["requests"]}),
            )
        )
    off = rep.get("offline")
    if off:
        if not off["slides_match"]:
            f.append(
                F(
                    "d10-external",
                    "error",
                    f"offline reload produced {int(off['slides'])} slides, online produced {int(rep['deck']['slides'])}",
                )
            )
        if off["img_failed_total"]:
            f.append(
                F(
                    "d10-external",
                    "error",
                    f"{int(off['img_failed_total'])} image(s) failed to decode with the network disabled",
                    sampled=off["sampled_slides"],
                )
            )
        if off["requestfailed"]:
            f.append(
                F(
                    "d10-external",
                    "error",
                    f"{len(off['requestfailed'])} request(s) failed offline - the deck wanted the network",
                    failures=off["requestfailed"][:10],
                )
            )
        if off["slides_match"] and not off["img_failed_total"] and not off["requestfailed"]:
            f.append(
                F(
                    "d10-external",
                    "info",
                    f"offline proof: reloaded with the network disabled, {int(off['slides'])} slides, "
                    "0 failed requests, 0 image decode failures",
                )
            )

    # ---- D7 duplicate payloads (deck-level: the same bytes inlined N times IN THE FILE)
    # Measured on the FILE, not the DOM: a builder that inlines an asset once and
    # re-points its other uses at page load (the deck toolbox's data-from) shows
    # the same src on N elements while the bytes exist once, and a DOM count
    # reported that as N-1 copies of avoidable size. Slides are attributed by
    # matching the DOM src heads back onto the file's payloads.
    where = {}
    for s in rep["slides"]:
        for im in s.get("images", []) + s.get("videos", []):
            h = im.get("src_head")
            if h:
                where.setdefault(h[:48], set()).add(s["n"])
    dups, dup_bytes = [], 0
    for d in duplicate_data_uris(rep["html"]):
        dup_bytes += d["bytes"] * (d["occurrences"] - 1)
        d["slides"] = sorted(where.get(d["src_head"][:48], ()))
        dups.append(d)
    if dups:
        f.append(
            F(
                "d7-duplicate-bytes",
                "warn",
                f"{len(dups)} asset(s) are inlined more than once in the file - ~{dup_bytes / 1e6:.1f} MB of avoidable size",
                duplicates=sorted(dups, key=lambda d: -d["bytes"])[:10],
            )
        )

    # ---- D8 portability (deck-level)
    codecs = {}
    for s in rep["slides"]:
        for v in s.get("videos", []):
            c = v.get("codec_fourcc") or "unknown"
            codecs[c] = codecs.get(c, 0) + 1
    soft = {c: n for c, n in codecs.items() if c in SOFT_CODECS}
    if soft:
        f.append(
            F(
                "d8-portability",
                "warn",
                f"{int(sum(soft.values()))} video(s) use {'/'.join(sorted(soft))}. Those decoders are absent from every Chromium built "
                "without proprietary codecs (this checker's build included) and from Firefox "
                "where the system H.264 is missing: those readers see a blank box with no "
                'error. Re-encode to AV1 (av01, in mp4) or VP9 - both report "probably" here.',
                codecs=codecs,
            )
        )
    elif codecs:
        f.append(
            F(
                "d8-portability",
                "info",
                "video codecs in the deck: {}".format(
                    ", ".join(f"{k} x{int(v)}" for k, v in sorted(codecs.items()))
                ),
                codecs=codecs,
            )
        )

    # ---- D11 console
    errs = [c for c in rep["console"] if c["type"] in ("error", "pageerror")]
    warns = [c for c in rep["console"] if c["type"] == "warning"]
    if errs:
        f.append(
            F(
                "d11-console",
                "error",
                f"{len(errs)} console error(s)/uncaught exception(s)",
                entries=errs[:15],
            )
        )
    if warns:
        f.append(F("d11-console", "warn", f"{len(warns)} console warning(s)", entries=warns[:10]))
    if rep.get("csp_violations"):
        f.append(
            F(
                "d11-console",
                "error",
                f"{len(rep['csp_violations'])} CSP violation(s)",
                entries=rep["csp_violations"][:10],
            )
        )
    if rep.get("requestfailed"):
        f.append(
            F(
                "d11-console",
                "warn",
                f"{len(rep['requestfailed'])} failed request(s) online",
                entries=rep["requestfailed"][:10],
            )
        )
    if not errs and not warns and not rep.get("csp_violations"):
        f.append(F("d11-console", "info", "no console errors, warnings or CSP violations"))
    return f


# =============================================================== report.md
def worst(findings):
    if any(x["severity"] == "error" for x in findings):
        return "error"
    if any(x["severity"] == "warn" for x in findings):
        return "warn"
    return "info" if findings else None


def write_md(rep, path):
    """A report a VLM can read BESIDE the images: every slide names its own PNG,
    and every finding carries the box it is about, so the model is told where to
    look instead of being asked to find it."""
    L = []
    w = L.append
    errs = [(s["n"], f) for s in rep["slides"] for f in s["findings"] if f["severity"] == "error"]
    wrns = [(s["n"], f) for s in rep["slides"] for f in s["findings"] if f["severity"] == "warn"]
    dk = rep["deck_findings"]
    w("# deck_qa report - {}".format(os.path.basename(rep["html"])))
    w("")
    w("| | |")
    w("|---|---|")
    w(
        f"| deck | `{os.path.basename(rep['html'])}` ({rep['static']['bytes'] / 1e6:.1f} MB, {int(rep['deck']['slides'])} slides) |"
    )
    w(f"| viewport | {int(rep['viewport'][0])}x{int(rep['viewport'][1])} @ {rep['scale']:g}x |")
    w("| generated | {} |".format(rep["generated"]))
    w(f"| slide findings | **{len(errs)} error**, {len(wrns)} warn |")
    w(
        f"| deck findings | **{int(sum(1 for f in dk if f['severity'] == 'error'))} error**, {int(sum(1 for f in dk if f['severity'] == 'warn'))} warn |"
    )
    w(
        "| timings | static {}s, load {}s, shoot {}s |".format(
            rep["timings"].get("static_scan_s"),
            rep["timings"].get("load_s"),
            rep["timings"].get("shoot_s"),
        )
    )
    w("")
    w("## Triage - open these slides first")
    w("")
    if errs or wrns:
        w("| slide | image | worst | detectors |")
        w("|---|---|---|---|")
        for s in rep["slides"]:
            sev = worst(s["findings"])
            if sev in ("error", "warn"):
                dets = sorted(
                    {f["detector"] for f in s["findings"] if f["severity"] in ("error", "warn")}
                )
                w(f"| {int(s['n'])} | `{s['file']}` | **{sev}** | {', '.join(dets)} |")
    else:
        w("No slide-level errors or warnings.")
    w("")
    w("## Deck-level findings")
    w("")
    for f in sorted(dk, key=lambda x: SEV_ORDER[x["severity"]]):
        w("- **{}** `{}` - {}".format(f["severity"].upper(), f["detector"], f["message"]))
        for key in ("samples", "entries", "duplicates", "requests", "failures"):
            for it in (f.get(key) or [])[:5]:
                w(f"  - `{json.dumps(it)[:220]}`")
    w("")
    w("## Per slide")
    w("")
    for s in rep["slides"]:
        sev = worst(s["findings"])
        tag = {"error": "**ERROR**", "warn": "**warn**", "info": "info", None: "clean"}[sev]
        w(f"### Slide {int(s['n'])} - {s.get('title') or s.get('kicker') or '(untitled)'}  [{tag}]")
        w("")
        w(
            f"`{s['file']}` - {int(s['w'])}x{int(s['h'])} content, scroll {int(s['scroll_w'])}x{int(s['scroll_h'])}, {len(s.get('images', []))} img, {len(s.get('videos', []))} video, {int(s.get('small_text', {}).get('n', 0))} text runs"
        )
        w("")
        if not s["findings"]:
            w("_no findings_")
            w("")
            continue
        for f in sorted(s["findings"], key=lambda x: SEV_ORDER[x["severity"]]):
            box = f.get("box") or (f.get("a") or {}).get("box")
            loc = (
                ("  at x={} y={} {}x{}".format(box["x"], box["y"], box["w"], box["h"]))
                if box
                else ""
            )
            w(
                "- **{}** `{}` - {}{}".format(
                    f["severity"].upper(), f["detector"], f["message"], loc
                )
            )
            for o in (f.get("offenders") or [])[:4]:
                w(
                    f"  - offender `<{o['sel']}>` in `<{o.get('block_sel', '?')}>` +{int(o['dx'])}x +{int(o['dy'])}y - {o['text_head'][:60]!r}"
                )
        w("")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(L) + "\n")


# =============================================================== cli
def parse_only(spec, total):
    if not spec:
        return list(range(1, total + 1))
    out = []
    for part in spec.replace(" ", "").split(","):
        if not part:
            continue
        if "-" in part[1:]:
            a, b = part.split("-", 1)
            out += list(range(int(a), int(b) + 1))
        else:
            out.append(int(part))
    bad = [n for n in out if not 1 <= n <= total]
    if bad:
        sys.exit(f"--only: slide(s) {bad} outside 1..{total}")
    return sorted(dict.fromkeys(out))


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--html", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default="")
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--height", type=int, default=1080)
    ap.add_argument("--scale", type=float, default=1.0)
    ap.add_argument("--tol", type=int, default=2, help="overflow tolerated (px)")
    ap.add_argument("--min-font", type=float, default=12.0, help="D5 error threshold (px)")
    ap.add_argument("--comfort-font", type=float, default=14.0, help="D5 warn threshold (px)")
    ap.add_argument("--upscale", type=float, default=1.35, help="D7 max image upscale factor")
    ap.add_argument("--overlap-min-px", type=float, default=24.0, help="D2 min overlap area (px^2)")
    ap.add_argument(
        "--overlap-min-frac", type=float, default=0.06, help="D2 min share of smaller rect"
    )
    ap.add_argument(
        "--occl-samples", type=int, default=3, help="D4 horizontal probe points per rect"
    )
    ap.add_argument("--sniff-bytes", type=int, default=2 << 20, help="D8 bytes read to find moov")
    ap.add_argument("--video-wait", type=int, default=4000, help="ms to wait for loadedmetadata")
    ap.add_argument("--freeze-ms", type=int, default=120, help="settle after pausing media")
    ap.add_argument("--font-family", nargs="*", default=["Inter"])
    ap.add_argument("--no-contrast", action="store_true")
    ap.add_argument("--keep-plates", action="store_true", help="write the ink-off plates too")
    ap.add_argument("--no-offline", action="store_true")
    ap.add_argument("--offline-slides", type=int, default=6)
    ap.add_argument("--no-contact", action="store_true")
    ap.add_argument("--contact-cols", type=int, default=6)
    ap.add_argument("--contact-width", type=int, default=460)
    ap.add_argument("--timeout", type=int, default=300000)
    ap.add_argument(
        "--scroll-slides",
        default="",
        help="slide numbers that may scroll, as if they carried data-qa-scroll "
        "(for decks you cannot edit); e.g. 78",
    )
    ap.add_argument("--strict", action="store_true", help="exit 1 on any error finding")
    args = ap.parse_args()

    rep, want, pad = run(args)
    rep["deck_findings"] = judge_deck(rep, args)

    by_slide = {s["n"]: worst(s["findings"]) for s in rep["slides"]}
    n_err = sum(1 for s in rep["slides"] for f in s["findings"] if f["severity"] == "error")
    n_warn = sum(1 for s in rep["slides"] for f in s["findings"] if f["severity"] == "warn")
    n_derr = sum(1 for f in rep["deck_findings"] if f["severity"] == "error")
    rep["totals"] = {
        "slides": len(rep["slides"]),
        "slide_errors": n_err,
        "slide_warnings": n_warn,
        "deck_errors": n_derr,
        "deck_warnings": sum(1 for f in rep["deck_findings"] if f["severity"] == "warn"),
        "error_slides": sorted(n for n, s in by_slide.items() if s == "error"),
        "warn_slides": sorted(n for n, s in by_slide.items() if s == "warn"),
        "by_detector": {},
    }
    for s in rep["slides"]:
        for f in s["findings"]:
            k = "{}/{}".format(f["detector"], f["severity"])
            rep["totals"]["by_detector"][k] = rep["totals"]["by_detector"].get(k, 0) + 1
    for f in rep["deck_findings"]:
        k = "{}/{}".format(f["detector"], f["severity"])
        rep["totals"]["by_detector"][k] = rep["totals"]["by_detector"].get(k, 0) + 1

    with open(os.path.join(args.out, "report.json"), "w", encoding="utf-8") as fh:
        json.dump(rep, fh, indent=1)
    write_md(rep, os.path.join(args.out, "report.md"))

    if not args.no_contact:
        c = contact_sheet(args.out, args.contact_cols, args.contact_width, by_slide)
        if c:
            print(f"contact sheet: {c[0]} ({int(c[1])} slides, {int(c[2][0])}x{int(c[2][1])})")

    print(f"\n{'detector/severity':<26} count")
    for k in sorted(rep["totals"]["by_detector"]):
        print(f"{str(k):<26} {int(rep['totals']['by_detector'][k])}")
    print("\nslides with errors: %s" % (rep["totals"]["error_slides"] or "none"))
    print("slides with warnings: %s" % (rep["totals"]["warn_slides"] or "none"))
    print(f"report: {args.out}/report.json  {args.out}/report.md")
    if args.strict and (n_err or n_derr):
        sys.exit(1)


if __name__ == "__main__":
    main()
