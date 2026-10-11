"""One placement model, two solver backends.

`encode(level, backend)` writes every rule of one level of the figure — the description's own
constraints plus the house rules (canvas width, clearance between boxes, a bus spine with every
box on one side of it, arrow room along every edge, the corridor a loop edge needs) — against a
small backend interface. The CP-SAT backend (`Cp`) is the placer. The Z3 backend (`Z3`) exists
only to explain an unsatisfiable level: every rule goes in behind its own tracked literal, named
in the description's own terms, and Z3's minimised unsat core is the explanation. Writing the
rules once is what keeps the explanation honest: it names the rules the placer actually used.

Coordinates are integer units (1 unit = 1 pt). A level's members are its direct children; an
element nested deeper is reached through the member that holds it: its coordinates are the
member's coordinates plus an offset fixed per shape of that member, so a rule may name any
element inside the level (affine in the member's position and its shape literals)."""

import z3
from ortools.sat.python import cp_model

from . import house


class Cp:
    """CP-SAT: the placer. Tags are ignored (CP-SAT gives no minimal cores)."""

    name = "cp-sat"

    def __init__(self):
        self.m = cp_model.CpModel()

    def int(self, name, lo, hi):
        return self.m.NewIntVar(lo, hi, name)

    def bool(self, name):
        return self.m.NewBoolVar(name)

    def neg(self, b):
        return b.Not()

    def b2i(self, b):
        return b

    def force(self, lit, tag):
        self.m.AddBoolAnd([lit])

    def add(self, ct, tag, when=()):
        c = self.m.Add(ct)
        if when:
            c.OnlyEnforceIf(list(when))

    def any_of(self, lits, tag, when=()):
        c = self.m.AddBoolOr(list(lits))
        if when:
            c.OnlyEnforceIf(list(when))

    def pick_one(self, lits, tag):
        self.m.AddExactlyOne(list(lits))

    # Min/max are written out with a selector literal per argument rather than with
    # AddMinEquality/AddMaxEquality: in OR-Tools 9.12.4544 AddMinEquality mishandles an argument
    # with a constant offset (min(y + 128) with y >= 0 came back INFEASIBLE; min(2y + 128,
    # 2z + 146) came back 0), which made sound levels read as unsatisfiable.
    def _sel(self, t, exprs, le):
        exprs = list(exprs)
        sel = [self.m.NewBoolVar("") for _ in exprs]
        self.m.AddExactlyOne(sel)
        for e, s in zip(exprs, sel, strict=False):
            self.m.Add(t <= e if le else t >= e)
            self.m.Add(t == e).OnlyEnforceIf(s)

    def min_eq(self, t, exprs):
        self._sel(t, exprs, True)

    def max_eq(self, t, exprs):
        self._sel(t, exprs, False)

    def abs_eq(self, t, e):
        self.m.AddAbsEquality(t, e)

    def rects(self, rects, clear):
        """Redundant global no-overlap over the level's boxes (propagation strength only)."""
        xs, ys = [], []
        for i, (x, y, w, h) in enumerate(rects):
            ex = self.m.NewIntVar(-(10**6), 10**6, f"ex{i}")
            ey = self.m.NewIntVar(-(10**6), 10**6, f"ey{i}")
            xs.append(self.m.NewIntervalVar(x, w + clear, ex, f"ix{i}"))
            ys.append(self.m.NewIntervalVar(y, h + clear, ey, f"iy{i}"))
        self.m.AddNoOverlap2D(xs, ys)


