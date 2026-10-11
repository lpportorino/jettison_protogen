"""One planted defect per figure-checker check, made from GOOD figures the selftest just picked.

plants(figs) -> {check: (base name, planted svg text)}, where figs = {name: (svg text, geometry)}.
Every plant is placed from the figure's own geometry (a box, a text, an edge, an empty spot), so
it stays meaningful when the placer picks a different layout. The selftest demands that each
check counts MORE on its plant than on the base, and that every base counts zero."""

import json
import re

W = 476
END = "</svg>"


def add(svg, el):
    return svg.replace(END, el + "\n" + END)


def path(d, net, extra=""):
    return f'<path class="edge" data-id="plant" data-net="{net}" d="{d}" fill="none" stroke="#1f3a5f" stroke-width="1.2"{extra}/>'


def box(g, bid):
    return next(b for b in g["boxes"] if b["id"] == bid)


def edge(g, eid):
    return next(e for e in g["edges"] if e["id"] == eid)


def free_spot(g, w, h):
    """Top-left of an empty w x h rectangle (4-unit margin from every box, edge, bus and label)."""
    obst = [(b["x"], b["y"], b["w"], b["h"]) for b in g["boxes"]]
    for e in g["edges"]:
        xs, ys = [p[0] for p in e["points"]], [p[1] for p in e["points"]]
        obst.append((min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)))
    for b in g["buses"]:
        x1, y1, x2, y2 = b["line"]
        obst.append((min(x1, x2) - 2, min(y1, y2) - 2, abs(x2 - x1) + 4, abs(y2 - y1) + 4))
        if b["label"]:
            lb = b["label"]
            obst.append((lb["x"], lb["y"], lb["w"], lb["h"]))
    H = max(b["y"] + b["h"] for b in g["boxes"])
    for y in range(4, int(H - h), 2):
        for x in range(4, W - w - 4, 2):
            if all(
                x + w + 4 <= ox or ox + ow + 4 <= x or y + h + 4 <= oy or oy + oh + 4 <= y
                for ox, oy, ow, oh in obst
            ):
                return x, y
    raise SystemExit(f"fig_plants: no free {w}x{h} spot in the base figure")


def text_line(svg, s):
    """The <text> element whose content is s."""
    m = re.search(r"<text [^>]*>" + re.escape(s) + "</text>", svg)
    if not m:
        raise SystemExit(f"fig_plants: no text {s!r} in the base figure")
    return m.group(0)


def set_attr(el, k, v):
    return re.sub(rf' {k}="[^"]*"', f' {k}="{v}"', el, count=1)


