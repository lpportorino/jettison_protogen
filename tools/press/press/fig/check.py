"""The figure checker: design-rule checks on any figure SVG, measured in Chromium at print
scale (the SVG is set 168 mm wide, so 476 units = the print column and a font size is its size on
paper). Every check is a HARD check: one defect fails the figure.

Geometry comes from the browser (text boxes are the browser's own glyph extents in Inter), not
from the SVG source. Edges are <line> and stroke-only <path> outside <marker>. A net is the set
of edges carrying one data-net value when every edge declares one (our renderer always does);
for an SVG drawn by hand, edges whose end touches another edge are one net.

text   min-pt          the smallest text is under the house floor (7 pt)
       canvas-overflow the drawing is wider than 476 units (it would print smaller)
       text-outside    a text pokes more than 0.6 units out of the smallest box containing its centre
       text-overlap    two texts overlap
       crowded         a text sits within 1.8 units of its box's side
       text-straddle   a text lies partly inside a box it does not belong to
       text-clipped    a text crosses the edge of the drawing
edges  clearance       parallel runs of different nets closer than 3 units over more than 2 units
       shared-path     different nets drawn on top of each other
       edge-in-box     an edge passes through a box (shrunk 1.5 units)
       edge-in-text    an edge passes through a text
       crossing        edges of different nets cross
       bends>3         an edge bends more than 3 times
       unbundled       runs of one net side by side, under 3 units apart, instead of one trunk
       edge-outside    an edge point lies outside the drawing
       unrouted        a connection the router left as a straight ratsnest (data-unrouted)
       room-escape     an edge with both ends inside a room (a box holding boxes) leaves it
       text-low-contrast  a text drawn over a filled box or mark (the last one painted under its centre)
                       has a WCAG 2 contrast ratio under 4.5:1 against that fill
ref    external        the SVG reaches outside itself: an href or xlink:href that is not a #fragment
                       or data:, a url(...) that is not url(#...), an @import, or any request the
                       page attempted while it was checked (all are intercepted and refused)
heads  arrow-short     the last segment is shorter than the arrowhead
       arrow-collide   both heads of a two-headed edge meet on one short segment
       arrow-buried    a tip ends more than 2 units inside a box
       arrow-hidden    a box painted later covers the head
       arrow-outside   a tip lies outside the drawing"""

import json
import re

from playwright.sync_api import sync_playwright

from . import house

CHECKS = [
    "min-pt",
    "canvas-overflow",
    "text-outside",
    "text-overlap",
    "crowded",
    "text-straddle",
    "text-clipped",
    "clearance",
    "shared-path",
    "edge-in-box",
    "edge-in-text",
    "crossing",
    "bends>3",
    "unbundled",
    "edge-outside",
    "unrouted",
    "room-escape",
    "arrow-short",
    "arrow-collide",
    "arrow-buried",
    "arrow-hidden",
    "arrow-outside",
    "external",
    "text-low-contrast",
]
EXTERNAL = [
    (re.compile(r"""(?:xlink:)?href\s*=\s*["']\s*(?!#|data:)([^"']*)""", re.I), "reference"),
    (re.compile(r"""url\(\s*["']?\s*(?!#|data:)([^)"']*)""", re.I), "url()"),
    (re.compile(r"@import[^;]*", re.I), "@import"),
]


def external_refs(svg_text):
    """Every reference in the SVG source that points outside the SVG itself."""
    out = []
    for rx, kind in EXTERNAL:
        out += [f"{kind} {m.group(0)[:60]}" for m in rx.finditer(svg_text)]
    return out