class Z3:
    """Z3: only to explain. Every tagged rule is guarded by a Bool named after the rule."""

    name = "z3"

    def __init__(self):
        self.s = z3.Solver()
        self.tags = {}  # tag text -> z3 Bool

    def int(self, name, lo, hi):
        """Lower bound only: every upper limit that can bind (the canvas width, the page) is a
        tagged rule, so it can appear in an explanation instead of hiding in a domain."""
        v = z3.Int(name)
        self.s.add(v >= lo)
        return v

    def bool(self, name):
        return z3.Bool(name)

    def neg(self, b):
        return z3.Not(b)

    def b2i(self, b):
        return z3.If(b, 1, 0)

    def force(self, lit, tag):
        self.add(lit, tag)

    def _guard(self, tag, when):
        g = list(when)
        if tag is not None:
            g.append(self.tags.setdefault(tag, z3.Bool(f"@{len(self.tags)}")))
        return g

    def add(self, ct, tag, when=()):
        g = self._guard(tag, when)
        self.s.add(z3.Implies(z3.And(g), ct) if g else ct)

    def any_of(self, lits, tag, when=()):
        self.add(z3.Or(list(lits)), tag, when)

    def pick_one(self, lits, tag):
        self.add(z3.Or(list(lits)), tag)
        self.s.add(z3.AtMost(*lits, 1))

    def min_eq(self, t, exprs):
        exprs = list(exprs)
        self.s.add(*[t <= e for e in exprs], z3.Or([t == e for e in exprs]))

    def max_eq(self, t, exprs):
        exprs = list(exprs)
        self.s.add(*[t >= e for e in exprs], z3.Or([t == e for e in exprs]))

    def abs_eq(self, t, e):
        self.s.add(t == z3.If(e >= 0, e, -e))

    def rects(self, rects, clear):
        pass


class LevelError(Exception):
    """A rule that cannot be written at all (wrong element kind for it)."""


