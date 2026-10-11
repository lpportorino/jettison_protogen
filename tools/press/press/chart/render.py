"""One chart renderer for three kinds -- bar, stacked, timeline -- over measured text.

Every text is measured with fig.measure (HarfBuzz on the Inter files Chromium draws with) BEFORE it is
placed; every element is written by fig.render's element writers using the house constants
(house.json: canvas and print column, text styles, colours, line height and type floor). Text
is never scaled: notes and panels WRAP to the canvas width, and a chart that cannot fit -- a label
that leaves its bar no room, a segment label wider than its segment, a word wider than the canvas,
two boxes on one row that collide -- is refused with what it needs (Unfit, exit 4); one taller than
the house page budget is refused with its height (exit 5). A pure function of the description:
no randomness, fixed number formatting, so two runs write identical bytes.

Text styles (house.json `text`): titles `h`, body `n`, muted `m`, bold `b`, a break line `H`."""

import math

from fig import house
from fig.measure import width
from fig.render import path_el, rect_el, svg_open, text_el

from .desc import number

W = house.W
MARGIN = house.PAD  # free space kept between any text and the canvas edge
TW = W - MARGIN  # the right-most a text may reach
BODY = house.TEXT["n"][0]
LEAD = round(BODY * house.LINE, 2)  # baseline advance of a body line
MIN_BAR = 8  # the shortest bar that still reads as a bar, units


class Unfit(Exception):
    """The chart cannot be drawn at the house sizes. code 4: something does not fit the width;
    code 5: over the page budget. `needs` says what it would take."""

    def __init__(self, code, message, needs):
        super().__init__(message)
        self.code, self.needs = code, needs


def _style(key, colour=None):
    """(size, weight, colour hex) of a text style, its colour optionally replaced by an element tone
    -- drawn in that tone's INK variant (house.json text-ink), so small text keeps its contrast."""
    size, weight, col = house.TEXT[key]
    return size, weight, house.COLORS[house.ink(colour) if colour else col]


def _w(s, key):
    size, weight, _ = house.TEXT[key]
    return width(s, size, weight)