CSS = (
    '.svgtxt{font-family:"Inter","Liberation Sans",Arial,sans-serif} .svgtxt,.svgtxt text{font-feature-settings:"calt" 0}'
    " svg{display:block;width:168mm;height:auto}"
)
JS = r"""() => {
 const svg = document.querySelector('svg'); const vb = svg.viewBox.baseVal; const sr = svg.getBoundingClientRect();
 const scale = sr.width / vb.width;
 const inMarker = el => !!el.closest('marker');
 const all = [...svg.querySelectorAll('*')];
 const order = new Map(all.map((e, i) => [e, i]));
 const op = el => { let o = 1; for (let e = el; e && e !== svg.parentNode; e = e.parentElement) {
   const cs = getComputedStyle(e); o *= parseFloat(cs.opacity || '1'); } return o; };
 const rects = [...svg.querySelectorAll('rect')].filter(r => !inMarker(r)).map(r => { const b = r.getBBox(); const cs = getComputedStyle(r);
   return {x: b.x, y: b.y, w: b.width, h: b.height, o: order.get(r), fill: r.getAttribute('fill'),
           fc: cs.fill, fo: parseFloat(cs.fillOpacity || '1') * op(r)}; }).filter(r => r.w > 2 && r.h > 2);
 const texts = [...svg.querySelectorAll('text')].filter(t => t.textContent.trim()).map(t => { const b = t.getBBox();
   return {x: b.x, y: b.y, w: b.width, h: b.height, s: t.textContent.trim().slice(0, 40), o: order.get(t),
           tc: getComputedStyle(t).fill, pt: parseFloat(getComputedStyle(t).fontSize) * scale * 0.75}; });
 const parse = d => { const tk = (d || '').match(/[MLHVZmlhvzC]|-?[\d.]+(?:e-?\d+)?/g) || []; const pts = []; let i = 0, c = 'M', x = 0, y = 0, sx = 0, sy = 0;
   while (i < tk.length) { if (/[A-Za-z]/.test(tk[i])) c = tk[i++];
     if (c === 'M' || c === 'L') { x = +tk[i++]; y = +tk[i++]; if (c === 'M') { sx = x; sy = y; if (pts.length) pts.push(null); } pts.push([x, y]); if (c === 'M') c = 'L'; }
     else if (c === 'm' || c === 'l') { x += +tk[i++]; y += +tk[i++]; pts.push([x, y]); if (c === 'm') c = 'l'; }
     else if (c === 'H') { x = +tk[i++]; pts.push([x, y]); } else if (c === 'h') { x += +tk[i++]; pts.push([x, y]); }
     else if (c === 'V') { y = +tk[i++]; pts.push([x, y]); } else if (c === 'v') { y += +tk[i++]; pts.push([x, y]); }
     else if (c === 'Z' || c === 'z') { pts.push([sx, sy]); } else if (c === 'C') { i += 4; x = +tk[i++]; y = +tk[i++]; pts.push([x, y]); } else i++; }
   return pts; };
 const edges = [];
 for (const el of svg.querySelectorAll('line, path')) {
   if (inMarker(el) || el.classList.contains('bridge')) continue;
   const fill = el.getAttribute('fill'); if (el.tagName === 'path' && fill && fill !== 'none') continue;
   const pts = el.tagName === 'line' ? [[+el.getAttribute('x1'), +el.getAttribute('y1')], [+el.getAttribute('x2'), +el.getAttribute('y2')]] : parse(el.getAttribute('d'));
   edges.push({pts: pts.filter(p => p), sw: parseFloat(el.getAttribute('stroke-width') || '1'),
     ms: !!el.getAttribute('marker-start'), me: !!el.getAttribute('marker-end'), o: order.get(el),
     id: el.getAttribute('data-id') || el.tagName + order.get(el), net: el.getAttribute('data-net'),
     unrouted: el.getAttribute('data-unrouted') === '1'});
 }
 return {vbw: vb.width, vbh: vb.height, rects, texts, edges};
}"""


def _segs(pts):
    return [(pts[i], pts[i + 1]) for i in range(len(pts) - 1) if pts[i] != pts[i + 1]]


def _onseg(p, s, tol=1.0):
    (x1, y1), (x2, y2) = s
    px, py = p
    if abs(x1 - x2) < 1e-6:
        return abs(px - x1) <= tol and min(y1, y2) - tol <= py <= max(y1, y2) + tol
    if abs(y1 - y2) < 1e-6:
        return abs(py - y1) <= tol and min(x1, x2) - tol <= px <= max(x1, x2) + tol
    dx, dy = x2 - x1, y2 - y1
    t = max(0, min(1, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)))
    return ((x1 + t * dx - px) ** 2 + (y1 + t * dy - py) ** 2) ** 0.5 <= tol


def _seg_rect(s, r):
    """Length of segment s inside rect r = (x, y, w, h), sampled."""
    (x1, y1), (x2, y2) = s
    n, inside = 40, 0
    for i in range(n):
        t = (i + 0.5) / n
        x, y = x1 + t * (x2 - x1), y1 + t * (y2 - y1)
        if r[0] < x < r[0] + r[2] and r[1] < y < r[1] + r[3]:
            inside += 1
    return inside / n * ((x2 - x1) ** 2 + (y2 - y1) ** 2) ** 0.5


def _cross(a, b):
    (p1, p2), (p3, p4) = a, b

    def d(p, q, r):
        return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])

    d1, d2, d3, d4 = d(p3, p4, p1), d(p3, p4, p2), d(p1, p2, p3), d(p1, p2, p4)
    return ((d1 > 1e-6 and d2 < -1e-6) or (d1 < -1e-6 and d2 > 1e-6)) and (
        (d3 > 1e-6 and d4 < -1e-6) or (d3 < -1e-6 and d4 > 1e-6)
    )