def plants(figs):
    flow, flow_geom = figs["repair-flow"]
    cart, cart_geom = figs["book-cart"]
    library, library_geom = figs["library-map"]
    P = {}
    t = text_line(flow, "Repair the binding")
    P["min-pt"] = ("repair-flow", flow.replace(t, set_attr(t, "font-size", "6.5")))
    P["canvas-overflow"] = (
        "repair-flow",
        re.sub(r'viewBox="0 0 476 ', 'viewBox="0 0 520 ', flow, count=1),
    )
    b3 = box(flow_geom, "bind")
    P["text-outside"] = (
        "repair-flow",
        flow.replace(t, set_attr(t, "x", f"{b3['x'] + b3['w'] - 40}")),
    )
    t1 = text_line(flow, "Inspect")
    t2 = text_line(flow, "Clean")
    m1 = re.search(r' y="([^"]+)"', t1).group(1)
    P["text-overlap"] = ("repair-flow", flow.replace(t2, set_attr(t2, "y", m1)))
    P["crowded"] = (
        "repair-flow",
        flow.replace(t1, set_attr(t1, "x", f"{box(flow_geom, 'inspect')['x'] + 1.4}")),
    )
    trunk = [e for e in flow_geom["edges"] if e["id"] == "ready"][0]["points"]
    tx = trunk[1][0]
    y0, y1 = sorted([trunk[1][1], trunk[2][1]])
    P["clearance"] = (
        "repair-flow",
        add(flow, path(f"M{tx + 2.5} {y0 + 4} L{tx + 2.5} {y1 - 4}", "plant")),
    )
    e23 = re.search(r'<path class="edge" data-id="clean-bind"[^>]*/>', flow).group(0)
    P["shared-path"] = (
        "repair-flow",
        add(flow, set_attr(set_attr(e23, "data-net", "plant"), "data-id", "plant")),
    )
    b7 = box(flow_geom, "shelve")
    P["edge-in-box"] = (
        "repair-flow",
        add(
            flow,
            path(f"M{b7['x'] + 30} {b7['y'] + 3} L{b7['x'] + 30} {b7['y'] + b7['h'] - 3}", "plant"),
        ),
    )
    ft = box(flow_geom, "care-note")
    P["edge-in-text"] = (
        "repair-flow",
        add(
            flow,
            path(
                f"M{ft['x'] + 20} {ft['y'] + ft['h'] / 2} L{ft['x'] + 140} {ft['y'] + ft['h'] / 2}",
                "plant",
            ),
        ),
    )
    x, y = free_spot(cart_geom, 70, 50)
    P["crossing"] = (
        "book-cart",
        add(
            add(cart, path(f"M{x} {y + 25} L{x + 70} {y + 25}", "plantA")),
            path(f"M{x + 35} {y} L{x + 35} {y + 50}", "plantB"),
        ),
    )
    P["bends>3"] = (
        "book-cart",
        add(
            cart,
            path(f"M{x} {y} H{x + 15} V{y + 10} H{x + 30} V{y + 20} H{x + 45} V{y + 30}", "plant"),
        ),
    )
    P["arrow-short"] = (
        "book-cart",
        add(cart, path(f"M{x} {y + 10} H{x + 50} V{y + 13}", "plant", ' marker-end="url(#arr)"')),
    )
    P["arrow-outside"] = (
        "book-cart",
        add(cart, path(f"M{W - 60} {y + 10} H{W + 6}", "plant", ' marker-end="url(#arr)"')),
    )
    P["edge-outside"] = ("book-cart", add(cart, path(f"M40 {y + 10} H-8 V{y + 40}", "plant")))
    P["unrouted"] = (
        "book-cart",
        add(
            cart,
            '<path class="edge" data-id="rat" data-net="rat" data-unrouted="1" '
            f'd="M{x} {y} L{x + 60} {y}" fill="none" stroke="#a8352f" stroke-width="1.2"/>',
        ),
    )
    op = edge(library_geom, "entrance")["points"]
    (ax, ay), (_bx, _by) = op[0], op[-1]
    old = re.search(r'<path class="edge" data-id="entrance"[^>]*/>', library).group(0)
    P["arrow-collide"] = (
        "library-map",
        library.replace(old, re.sub(r' d="[^"]*"', f' d="M{ax} {ay} L{ax} {ay + 9}"', old)),
    )
    e01 = edge(flow_geom, "receive-inspect")["points"]
    old = re.search(r'<path class="edge" data-id="receive-inspect"[^>]*/>', flow).group(0)
    (px, py) = e01[-1]
    P["arrow-buried"] = (
        "repair-flow",
        flow.replace(old, re.sub(r' d="[^"]*"', f' d="M{px} {e01[0][1]} L{px} {py + 10}"', old)),
    )
    P["arrow-hidden"] = (
        "repair-flow",
        add(flow, f'<rect x="{px - 6}" y="{py - 6}" width="12" height="6" fill="#d8f0ee"/>'),
    )
    lab = text_line(library, "Quiet reading area")
    mod = box(library_geom, "reading-room")
    P["text-straddle"] = (
        "library-map",
        library.replace(
            lab,
            set_attr(lab, "x", f"{mod['x']}").replace(
                re.search(r' y="[^"]*"', lab).group(0), f' y="{mod["y"] + 30}"'
            ),
        ),
    )
    fl = text_line(
        flow,
        "This invented workflow illustrates optional paths through a repair desk.",
    )
    P["text-clipped"] = ("repair-flow", flow.replace(fl, set_attr(fl, "x", "-14")))
    P["unbundled"] = (
        "repair-flow",
        add(
            flow,
            f'<path class="edge" data-id="plant" data-net="inspect:e" d="M{tx + 2} {y0 + 2} '
            f'L{tx + 2} {y1 - 2}" fill="none" stroke="#5b6875" stroke-width="1.2" stroke-dasharray="3 2"/>',
        ),
    )
    host = box(library_geom, "reading-room")
    P["room-escape"] = (
        "library-map",
        add(
            library,
            path(
                f"M{host['x'] + 20} {host['y'] + 60} V{host['y'] - 6} H{host['x'] + 60} "
                f"V{host['y'] + 60}",
                "plant",
            ),
        ),
    )
    P["external"] = (
        "repair-flow",
        add(flow, '<image href="http://203.0.113.7/x.png" x="0" y="0" width="4" height="4"/>'),
    )
    # a label inside its box drawn in a pale grey: about 1.3:1 against the box fill
    P["text-low-contrast"] = ("repair-flow", flow.replace(t1, set_attr(t1, "fill", "#b8c4cc")))
    for k, (base, svg) in P.items():
        if svg == figs[base][0]:
            raise SystemExit(f"fig_plants: plant {k} did not change {base}")
    return P


if __name__ == "__main__":
    import sys

    figs = {}
    for name in ("library-map", "book-cart", "repair-flow"):
        rec = json.load(open(f"{sys.argv[1]}/{name}.pick.json"))
        figs[name] = (open(f"{sys.argv[1]}/{name}.svg", encoding="utf-8").read(), rec["geometry"])
    for k, (base, svg) in plants(figs).items():
        open(f"{sys.argv[2]}/{k}.svg", "w", encoding="utf-8").write(svg)
        print(k, base)