def encode(L, B):
    """Write level L (see place.Level) into backend B. Returns the handle `place` reads."""
    C = house.CLEAR
    M = L.members
    V = {}  # member id -> dict(x, y, w, h, sh=[lits])
    tagged = {}  # tag -> what it means (for explain.json)

    def tag(t, meaning):
        tagged[t] = meaning
        return t

    for m in M:
        if L.kind[m] == "bus":
            continue
        shapes = L.shapes[m]
        x = B.int(f"{m}.x", 0, L.avail_w)
        y = B.int(f"{m}.y", 0, L.hmax)
        sh = [B.bool(f"{m}.sh{k}") for k in range(len(shapes))]
        B.pick_one(sh, None)
        wn = [s[0] for s in shapes]
        hn = [s[1] for s in shapes]
        w = B.int(f"{m}.w", min(wn), L.avail_w)
        h = B.int(f"{m}.h", min(hn), L.hmax)
        nat_w = sum(B.b2i(s) * k for s, k in zip(sh, wn, strict=False)) if len(sh) > 1 else wn[0]
        nat_h = sum(B.b2i(s) * k for s, k in zip(sh, hn, strict=False)) if len(sh) > 1 else hn[0]
        B.add(w >= nat_w, None)
        B.add(h >= nat_h, None)
        if m not in L.stretch_x:
            B.add(w == nat_w, None)
        if m not in L.stretch_y:
            B.add(h == nat_h, None)
        V[m] = dict(x=x, y=y, w=w, h=h, sh=sh, nat_w=nat_w, nat_h=nat_h)
        B.add(
            x + w <= L.avail_w,
            tag(
                f"canvas-width {m}",
                {"rule": "canvas-width", "element": m, "width": L.avail_w, "level": L.name},
            ),
        )

    # -- the geometry of any element reachable from this level ---------------------------
    BUS = {}

    def geo(e):
        """(x, y, w, h) expressions of element e (a member, or nested inside one)."""
        if e in V:
            v = V[e]
            return v["x"], v["y"], v["w"], v["h"]
        if e in BUS:
            b = BUS[e]
            if b["orient"] == "v":
                return b["p"], None, 0, None
            return None, b["p"], None, 0
        owner = L.owner.get(e)
        if owner is None:
            raise LevelError(f"{e!r} is not inside level {L.name}")
        v, inner = V[owner], L.inner[owner]
        if len(v["sh"]) == 1:
            dx, dy, w, h = inner[0][e]
            return v["x"] + dx, v["y"] + dy, w, h
        parts = [inner[k][e] for k in range(len(v["sh"]))]

        def comb(i):
            vals = [p[i] for p in parts]
            if len(set(vals)) == 1:
                return vals[0]
            return sum(B.b2i(s) * c for s, c in zip(v["sh"], vals, strict=False))

        return v["x"] + comb(0), v["y"] + comb(1), comb(2), comb(3)

    def need(e, axis, what):
        g = geo(e)
        if (axis == "x" and g[0] is None) or (axis == "y" and g[1] is None):
            raise LevelError(
                f"{what}: {e!r} is a bus running along that axis; it has no position there"
            )
        return g

    def owner_of(e):
        return e if e in V or e in BUS else L.owner[e]

    # -- level bounding box ---------------------------------------------------------------
    boxes = [m for m in M if m in V]
    Wl = B.int(f"{L.name}.W", 0, L.avail_w)
    Hl = B.int(f"{L.name}.H", 0, L.hmax)
    if boxes:
        minx = B.int(f"{L.name}.minx", 0, L.avail_w)
        miny = B.int(f"{L.name}.miny", 0, L.hmax)
        B.min_eq(minx, [V[m]["x"] for m in boxes])
        B.min_eq(miny, [V[m]["y"] for m in boxes])
        B.add(minx == 0, None)
        B.add(miny == 0, None)
    obj = []  # (weight, expr)
    sig = []  # signature literals (topology)
    extra = {}  # label / corridor boxes: id -> dict(x, y, w, h)

    # -- buses --------------------------------------------------------------------------
    for b in L.buses:
        bid = b["id"]
        hi = L.avail_w if b["orient"] == "v" else L.hmax
        p = B.int(f"{bid}.p", 0, hi)
        BUS[bid] = dict(orient=b["orient"], p=p, side={}, b=b)
    for bid, bb in BUS.items():
        b, p, v_or = bb["b"], bb["p"], bb["orient"] == "v"
        B.add(p <= (Wl if v_or else Hl), None)
        for m in boxes:
            s = B.bool(f"{m}^{bid}")
            bb["side"][m] = s
            sig.append(s)
            x, y, w, h = geo(m)
            t = tag(
                f"bus-side {bid} {m}",
                {"rule": "bus-side", "bus": bid, "element": m, "clearance": C, "level": L.name},
            )
            if v_or:  # s: the element lies left of a vertical spine
                B.add(x + w + C <= p, t, [s])
                B.add(x >= p + C, t, [B.neg(s)])
            else:  # s: the element lies above a horizontal spine
                B.add(y + h + C <= p, t, [s])
                B.add(y >= p + C, t, [B.neg(s)])
        # tap projections onto the spine (doubled to stay integer) and tap lengths
        projs = []
        for tp in b["taps"]:
            e = tp["node"]
            x, y, w, h = geo(e)
            s = bb["side"][owner_of(e)]
            projs.append(2 * y + h if v_or else 2 * x + w)
            far = B.int(f"{bid}.{e}.len", 0, 2 * max(L.avail_w, L.hmax))
            if v_or:
                B.add(far == p - x - w, None, [s])
                B.add(far == x - p, None, [B.neg(s)])
            else:
                B.add(far == p - y - h, None, [s])
                B.add(far == y - p, None, [B.neg(s)])
            B.add(
                far >= house.TAP_ROOM,
                tag(
                    f"tap-room {bid} {e}",
                    {
                        "rule": "tap-room",
                        "bus": bid,
                        "element": e,
                        "min": house.TAP_ROOM,
                        "level": L.name,
                    },
                ),
            )
            obj.append((2, far))
        lo2 = B.int(f"{bid}.lo2", 0, 2 * max(L.avail_w, L.hmax))
        B.min_eq(lo2, projs)
        bb["lo2"] = lo2
        if b["id"] in L.labels:
            lw, lh = L.labels[b["id"]]["w"], L.labels[b["id"]]["h"]
            lx = B.int(f"{bid}.lab.x", 0, L.avail_w)
            ly = B.int(f"{bid}.lab.y", 0, L.hmax)
            if v_or:  # centred on the spine, above the first tap
                B.add(2 * lx + lw - 2 * p <= 1, None)
                B.add(2 * p - 2 * lx - lw <= 1, None)
                lead = B.int(f"{bid}.lab.lead", 0, 2 * L.hmax)
                B.add(lead == lo2 - 2 * (ly + lh), None)
            else:  # centred on the spine, left of the first tap
                B.add(2 * ly + lh - 2 * p <= 1, None)
                B.add(2 * p - 2 * ly - lh <= 1, None)
                lead = B.int(f"{bid}.lab.lead", 0, 2 * L.avail_w)
                B.add(lead == lo2 - 2 * (lx + lw), None)
            B.add(
                lead >= 2 * house.TAP_ROOM,
                tag(f"bus-label {bid}", {"rule": "bus-label-room", "bus": bid, "level": L.name}),
            )
            obj.append((4, lead))
            B.add(lx + lw <= Wl, None)
            B.add(ly + lh <= Hl, None)
            extra[f"label:{bid}"] = dict(x=lx, y=ly, w=lw, h=lh)

    # -- corridors: the room a loop edge (out of a side and back into the same side) needs --
    for key, es in L.bundles.items():
        side = es[0]["from-side"]
        ends = [es[0]["from"]] + [e["to"] for e in es]
        cid = f"corridor:{key}"
        vert = side in "ew"
        cw = house.CORRIDOR
        cx = B.int(f"{cid}.x", 0, L.avail_w)
        cy = B.int(f"{cid}.y", 0, L.hmax)
        cl = B.int(f"{cid}.len", 1, 2 * max(L.avail_w, L.hmax))
        t = tag(
            f"corridor {key}",
            {
                "rule": "corridor",
                "edges": [e["id"] for e in es],
                "side": side,
                "width": cw,
                "level": L.name,
            },
        )
        for e in ends:
            x, y, w, h = geo(e)
            if vert:
                if side == "e":
                    B.add(cx >= x + w + 1, t)
                    obj.append((2, cx - x - w))
                else:
                    B.add(cx + cw + 1 <= x, t)
                    obj.append((2, x - cx - cw))
                B.add(2 * cy <= 2 * y + h, t)
                B.add(2 * (cy + cl) >= 2 * y + h, t)
            else:
                if side == "s":
                    B.add(cy >= y + h + 1, t)
                    obj.append((2, cy - y - h))
                else:
                    B.add(cy + cw + 1 <= y, t)
                    obj.append((2, y - cy - cw))
                B.add(2 * cx <= 2 * x + w, t)
                B.add(2 * (cx + cl) >= 2 * x + w, t)
        obj.append((1, cl))
        cwx, chy = (cw, cl) if vert else (cl, cw)
        B.add(cx + cwx <= Wl, t)
        B.add(cy + chy <= Hl, t)
        extra[cid] = dict(x=cx, y=cy, w=cwx, h=chy, side=side, edges=[e["id"] for e in es])

    # -- clearance between every pair of boxes at this level --------------------------------
    items = [(m, V[m]) for m in boxes] + list(extra.items())
    for i in range(len(items)):
        for j in range(i + 1, len(items)):
            (a, p), (b2, q) = items[i], items[j]
            r = {k: B.bool(f"{a}{k}{b2}") for k in "ABLR"}
            B.pick_one(
                list(r.values()),
                tag(
                    f"no-overlap {a} {b2}",
                    {"rule": "no-overlap", "elements": [a, b2], "clearance": C, "level": L.name},
                ),
            )
            sig.extend(r.values())
            B.add(p["y"] + p["h"] + C <= q["y"], None, [r["A"]])
            B.add(q["y"] + q["h"] + C <= p["y"], None, [r["B"]])
            B.add(p["x"] + p["w"] + C <= q["x"], None, [r["L"]])
            B.add(q["x"] + q["w"] + C <= p["x"], None, [r["R"]])
            for k in "LR":  # canonical: left/right only when not vertically separated
                B.add(p["y"] < q["y"] + q["h"] + C, None, [r[k]])
                B.add(q["y"] < p["y"] + p["h"] + C, None, [r[k]])
    B.rects([(v["x"], v["y"], v["w"], v["h"]) for _, v in items], C)
    for m in boxes:
        sig.extend(V[m]["sh"] if len(V[m]["sh"]) > 1 else [])

    # -- group layouts --------------------------------------------------------------------
    order = [m for m in L.layout_order if m in V]
    if L.layout in ("stack", "row") and len(order) > 1:
        t = tag(
            f"layout {L.name} {L.layout}",
            {
                "rule": "layout",
                "group": L.name,
                "layout": L.layout,
                "children": order,
                "gap": house.CLEAR,
            },
        )
        for a, b2 in zip(order, order[1:], strict=False):
            if L.layout == "stack":
                B.add(V[b2]["y"] >= V[a]["y"] + V[a]["h"] + house.CLEAR, t)
                B.add(V[b2]["x"] == V[a]["x"], t)
            else:
                B.add(V[b2]["x"] >= V[a]["x"] + V[a]["w"] + house.CLEAR, t)
                B.add(V[b2]["y"] == V[a]["y"], t)

    # -- edges: arrow room along every drawn edge; length goes into the objective -----------
    for e in L.edges:
        fx, fy, fw, fh = need(e["from"], "x", f"edge {e['id']}")
        tx, ty, tw, th = need(e["to"], "x", f"edge {e['id']}")
        heads = {"end": 1, "both": 2, "none": 0}[e["arrow"]]
        room = house.ARROW_ROOM * heads if heads else house.TAP_ROOM
        fs, ts = e.get("from-side"), e.get("to-side")
        t = tag(
            f"arrow-room {e['id']}",
            {"rule": "arrow-room", "edge": e["id"], "min": room, "level": L.name},
        )
        if (fs, ts) == ("s", "n"):
            B.add(ty - fy - fh >= room, t)
        elif (fs, ts) == ("n", "s"):
            B.add(fy - ty - th >= room, t)
        elif (fs, ts) == ("e", "w"):
            B.add(tx - fx - fw >= room, t)
        elif (fs, ts) == ("w", "e"):
            B.add(fx - tx - tw >= room, t)
        elif fs in ("e", "w") and ts in ("n", "s"):  # an L: out sideways, the last leg vertical
            if ts == "n":
                B.add(2 * ty - 2 * fy - fh >= 2 * room, t)
            else:
                B.add(2 * fy + fh - 2 * ty - 2 * th >= 2 * room, t)
            if fs == "e":
                B.add(2 * tx + tw - 2 * fx - 2 * fw >= 2 * house.TAP_ROOM, t)
            else:
                B.add(2 * fx - 2 * tx - tw >= 2 * house.TAP_ROOM, t)
        elif fs in ("n", "s") and ts in ("e", "w"):  # an L: out vertically, the last leg sideways
            if ts == "w":
                B.add(2 * tx - 2 * fx - fw >= 2 * room, t)
            else:
                B.add(2 * fx + fw - 2 * tx - 2 * tw >= 2 * room, t)
            if fs == "s":
                B.add(2 * ty + th - 2 * fy - 2 * fh >= 2 * house.TAP_ROOM, t)
            else:
                B.add(2 * fy - 2 * ty - th >= 2 * house.TAP_ROOM, t)
        elif fs is None or ts is None:  # the router takes two facing sides; leave it a channel
            free = room + 2 * house.CLEAR  # for a bend and a neighbour's parallel run
            facing = [B.bool(f"{e['id']}.face{k}") for k in range(4)]
            B.add(ty - fy - fh >= free, t, [facing[0]])
            B.add(fy - ty - th >= free, t, [facing[1]])
            B.add(tx - fx - fw >= free, t, [facing[2]])
            B.add(fx - tx - tw >= free, t, [facing[3]])
            B.any_of(facing, t)
        for a, b2 in ((2 * fx + fw, 2 * tx + tw), (2 * fy + fh, 2 * ty + th)):
            d = B.int(f"{e['id']}.d{len(obj)}", 0, 4 * max(L.avail_w, L.hmax))
            B.abs_eq(d, a - b2)
            obj.append((L.soft["edges"], d))

    # -- the description's own constraints -------------------------------------------------
    stretch_pen = []
    for idx, c in L.constraints:
        _constraint(L, B, idx, c, geo, need, V, BUS, Wl, Hl, tag, obj, sig, extra, owner_of)
    for m in boxes:
        if m in L.stretch_x:
            stretch_pen.append(V[m]["w"] - V[m]["nat_w"])
        if m in L.stretch_y:
            stretch_pen.append(V[m]["h"] - V[m]["nat_h"])
    for s in stretch_pen:
        obj.append((L.soft["height"], s))
    # the level's extent IS its content's extent (a shape is never padded with empty space)
    rights = [V[m]["x"] + V[m]["w"] for m in boxes] + [v["x"] + v["w"] for v in extra.values()]
    bottoms = [V[m]["y"] + V[m]["h"] for m in boxes] + [v["y"] + v["h"] for v in extra.values()]
    rights += [bb["p"] for bb in BUS.values() if bb["orient"] == "v"]
    bottoms += [bb["p"] for bb in BUS.values() if bb["orient"] == "h"]
    if rights:
        B.max_eq(Wl, rights)
        B.max_eq(Hl, bottoms)
    height_w = L.soft["height"]
    objective = 2 * height_w * Hl + (Wl if not L.top else 0) + sum(w * e for w, e in obj)
    return dict(
        V=V,
        BUS=BUS,
        extra=extra,
        Wl=Wl,
        Hl=Hl,
        sig=sig,
        objective=objective,
        tagged=tagged,
        geo=geo,
    )


