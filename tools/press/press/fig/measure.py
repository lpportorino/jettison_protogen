"""Text measurement and box sizing.

Widths come from HarfBuzz shaping of the Inter font files Chromium itself loads, with the
`calt` feature OFF (the renderer sets font-feature-settings "calt" 0 to match), so a width here
is the width Chromium draws (within about 0.02 units over 133 strings when measured against the
browser's own advances); a plain sum of glyph advances, without kerning, errs by up to 2.6 units.

Box text layout (the house panel): a row's baseline advances by size * line-height; the box is
2 * pad - 2 + sum(size * line-height) tall and (widest row) + 2 * pad + 2 wide; text starts
pad + 1 in. Cells (one line of table columns) share tab stops across their column set: each
column is as wide as its widest cell in the set, plus the house column gap.
Every size is rounded UP to a whole unit: the placer works on integers."""

import functools
import math

import uharfbuzz as hb

from . import house

FONTS = {
    400: "/usr/share/fonts/opentype/inter/Inter-Regular.otf",
    700: "/usr/share/fonts/opentype/inter/Inter-Bold.otf",
}


@functools.lru_cache(None)
def _font(weight):
    if weight not in FONTS:
        raise SystemExit(
            f"measure: no Inter face for weight {weight}; house.json may use 400 or 700"
        )
    face = hb.Face(hb.Blob.from_file_path(FONTS[weight]))
    return hb.Font(face), face.upem


@functools.lru_cache(None)
def width(text, size, weight):
    """Advance width of `text` in units (= pt) at `size` pt."""
    font, upem = _font(weight)
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf, {"calt": False})
    return sum(p.x_advance for p in buf.glyph_positions) * size / upem


def _row_w(style, text):
    size, weight, _ = house.TEXT[style]
    return width(text, size, weight)


def rows_block(rows, pad, align):
    """-> (w, h, texts) for stacked rows; texts = [[dx, dy, style, text, anchor]] relative to the
    block's top-left; dx is None for centred rows (resolved against the final box width)."""
    cy, texts, wmax = pad - 1, [], 0.0
    for st, s in rows:
        size = house.TEXT[st][0]
        cy += size * house.LINE
        wmax = max(wmax, _row_w(st, s))
        texts.append(
            [
                None if align == "center" else pad + 1,
                round(cy - size * 0.34, 2),
                st,
                s,
                "middle" if align == "center" else "start",
            ]
        )
    h = 2 * pad - 2 + sum(house.TEXT[st][0] * house.LINE for st, _ in rows)
    return wmax + 2 * pad + 2, h, texts


def size_all(d):
    """Measure every node, group title and bus label of an (effective) description.
    -> {'nodes': {id: {w, h, texts, look, note}}, 'groups': {id: {title_w, top, pad, texts}},
        'labels': {bus id: {w, h, texts}}}"""
    tabs = {}
    for n in d["nodes"]:
        if "cells" in n:
            cur = tabs.setdefault(n["column-set"], [0.0] * len(n["cells"]))
            for i, (st, s) in enumerate(n["cells"]):
                cur[i] = max(cur[i], _row_w(st, s))
    nodes = {}
    for n in d["nodes"]:
        note = n["look"] == "note"
        pad = n.get("pad", house.NOTE_PAD if note else house.PAD)
        if "cells" in n:
            cw = tabs[n["column-set"]]
            size = max(house.TEXT[st][0] for st, _ in n["cells"])
            base = pad - 1 + size * house.LINE
            x, texts = pad + 1, []
            for i, (st, s) in enumerate(n["cells"]):
                texts.append(
                    [round(x, 2), round(base - house.TEXT[st][0] * 0.34, 2), st, s, "start"]
                )
                x += cw[i] + house.COLGAP
            w, h = x - house.COLGAP + pad + 1, 2 * pad - 2 + size * house.LINE
        else:
            w, h, texts = rows_block(n["rows"], pad, n.get("align", "left"))
        nodes[n["id"]] = {
            "w": math.ceil(w - 1e-6),
            "h": math.ceil(h - 1e-6),
            "texts": texts,
            "look": n["look"],
            "note": note,
        }
    groups = {}
    for g in d["groups"]:
        title = g.get("title", [])
        if title:
            tw, th, texts = rows_block(title, house.PAD, g.get("align", "left"))
            top = math.ceil(th + 2 - 1e-6)
        else:
            tw, texts, top = 0, [], house.PAD
        groups[g["id"]] = {
            "title_w": math.ceil(tw - 1e-6),
            "top": top,
            "pad": house.PAD,
            "texts": texts,
            "look": g["look"],
        }
    labels = {}
    for b in d["buses"]:
        if "label" in b:
            w, h, texts = rows_block(b["label"], 0, "center")
            labels[b["id"]] = {"w": math.ceil(w - 1e-6), "h": math.ceil(h - 1e-6), "texts": texts}
    return {"nodes": nodes, "groups": groups, "labels": labels}