def _simplify(P):
    """Merge collinear consecutive points: a straight run drawn as several segments is ONE run."""
    out = []
    for p in P:
        if out and abs(p[0] - out[-1][0]) < 1e-6 and abs(p[1] - out[-1][1]) < 1e-6:
            continue
        if len(out) >= 2:
            (ax, ay), (bx, by) = out[-2], out[-1]
            if (
                abs((bx - ax) * (p[1] - ay) - (by - ay) * (p[0] - ax)) < 1e-6
                and (bx - ax) * (p[0] - bx) + (by - ay) * (p[1] - by) >= 0
            ):
                out[-1] = p
                continue
        out.append(p)
    return out


MIN_CONTRAST = 4.5  # WCAG 2 AA for normal-size text: every figure text is 7-9 pt


def _rgb(css):
    """'rgb(r, g, b)' / 'rgba(r, g, b, a)' -> (r, g, b, a) in 0..1, or None (none, a url, a keyword)."""
    m = re.fullmatch(
        r"rgba?\(\s*([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\s*\)", (css or "").strip()
    )
    if not m:
        return None
    return tuple(float(v) / 255 for v in m.groups()[:3]) + (
        float(m.group(4)) if m.group(4) else 1.0,
    )


def contrast(c1, c2):
    """WCAG 2 contrast ratio of two (r, g, b, ...) colours in 0..1."""

    def lum(c):
        f = [v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4 for v in c[:3]]
        return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2]

    hi, lo = sorted((lum(c1), lum(c2)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def analyse(G, name, external=()):
    D = {k: [] for k in CHECKS}
    D["external"] = list(external)
    R, T, E = G["rects"], G["texts"], G["edges"]
    for e in E:
        e["pts"] = _simplify(e["pts"])
    D["unrouted"] = [e["id"] for e in E if e.get("unrouted")]
    # a line or path with fewer than two distinct points (no d, an empty d, a lone moveto) draws no
    # ink: nothing to judge, and every check below indexes its ends
    E = [e for e in E if not e.get("unrouted") and len(e["pts"]) >= 2]
    minpt = min((t["pt"] for t in T), default=99.0)
    if minpt < house.TEXT_FLOOR - 0.005:
        D["min-pt"].append(f"{minpt:.2f} pt")
    if G["vbw"] > house.W + 0.5:
        D["canvas-overflow"].append(f"{G['vbw']:.0f} u wide")

    def contains(r, q):
        return (
            q is not r
            and r["x"] <= q["x"] + 0.01
            and r["y"] <= q["y"] + 0.01
            and q["x"] + q["w"] <= r["x"] + r["w"] + 0.01
            and q["y"] + q["h"] <= r["y"] + r["h"] + 0.01
        )

    leaves = [r for r in R if not any(contains(r, q) for q in R)]
    for t in T:
        cx, cy = t["x"] + t["w"] / 2, t["y"] + t["h"] / 2
        c = sorted(
            [r for r in R if r["x"] <= cx <= r["x"] + r["w"] and r["y"] <= cy <= r["y"] + r["h"]],
            key=lambda r: r["w"] * r["h"],
        )
        if c:
            r = c[0]
            over = max(
                r["x"] - t["x"],
                t["x"] + t["w"] - r["x"] - r["w"],
                r["y"] - t["y"],
                t["y"] + t["h"] - r["y"] - r["h"],
            )
            if over > 0.6:
                D["text-outside"].append(f"{over:.1f}u {t['s']}")
            elif min(t["x"] - r["x"], r["x"] + r["w"] - t["x"] - t["w"]) < 1.8:
                D["crowded"].append(t["s"])
    for t in T:
        if (
            t["x"] < -0.5
            or t["y"] < -0.5
            or t["x"] + t["w"] > G["vbw"] + 0.5
            or t["y"] + t["h"] > G["vbh"] + 0.5
        ):
            D["text-clipped"].append(t["s"])
    for t in T:
        for r in R:
            ix = min(t["x"] + t["w"], r["x"] + r["w"]) - max(t["x"], r["x"])
            iy = min(t["y"] + t["h"], r["y"] + r["h"]) - max(t["y"], r["y"])
            inside = (
                r["x"] - 0.6 <= t["x"]
                and t["x"] + t["w"] <= r["x"] + r["w"] + 0.6
                and r["y"] - 0.6 <= t["y"]
                and t["y"] + t["h"] <= r["y"] + r["h"] + 0.6
            )
            if (
                ix > 0.8
                and iy > 1.1
                and not inside
                and not (ix >= t["w"] - 0.6 and iy >= t["h"] - 0.6)
            ):
                D["text-straddle"].append(f"{t['s']} / box {r['w']:.0f}x{r['h']:.0f}")
                break
    for t in T:
        tc = _rgb(t.get("tc"))
        cx, cy = t["x"] + t["w"] / 2, t["y"] + t["h"] / 2
        under = [
            r
            for r in R
            if r["o"] < t["o"]
            and r["x"] <= cx <= r["x"] + r["w"]
            and r["y"] <= cy <= r["y"] + r["h"]
            and _rgb(r.get("fc"))
            and _rgb(r["fc"])[3] * r.get("fo", 1) > 0.5
        ]
        if tc and under:
            bg = _rgb(max(under, key=lambda r: r["o"])["fc"])
            ratio = contrast(tc, bg)
            if ratio < MIN_CONTRAST:
                D["text-low-contrast"].append(f"{ratio:.2f}:1 {t['s']}")
    for i in range(len(T)):
        for j in range(i + 1, len(T)):
            a, b = T[i], T[j]
            ix = min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"])
            iy = min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"])
            if ix > 0.8 and iy > 1.1:
                D["text-overlap"].append(f"{a['s']} <> {b['s']}")
    par = list(range(len(E)))

    def find(i):
        while par[i] != i:
            par[i] = par[par[i]]
            i = par[i]
        return i

    S = [_segs(e["pts"]) for e in E]
    if all(e.get("net") for e in E):
        first = {}
        for i, e in enumerate(E):
            par[find(i)] = find(first.setdefault(e["net"], i))
    else:
        for i in range(len(E)):
            for j in range(len(E)):
                if i != j and any(
                    _onseg(p, s) for p in (E[i]["pts"][0], E[i]["pts"][-1]) for s in S[j]
                ):
                    par[find(i)] = find(j)

    def hv(s):
        return (
            "h"
            if abs(s[0][1] - s[1][1]) < 1e-6
            else ("v" if abs(s[0][0] - s[1][0]) < 1e-6 else "d")
        )

    for i in range(len(E)):
        for j in range(i + 1, len(E)):
            same = find(i) == find(j)
            for a in S[i]:
                for b in S[j]:
                    ka, kb = hv(a), hv(b)
                    if ka == kb != "d":
                        ax = 0 if ka == "h" else 1
                        c = 1 - ax
                        lo = max(min(a[0][ax], a[1][ax]), min(b[0][ax], b[1][ax]))
                        hi = min(max(a[0][ax], a[1][ax]), max(b[0][ax], b[1][ax]))
                        if hi - lo <= 2:
                            continue
                        gap = abs(a[0][c] - b[0][c]) - (E[i]["sw"] + E[j]["sw"]) / 2
                        if same:
                            if abs(a[0][c] - b[0][c]) >= 0.5 and gap < 3.0:
                                D["unbundled"].append(
                                    f"{E[i]['id']}||{E[j]['id']} gap {gap:.1f}u over {hi - lo:.0f}u"
                                )
                            continue
                        if abs(a[0][c] - b[0][c]) < 0.5:
                            D["shared-path"].append(f"{E[i]['id']}~{E[j]['id']} {hi - lo:.0f}u")
                        elif gap < 3.0:
                            D["clearance"].append(
                                f"{E[i]['id']}|{E[j]['id']} gap {gap:.1f}u over {hi - lo:.0f}u"
                            )
                    elif not same and _cross(a, b):
                        D["crossing"].append(f"{E[i]['id']} x {E[j]['id']}")
    bends = []
    for k, e in enumerate(E):
        b = sum(
            1 for i in range(1, len(S[k])) if hv(S[k][i]) != hv(S[k][i - 1]) or hv(S[k][i]) == "d"
        )
        bends.append(b)
        if b > 3:
            D["bends>3"].append(f"{e['id']} {b}")
        if any(
            not (-0.6 <= x <= G["vbw"] + 0.6 and -0.6 <= y <= G["vbh"] + 0.6) for x, y in e["pts"]
        ):
            D["edge-outside"].append(e["id"])
        for s in S[k]:
            for r in leaves:
                L = _seg_rect(s, (r["x"] + 1.5, r["y"] + 1.5, r["w"] - 3, r["h"] - 3))
                if L > 0.5:
                    D["edge-in-box"].append(f"{e['id']} {L:.0f}u")
                    break
            for t in T:
                if _seg_rect(s, (t["x"] + 0.3, t["y"] + 0.3, t["w"] - 0.6, t["h"] - 0.6)) > 0.3:
                    D["edge-in-text"].append(f"{e['id']} / {t['s']}")
                    break
        heads = []
        if e["me"] and len(e["pts"]) > 1:
            heads.append((e["pts"][-1], e["pts"][-2]))
        if e["ms"] and len(e["pts"]) > 1:
            heads.append((e["pts"][0], e["pts"][1]))
        hl = 6 * e["sw"] * 0.9
        if e["ms"] and e["me"] and len(S[k]) == 1:
            p, q = S[k][0]
            if ((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2) ** 0.5 < 2 * hl:
                D["arrow-collide"].append(e["id"])
        for tip, prev in heads:
            L = ((tip[0] - prev[0]) ** 2 + (tip[1] - prev[1]) ** 2) ** 0.5
            if L < hl:
                D["arrow-short"].append(f"{e['id']} {L:.1f}u<{hl:.1f}u")
            for r in leaves:
                dep = min(
                    tip[0] - r["x"],
                    r["x"] + r["w"] - tip[0],
                    tip[1] - r["y"],
                    r["y"] + r["h"] - tip[1],
                )
                if dep > 2:
                    D["arrow-buried"].append(f"{e['id']} {dep:.1f}u")
            mid = (
                tip[0] - (tip[0] - prev[0]) / max(L, 1e-9) * hl / 2,
                tip[1] - (tip[1] - prev[1]) / max(L, 1e-9) * hl / 2,
            )
            for r in R:
                if (
                    r["o"] > e["o"]
                    and r["fill"] not in (None, "none")
                    and r["x"] < mid[0] < r["x"] + r["w"]
                    and r["y"] < mid[1] < r["y"] + r["h"]
                ):
                    D["arrow-hidden"].append(e["id"])
                    break
            if not (0 <= tip[0] <= G["vbw"] and 0 <= tip[1] <= G["vbh"]):
                D["arrow-outside"].append(e["id"])
    rooms = [r for r in R if r not in leaves]

    def inr(p, r, t=0.6):
        return (
            r["x"] - t <= p[0] <= r["x"] + r["w"] + t and r["y"] - t <= p[1] <= r["y"] + r["h"] + t
        )

    for k, e in enumerate(E):
        if len(e["pts"]) < 2:
            continue
        for r in rooms:
            if inr(e["pts"][0], r) and inr(e["pts"][-1], r):
                out = [
                    s
                    for s in S[k]
                    if not all(
                        inr(((1 - t) * s[0][0] + t * s[1][0], (1 - t) * s[0][1] + t * s[1][1]), r)
                        for t in (0, 0.25, 0.5, 0.75, 1)
                    )
                ]
                if out:
                    D["room-escape"].append(f"{e['id']} leaves {r['w']:.0f}x{r['h']:.0f} room")
                    break
    total = sum(len(v) for v in D.values())
    return {
        "name": name,
        "defects": total,
        "by": {k: len(v) for k, v in D.items() if v},
        "detail": {k: v[:6] for k, v in D.items() if v},
        "min_pt": round(minpt, 2),
        "width_u": round(G["vbw"], 1),
        "height_u": round(G["vbh"], 1),
        "text_right_u": round(max((t["x"] + t["w"] for t in T), default=0.0), 1),
        "bends_total": sum(bends),
        "bends_max": max(bends) if bends else 0,
        "edges": len(E),
    }


class Checker:
    """One Chromium for many SVGs."""

    def __enter__(self):
        self.pw = sync_playwright().start()
        self.b = self.pw.chromium.launch()
        self.pg = self.b.new_page()
        self.requests = []
        self.pg.route("**/*", self._refuse)
        return self

    def _refuse(self, route):
        """Nothing leaves the page: every request is recorded and aborted."""
        self.requests.append(route.request.url)
        route.abort()

    def __exit__(self, *a):
        self.b.close()
        self.pw.stop()

    def check(self, svg_text, name):
        self.requests = []
        self.pg.set_content(
            f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head>'
            f'<body style="margin:0">{svg_text}</body></html>'
        )
        self.pg.evaluate("document.fonts.ready.then(() => 1)")
        geo = self.pg.evaluate(JS)
        ext = external_refs(svg_text) + [f"request {u[:60]}" for u in self.requests]
        return analyse(geo, name, ext)


def run(path, out=None):
    """press fig check: the report for one SVG (written to `out` when given)."""
    with Checker() as c:
        r = c.check(open(path, encoding="utf-8").read(), path)
    if out:
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(r, fh, indent=1, ensure_ascii=False)
    return r