def _contrast(a, b):
    """WCAG 2 contrast ratio of two colour hexes."""

    def lum(h):
        def ch(c):
            return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

        r, g, bl = (int(h[i : i + 2], 16) / 255 for i in (1, 3, 5))
        return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(bl)

    hi, lo = sorted((lum(a), lum(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


class Out:
    def __init__(self):
        self.el = []
        self.n = 0

    def text(self, x, y, s, key="n", colour=None, anchor="start"):
        size, weight, fill = _style(key, colour)
        self.el.append(text_el(round(x, 2), round(y, 2), size, weight, fill, s, anchor))

    def rect(self, x, y, w, h, fill, stroke=None, rx=0, cls="mark"):
        self.n += 1
        self.el.append(
            rect_el(
                cls,
                f"{cls}{self.n}",
                round(x, 2),
                round(y, 2),
                round(w, 2),
                round(h, 2),
                rx,
                house.COLORS[fill] if fill else None,
                house.COLORS[stroke] if stroke else None,
            )
        )

    def path(self, pts, col, sw, net, dash=None, arrow="none"):
        self.n += 1
        self.el.append(
            path_el(
                [[round(x, 2), round(y, 2)] for x, y in pts],
                col,
                sw,
                dash,
                arrow,
                "edge",
                f"{net}-{self.n}",
                net,
            )
        )

    def svg(self, height):
        if height > house.BUDGET_H:
            raise Unfit(
                5,
                f"the chart is {math.ceil(height)} units tall; the page budget is {house.BUDGET_H}",
                {"height_u": math.ceil(height), "budget_u": house.BUDGET_H},
            )
        return "\n".join(svg_open(height) + self.el + ["</svg>"]) + "\n"


def wrap(s, avail, key, where):
    """Words of `s` into lines no wider than `avail` units; a word wider than that is refused."""
    lines, cur = [], ""
    for word in s.split():
        if _w(word, key) > avail:
            raise Unfit(
                4,
                f"{where}: the word {word!r} is {_w(word, key):.0f} units wide; the line is {avail:.0f}",
                {
                    "element": where,
                    "width_u": math.ceil(_w(word, key)),
                    "avail_u": math.floor(avail),
                },
            )
        t = f"{cur} {word}" if cur else word
        if cur and _w(t, key) > avail:
            lines.append(cur)
            cur = word
        else:
            cur = t
    return lines + ([cur] if cur else [])


def _notes(o, y, items, where):
    for i, n in enumerate(items):
        for ln in wrap(n, TW, "m", f"{where}[{i}]"):
            y += LEAD
            o.text(0, y, ln, "m")
    return y


def bar(d):
    data = d["data"]
    o, y = Out(), 0.0
    for ln in wrap(data["title"], TW, "h", "data.title"):
        y += house.TEXT["h"][0] * house.LINE
        o.text(0, y, ln, "h")
    rows = data["rows"]
    vals = [number(r["value"], "value") for r in rows]
    x0 = math.ceil(max(_w(r["label"], "n") for r in rows) + 6)

    def tail(r, txt):
        # + 1: a glyph's drawn box can overhang its shaped advance by a fraction of a unit
        t = 5 + _w(txt, "b") + 1
        return t + (4 + _w("· " + r["note"], "m") if r.get("note") else 0)

    for (_v, txt), r in zip(vals, rows, strict=False):
        room = TW - x0 - tail(r, txt)
        if room < MIN_BAR:
            widest = max(rows, key=lambda q: _w(q["label"], "n"))["label"]
            raise Unfit(
                4,
                f"row {r['label']!r}: the label column ({x0} u, set by the label {widest!r}) and this "
                f"row's value and note ({tail(r, txt):.0f} u) leave {room:.0f} u for its bar in the {TW} units a text may reach "
                f"canvas, under the {MIN_BAR} u a bar needs; shorten a label or the note by "
                f"{math.ceil(MIN_BAR - room)} u",
                {
                    "element": r["label"],
                    "label_column_set_by": widest,
                    "short_by_u": math.ceil(MIN_BAR - room),
                },
            )
    scale = (
        min(
            (TW - x0 - tail(r, txt)) / float(v)
            for (v, txt), r in zip(vals, rows, strict=False)
            if v > 0
        )
        if any(v > 0 for v, _ in vals)
        else 0.0
    )
    y += 6
    for (v, txt), r in zip(vals, rows, strict=False):
        w = float(v) * scale
        o.text(x0 - 6, y + 9, r["label"], "n", anchor="end")
        if w > 0:
            o.rect(x0, y, w, 12, r["tone"], rx=1.5, cls="bar")
        vx = x0 + w + 5
        o.text(vx, y + 9, txt, "b", r["tone"] if r.get("emphasis") else None)
        if r.get("note"):
            o.text(vx + _w(txt, "b") + 4, y + 9, "· " + r["note"], "m")
        y += 17
    y = _notes(o, y + 3, data.get("notes", []), "data.notes")
    return o.svg(y + 4)


def stacked(d):
    data = d["data"]
    o, y = Out(), 0.0
    H = 22
    for bi, b in enumerate(data["bars"]):
        where = f"data.bars[{bi}]"
        for ln in wrap(b["title"], TW, "h", f"{where}.title"):
            y += house.TEXT["h"][0] * house.LINE
            o.text(0, y, ln, "h")
        y += 4
        total = float(number(b["total"], "total")[0])
        span = W - 1
        o.rect(0.5, y, span, H, "WHITE", "MUTED", cls="frame")
        fills = {}

        def place(segs, x, label_from=None, *, total=total, span=span, y=y, fills=fills):
            for s in segs:
                w = float(number(s["size"], "size")[0]) / total * span
                o.rect(x, y, w, H, s["fill"], s.get("outline"), cls="segment")
                fills[s["id"]] = s
                lx0 = x
                if "inside" in s:
                    lx0 = place(s["inside"], x)
                if s.get("label"):
                    room = x + w - lx0
                    lw = _w(s["label"], "n")
                    if lw + 6 > room:
                        raise Unfit(
                            4,
                            f"segment {s['id']!r}: its label {s['label']!r} is {lw:.0f} u wide and the segment "
                            f"leaves {room:.0f} u; drop the label and name it in the legend",
                            {
                                "element": s["id"],
                                "width_u": math.ceil(lw + 6),
                                "avail_u": math.floor(room),
                            },
                        )
                    fill = house.COLORS[s["fill"]]
                    col = max(("WHITE", "INK"), key=lambda c: _contrast(house.COLORS[c], fill))
                    o.text(lx0 + room / 2, y + 14.5, s["label"], "n", col, "middle")
                x += w
            return x

        end = place(b["segments"], 0.5)
        free = W - 0.5 - end
        if b.get("free-label"):
            lw = _w(b["free-label"], "m")
            if lw + 6 > free:
                raise Unfit(
                    4,
                    f"{where}.free-label: {lw:.0f} u wide; the free part is {free:.0f} u",
                    {
                        "element": f"{where}.free-label",
                        "width_u": math.ceil(lw + 6),
                        "avail_u": math.floor(free),
                    },
                )
            o.text(end + free / 2, y + 14.5, b["free-label"], "m", anchor="middle")
        y += H + 12
        x, colw = 0.0, W / 3
        for j, it in enumerate(b.get("legend", [])):
            s = fills[it["of"]]
            tw = _w(it["text"], "n") + 10
            if tw > TW:
                raise Unfit(
                    4,
                    f"{where}.legend[{j}]: {tw:.0f} u wide; a text may reach {TW}",
                    {"element": f"{where}.legend[{j}]", "width_u": math.ceil(tw), "avail_u": TW},
                )
            if x + tw > TW:
                x, y = 0.0, y + LEAD + 2
            o.rect(x + 0.5, y - 6.5, 7, 7, s["fill"], s.get("outline"), cls="swatch")
            o.text(x + 10, y, it["text"], "n")
            x += colw * max(1, math.ceil(tw / colw))
        if b.get("note"):
            y = _notes(o, y + 2, [b["note"]], f"{where}.note")
        y += 18
    y = _notes(o, y - 10, data.get("notes", []), "data.notes") if data.get("notes") else y - 18
    return o.svg(y + 4)


def timeline(d):
    data = d["data"]
    o = Out()
    L = data["lifelines"]
    n = len(L)
    slot = W / n
    xs = {ll["id"]: slot * (2 * i + 1) / 2 for i, ll in enumerate(L)}
    for ll in L:
        for key, s in (("h", ll["title"]), ("m", ll.get("sub"))):
            if s and _w(s, key) > slot - 4:
                raise Unfit(
                    4,
                    f"lifeline {ll['id']!r}: {s!r} is {_w(s, key):.0f} u wide; each of the {n} lifelines "
                    f"has {slot - 4:.0f} u",
                    {
                        "element": ll["id"],
                        "width_u": math.ceil(_w(s, key)),
                        "avail_u": math.floor(slot - 4),
                    },
                )
        o.text(xs[ll["id"]], 9, ll["title"], "h", anchor="middle")
        if ll.get("sub"):
            o.text(xs[ll["id"]], 19, ll["sub"], "m", anchor="middle")
    top = y = 24.0
    life_end = None
    mark = len(o.el)  # the lifelines go here, under everything below
    for i, st in enumerate(data["steps"]):
        where = f"data.steps[{i}]"
        k = st["step"]
        if k == "msg":
            a, b = xs[st["from"]], xs[st["to"]]
            tone = st.get("tone", "NAVY")
            lines = st["text"]
            span = abs(b - a) - 6
            widest = max(_w(t, "n") for t in lines)
            y += LEAD * len(lines) + 6
            tcol = st.get("text-tone", "INK" if tone == "NAVY" else tone)
            if widest <= span:
                for j, t in enumerate(lines):
                    o.text((a + b) / 2, y - 6 - LEAD * (len(lines) - 1 - j), t, "n", tcol, "middle")
            else:
                x = min(a, b) + 2
                if x + widest > TW:
                    raise Unfit(
                        4,
                        f"{where}: the message text is {widest:.0f} u wide; from its sender to the margin "
                        f"there are {TW - x:.0f} u",
                        {
                            "element": where,
                            "width_u": math.ceil(widest),
                            "avail_u": math.floor(TW - x),
                        },
                    )
                for j, t in enumerate(lines):
                    o.text(x, y - 6 - LEAD * (len(lines) - 1 - j), t, "n", tcol)
            sgn = 1 if b > a else -1
            o.path(
                [[a + sgn, y], [b - 2 * sgn, y]],
                tone,
                1.3,
                f"msg{i}",
                house.EDGE_LOOKS["dashed"][2] if st.get("look") == "dashed" else None,
                "end",
            )
            y += 6
        elif k == "box":
            x = xs[st["at"]]
            tone = st.get("tone", "TEAL")
            rows = [(t, "b", tone) for t in st["title"]] + (
                [(st["sub"], "m", None)] if st.get("sub") else []
            )
            bw = max(_w(t, key) for t, key, _ in rows) + 16
            if bw > W - 1:
                raise Unfit(
                    4,
                    f"{where}: the box is {bw:.0f} u wide; the canvas holds {W - 1}",
                    {"element": where, "width_u": math.ceil(bw), "avail_u": W - 1},
                )
            if life_end is None:
                life_end = y
            bx = min(max(x - bw / 2, 0.5), W - 0.5 - bw)
            h = 8 + LEAD * len(rows) + 2
            o.rect(bx, y, bw, h, "HUB", tone, 3, cls="box")
            for j, (t, key, col) in enumerate(rows):
                o.text(bx + bw / 2, y + 4 + LEAD * (j + 1), t, key, col, "middle")
            y += h + 10
        elif k == "break":
            if life_end is None:
                life_end = y
            o.rect(0, y, W, 2, "RED", cls="rule")
            size = house.TEXT["H"][0]
            for ln in wrap(st["text"], TW - 4, "H", where):
                y += size * house.LINE
                o.text(4, y + 4, ln, "H", "RED")
            y += 14
        elif k == "boxes":
            boxes = []
            for it in st["items"]:
                rows = [(t, "b", "RED") for t in it["title"]] + (
                    [(it["sub"], "m", None)] if it.get("sub") else []
                )
                bw = max(_w(t, key) for t, key, _ in rows) + 14
                if bw > W - 1:
                    raise Unfit(
                        4,
                        f"{where}: the box on {it['at']!r} is {bw:.0f} u wide; the canvas holds {W - 1}",
                        {
                            "element": f"{where} ({it['at']})",
                            "width_u": math.ceil(bw),
                            "avail_u": W - 1,
                        },
                    )
                x = xs[it["at"]]
                boxes.append([min(max(x - bw / 2, 0.5), W - 0.5 - bw), bw, rows, it["at"]])
            boxes.sort(key=lambda bb: bb[0])
            for p, q in zip(boxes, boxes[1:], strict=False):
                if p[0] + p[1] + 4 > q[0]:
                    over = p[0] + p[1] + 4 - q[0]
                    raise Unfit(
                        4,
                        f"{where}: the boxes on {p[3]!r} and {q[3]!r} overlap by {over:.0f} u; shorten a title",
                        {"element": where, "short_by_u": math.ceil(over)},
                    )
            h = 8 + LEAD * max(len(bb[2]) for bb in boxes) + 6
            for bx, bw, rows, _ in boxes:
                o.rect(bx, y, bw, h, "REDWASH", "RED", 3, cls="box")
                for j, (t, key, col) in enumerate(rows):
                    o.text(bx + bw / 2, y + 4 + LEAD * (j + 1), t, key, col, "middle")
            y += h + 10
        else:
            ls = wrap(st["text"], W - 16, "n", where)
            h = 9 + LEAD * len(ls)
            o.rect(0.5, y, W - 1, h, "WASH", "LINE", 3, cls="panel")
            for j, t in enumerate(ls):
                o.text(8, y + 4 + LEAD * (j + 1), t, "n")
            y += h + 1
    body, o.el = o.el[mark:], o.el[:mark]
    for ll in L:  # lifelines first, so labels and boxes paint over them
        o.path(
            [[xs[ll["id"]], top], [xs[ll["id"]], life_end if life_end is not None else y]],
            "LINE",
            1,
            f"life-{ll['id']}",
        )
    o.el += body
    return o.svg(y + 1)


def render(d):
    """-> SVG text for a validated chart description; raises Unfit."""
    return {"bar": bar, "stacked": stacked, "timeline": timeline}[d["chart"]](d)