def _constraint(L, B, idx, c, geo, need, V, BUS, Wl, Hl, tag, obj, sig, extra, owner_of):
    from .desc import label

    name, args = c[0], c[1:]
    t = tag(f"constraint {idx}", {"constraint": c, "index": idx, "text": label(c), "level": L.name})
    if name in ("order-x", "order-y"):
        ax = "x" if name == "order-x" else "y"
        for a, b in zip(args[0], args[0][1:], strict=False):
            ga, gb = need(a, ax, name), need(b, ax, name)
            if ax == "x":
                B.add(ga[0] + ga[2] + args[1] <= gb[0], t)
            else:
                B.add(gb[1] - ga[1] - ga[3] >= args[1], t)
                if len(args) > 2:
                    B.add(gb[1] - ga[1] - ga[3] <= args[2], t)
    elif name in ("same-x", "same-y"):
        i = 0 if name == "same-x" else 1
        ref = need(args[0][0], "xy"[i], name)
        for a in args[0][1:]:
            B.add(need(a, "xy"[i], name)[i] == ref[i], t)
    elif name in ("equal-width", "equal-height"):
        i = 2 if name == "equal-width" else 3
        ref = need(args[0][0], "xy"[i - 2], name)
        for a in args[0][1:]:
            B.add(need(a, "xy"[i - 2], name)[i] == ref[i], t)
    elif name == "same-shape":
        ids = args[0]
        for a in ids:
            if a not in V:
                raise LevelError(f"same-shape: {a!r} is not a group placed at level {L.name}")
        n0 = len(V[ids[0]]["sh"])
        for a in ids[1:]:
            if len(V[a]["sh"]) != n0:
                raise LevelError(
                    f"same-shape: {ids[0]!r} has {n0} shapes, {a!r} has {len(V[a]['sh'])}"
                )
            for s, s0 in zip(V[a]["sh"], V[ids[0]]["sh"], strict=False):
                B.add(B.b2i(s) == B.b2i(s0), t)
    elif name == "span-x":
        x, y, w, h = need(args[0], "x", name)
        B.add(x == 0, t)
        B.add(x + w == (house.W if L.top else Wl), t)
    elif name == "fill-x":
        ids = args[0]
        gap = B.int(f"fill{idx}.gap", args[1], L.avail_w)
        gs = [need(a, "x", name) for a in ids]
        B.add(gs[0][0] == 0, t)
        B.add(gs[-1][0] + gs[-1][2] == (house.W if L.top else Wl), t)
        for ga, gb in zip(gs, gs[1:], strict=False):
            B.add(gb[0] - ga[0] - ga[2] == gap, t)
            B.add(gb[2] == ga[2], t)
    elif name == "hang":
        e, bid, g0, g1, side = args
        if e in BUS:
            raise LevelError(f"hang: {e!r} is a bus; hang places a box against a bus")
        bb = BUS.get(bid)
        if bb is None:
            raise LevelError(f"hang: bus {bid!r} is not at level {L.name}")
        v_or = bb["orient"] == "v"
        if side != "free" and side not in (("left", "right") if v_or else ("above", "below")):
            raise LevelError(
                f"hang: side {side!r} does not exist for a {'vertical' if v_or else 'horizontal'} bus"
            )
        x, y, w, h = geo(e)
        s = bb["side"][owner_of(e)]
        p = bb["p"]
        near_, far_ = (x + w, x) if v_or else (y + h, y)
        B.add(p - near_ >= g0, t, [s])
        B.add(p - near_ <= g1, t, [s])
        B.add(far_ - p >= g0, t, [B.neg(s)])
        B.add(far_ - p <= g1, t, [B.neg(s)])
        if side in ("left", "above"):
            B.force(s, t)
        elif side in ("right", "below"):
            B.force(B.neg(s), t)
    elif name == "follow":
        a, b, bid, gap = args
        if a in BUS or b in BUS:
            raise LevelError("follow: both elements must be boxes, not buses")
        bb = BUS.get(bid)
        if bb is None:
            raise LevelError(f"follow: bus {bid!r} is not at level {L.name}")
        v_or = bb["orient"] == "v"
        ga, gb = geo(a), geo(b)
        sa, sb = bb["side"][owner_of(a)], bb["side"][owner_of(b)]
        B.add(B.b2i(sa) == B.b2i(sb), t)
        i, j = (0, 2) if v_or else (1, 3)  # the axis across the spine
        k, m = (1, 3) if v_or else (0, 2)  # the axis along it
        B.add(ga[i] + ga[j] + gap == gb[i], t, [sb])
        B.add(ga[i] == gb[i] + gb[j] + gap, t, [B.neg(sb)])
        B.add(2 * ga[k] + ga[m] - 2 * gb[k] - gb[m] <= 1, t)
        B.add(2 * gb[k] + gb[m] - 2 * ga[k] - ga[m] <= 1, t)
    elif name == "beside":
        a, b, gap = args
        ga, gb = need(a, "x", name), need(b, "x", name)
        left = B.bool(f"beside{idx}")
        sig.append(left)
        B.add(ga[0] + ga[2] + gap == gb[0], t, [left])
        B.add(gb[0] + gb[2] + gap == ga[0], t, [B.neg(left)])
    elif name == "within-y":
        ga, gb = need(args[0], "y", name), need(args[1], "y", name)
        B.add(ga[1] >= gb[1], t)
        B.add(ga[1] + ga[3] <= gb[1] + gb[3], t)
    elif name == "span-y":
        g, top, bot = (need(a, "y", name) for a in args)
        B.add(g[1] == top[1], t)
        B.add(g[1] + g[3] == bot[1] + bot[3], t)
    elif name == "near":
        e, eids, side, gap = args
        ge = need(e, "x", name)
        cor = next(
            (
                v
                for k, v in extra.items()
                if k.startswith("corridor:") and set(eids) <= set(v["edges"])
            ),
            None,
        )
        if cor is not None:
            env = (cor["x"], cor["y"], cor["w"], cor["h"])
        else:
            ends = [x for ed in L.edges if ed["id"] in eids for x in (ed["from"], ed["to"])]
            if not ends:
                raise LevelError(f"near: none of the edges {eids} is drawn at level {L.name}")
            gs = [need(x, "x", name) for x in ends]
            ex0 = B.int(f"near{idx}.x0", 0, L.avail_w)
            ey0 = B.int(f"near{idx}.y0", 0, L.hmax)
            ex1 = B.int(f"near{idx}.x1", 0, L.avail_w)
            ey1 = B.int(f"near{idx}.y1", 0, L.hmax)
            B.min_eq(ex0, [g[0] for g in gs])
            B.min_eq(ey0, [g[1] for g in gs])
            B.max_eq(ex1, [g[0] + g[2] for g in gs])
            B.max_eq(ey1, [g[1] + g[3] for g in gs])
            env = (ex0, ey0, ex1 - ex0, ey1 - ey0)
        x0, y0, w0, h0 = env
        if side in "ew":
            d = ge[0] - x0 - w0 if side == "e" else x0 - ge[0] - ge[2]
            B.add(ge[1] >= y0, t)
            B.add(ge[1] + ge[3] <= y0 + h0, t)
        else:
            d = ge[1] - y0 - h0 if side == "s" else y0 - ge[1] - ge[3]
            B.add(ge[0] >= x0, t)
            B.add(ge[0] + ge[2] <= x0 + w0, t)
        B.add(d >= gap, t)
        obj.append((4, d))
    else:
        raise LevelError(f"constraint {name!r} has no encoding")
