"""Resolved geometry -> SVG, in the house style. A pure function of the geometry and house.json:
no measurement, no solver, no randomness, fixed number formatting — so `press fig rebuild` from a
pick record reproduces the picked bytes exactly.

The SVG is 476 units wide (1 unit = 1 pt; the 168 mm print column), text stays text, and it
names its own font so it reads the same standalone or inside a page. Every edge carries
data-id and data-net, and every box data-id, so the checker knows nets from data, not from
guessing which lines touch."""

from . import house

MARKERS = {"NAVY": "arr", "MUTED": "arrm", "TEAL": "arrt", "AMBER": "arra", "RED": "arrr"}
STYLE = (
    f"font-family:{house.FONT_FAMILY},'Liberation Sans',Arial,sans-serif;"
    "font-feature-settings:'calt' 0"
)


def esc(s):
    """Text content: & < > escaped."""
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def attr(s):
    """An attribute value inside double quotes: & < > " ' escaped."""
    return esc(s).replace('"', "&quot;").replace("'", "&#39;")


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".") if isinstance(v, float) else str(v)


def marker_defs():
    """The arrowhead markers, one per arrow colour (ids from MARKERS)."""
    return [
        f'<marker id="{mid}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" '
        f'orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="{house.COLORS[col]}"/></marker>'
        for col, mid in MARKERS.items()
    ]


def svg_open(height):
    """The house SVG root (476 units wide, its own font) and the marker defs."""
    return (
        [
            f'<svg viewBox="0 0 {house.W} {f(round(height, 2))}" xmlns="http://www.w3.org/2000/svg" class="svgtxt" '
            f'style="{STYLE}">',
            "<defs>",
        ]
        + marker_defs()
        + ["</defs>"]
    )


def text_el(x, y, size, weight, fill, s, anchor="start"):
    """One text run; `fill` is a colour hex."""
    a = (
        ' text-anchor="middle"'
        if anchor == "middle"
        else (' text-anchor="end"' if anchor == "end" else "")
    )
    w = f' font-weight="{weight}"' if weight != 400 else ""
    return (
        f'<text x="{f(x)}" y="{f(y)}" font-size="{size}"{w}{a} fill="{fill}" '
        f'style="white-space:pre">{esc(s)}</text>'
    )


def rect_el(cls, id_, x, y, w, h, rx, fill, stroke, dash=None):
    """One box; `fill` / `stroke` are colour hexes or None (drawn as none)."""
    da = f' stroke-dasharray="{dash}"' if dash else ""
    return (
        f'<rect class="{cls}" data-id="{attr(id_)}" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" '
        f'rx="{rx}" fill="{fill or "none"}" stroke="{stroke or "none"}" stroke-width="1"{da}/>'
    )


def path_el(pts, col, sw, dash, arrow, cls, id_, net, unrouted=False):
    """One stroked polyline; `col` is a house colour KEY (it picks the arrowhead marker too)."""
    d = "M" + " L".join(f"{f(x)} {f(y)}" for x, y in pts)
    da = f' stroke-dasharray="{dash}"' if dash else ""
    m = ""
    if arrow in ("end", "both"):
        m += f' marker-end="url(#{MARKERS[col]})"'
    if arrow == "both":
        m += f' marker-start="url(#{MARKERS[col]})"'
    ur = ' data-unrouted="1"' if unrouted else ""
    return (
        f'<path class="{cls}" data-id="{attr(id_)}" data-net="{attr(net)}"{ur} d="{d}" fill="none" '
        f'stroke="{house.COLORS[col]}" stroke-width="{sw}"{da}{m}/>'
    )


def extent(g):
    ys = [b["y"] + b["h"] for b in g["boxes"]]
    ys += [y for e in g["edges"] for _, y in e["points"]]
    for b in g["buses"]:
        ys += [b["line"][1], b["line"][3]]
        if b["label"]:
            ys.append(b["label"]["y"] + b["label"]["h"])
    return max(ys) + 1


def svg(g):
    C, T, L, E = house.COLORS, house.TEXT, house.LOOKS, house.EDGE_LOOKS
    o = svg_open(extent(g))

    def text(x, y, st, s, anchor):
        size, wt, col = T[st]
        o.append(text_el(x, y, size, wt, C[col], s, anchor))

    for b in g["boxes"]:
        fill, stroke, rx, dash = L[b["look"]]
        if fill or stroke:
            o.append(
                rect_el(
                    b["kind"],
                    b["id"],
                    b["x"] + 0.5,
                    b["y"] + 0.5,
                    b["w"] - 1,
                    b["h"] - 1,
                    rx,
                    C[fill] if fill else None,
                    C[stroke] if stroke else None,
                    dash,
                )
            )
        for t in b["texts"]:
            text(*t)

    def poly(pts, look, arrow, cls, id_, net, unrouted=False):
        col, sw, dash = E[look]
        o.append(path_el(pts, col, sw, dash, arrow, cls, id_, net, unrouted))

    for b in g["buses"]:
        x1, y1, x2, y2 = b["line"]
        poly([[x1, y1], [x2, y2]], b["look"], "none", "bus", b["id"], b["id"])
        if b["label"]:
            for t in b["label"]["texts"]:
                text(*t)
    for e in g["edges"]:
        poly(
            e["points"], e["look"], e["arrow"], "edge", e["id"], e["net"], e.get("unrouted", False)
        )
    o.append("</svg>")
    return "\n".join(o) + "\n"
