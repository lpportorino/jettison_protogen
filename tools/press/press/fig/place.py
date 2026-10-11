"""Placement: groups bottom-up, then the figure, by CP-SAT — deterministically.

A group is laid out first, on its own, as a small figure (a LEVEL): its direct children are the
level's members. Solving it gives up to SHAPES_MAX distinct shapes — arrangements that differ
in at least DIFF_BITS topology bits (never the same arrangement merely spaced out: the level's
extent is its content's extent) — each with the resolved offsets of everything inside. The level above
then places the group as ONE box that may take any of those shapes. Rules that name elements in
different groups are written at the deepest level that holds them all (their common container),
where a nested element's position is its member's position plus a per-shape offset.

At the top, rounds of CP-SAT give the diverse candidates: each round minimises the soft
objective (height first, then edge length), then the next round must differ VISIBLY from every
candidate kept so far: leaf boxes holding at least MOVED_AREA of all leaf-box area (natural
sizes) must each move by `step` (DIFF units and half the box's own size along that axis). A
group's change of shape moves the leaves inside it, so it counts through them. `visible_share`
measures exactly that on two solutions, and it is the same function the placer encodes: a kept
candidate that fails it is a defect in press (`Internal`), never a silent drop. A candidate the
checker rejects asks for no distance from later ones: only that exact layout is excluded, and when
a second layout of the same arrangement (the same pair relations, bus sides and shapes) fails too,
that arrangement is excluded. At most ROUNDS_PER_TILE rounds are spent per wanted tile.

Determinism: one worker, a fixed seed, and CP-SAT's DETERMINISTIC time (work units, roughly
seconds on a desktop core) for every limit, so one description on one image always gives the
same candidates. `--cap-s` limits one round; `Clock` holds the total for the whole solve
(`--total-s`) and its exhaustion is a named failure (`TimeLimit`) naming where it ran out.

Heights are bounded by what the level's own content could ever need (every box stacked, every
gap at the largest gap any rule or house rule asks for), never by the page budget: a figure
over the budget is solved with the budget as an assumption, and "over budget" and
"unsatisfiable" are told apart by dropping it.
"""

import dataclasses
import math

from ortools.sat.python import cp_model

from . import house
from .model import Cp, LevelError, encode

SHAPES_MAX = 4  # shapes kept per group
DIFF = 16  # units a leaf box must move at least (and half its own size) to count as moved
DIFF_BITS = 2  # topology bits two SHAPES of one group must differ in
MOVED_AREA = 0.10  # share of leaf-box area that must move visibly between any two kept candidates
SEED = 1
ROUNDS_PER_TILE = 3  # rounds allowed per wanted tile (rejected candidates use rounds too)


class Unsat(Exception):
    """A level that admits no layout; `level` names it so `explain` can re-encode it in Z3."""

    def __init__(self, level, msg):
        super().__init__(msg)
        self.level = level


class OverBudget(Exception):
    def __init__(self, needs_h):
        super().__init__(f"needs {needs_h} units of height")
        self.needs_h = needs_h


class NoAnswer(Exception):
    """CP-SAT ran out of its per-round limit without a layout or a proof that none exists."""


class TimeLimit(Exception):
    """The total deterministic time of the solve ran out; the message names where."""


class TooWide(Exception):
    """A box whose narrowest shape is wider than the room it must sit in (found before solving)."""

    def __init__(self, element, width, avail, level):
        super().__init__(f"{element!r} is {width} units wide; {level} is {avail} units wide")
        self.element, self.width, self.avail, self.level = element, width, avail, level


class Internal(Exception):
    """A defect in press itself (an invalid model, the placer and the filter disagreeing)."""


@dataclasses.dataclass
class Level:
    name: str
    gid: object
    top: bool
    members: list
    kind: dict
    shapes: dict
    inner: dict
    owner: dict
    buses: list
    labels: dict
    avail_w: int
    hmax: int
    constraints: list
    edges: list
    bundles: dict
    stretch_x: set
    stretch_y: set
    leaves: set
    layout: str
    layout_order: list
    soft: dict


def containers(d, e):
    """Groups strictly containing e, innermost first."""
    out, p = [], d["_parent"].get(e)
    while p is not None:
        out.append(p)
        p = d["_parent"].get(p)
    return out


def lca(d, ids):
    """The deepest group strictly containing every id (None = the figure itself)."""
    chains = [containers(d, e) for e in ids]
    common = set(chains[0]).intersection(*map(set, chains[1:])) if chains else set()
    for g in chains[0]:
        if g in common:
            return g
    return None


def constraint_ids(c):
    name, args = c[0], c[1:]
    if name in (
        "order-x",
        "order-y",
        "same-x",
        "same-y",
        "equal-width",
        "equal-height",
        "same-shape",
        "fill-x",
    ):
        return list(args[0])
    if name == "near":
        return [args[0]]
    return [
        a
        for a in args
        if isinstance(a, str) and a not in ("free", "above", "below", "left", "right")
    ]


class Problem:
    """The whole figure: measured sizes, the level tree, and where each rule is written."""

    def __init__(self, d, sizes):
        self.d, self.sizes = d, sizes
        self.groups = {g["id"]: g for g in d["groups"]}
        self.buses = {b["id"]: b for b in d["buses"]}
        self.nodes = {n["id"]: n for n in d["nodes"]}
        self.children = {g["id"]: list(g["children"]) for g in d["groups"]}
        self.top_members = [
            i
            for i in list(self.nodes) + list(self.groups) + list(self.buses)
            if i not in d["_parent"]
        ]
        edges_by_id = {e["id"]: e for e in d["edges"]}
        self.bundles, self.bundle_of = {}, {}
        for e in d["edges"]:
            fs, ts = e.get("from-side"), e.get("to-side")
            if e["kind"] == "skip" and fs and fs == ts:
                key = f"{e['from']}:{fs}"
                self.bundles.setdefault(key, []).append(e)
                self.bundle_of[e["id"]] = key
        self.level_cons, self.level_edges, self.level_bundles = {}, {}, {}
        for i, c in enumerate(d["constraints"]):
            ids = constraint_ids(c)
            if c[0] == "near":
                ids += [
                    x for eid in c[2] for x in (edges_by_id[eid]["from"], edges_by_id[eid]["to"])
                ]
            self.level_cons.setdefault(lca(d, ids), []).append((i, c))
        for key, es in self.bundles.items():
            ends = [es[0]["from"]] + [e["to"] for e in es]
            lv = lca(d, ends)
            self.level_bundles.setdefault(lv, {})[key] = es
            for e in es:
                self.level_edges.setdefault(lv, []).append(e)
        for e in d["edges"]:
            if e["id"] not in self.bundle_of:
                self.level_edges.setdefault(lca(d, [e["from"], e["to"]]), []).append(e)
        for b in d["buses"]:
            lv = d["_parent"].get(b["id"])
            for t in b["taps"]:
                chain = containers(d, t["node"])
                if lv is not None and lv not in chain:
                    raise LevelError(
                        f"bus {b['id']!r} sits in group {lv!r} but taps {t['node']!r}, which is outside it"
                    )
        self.avail = {None: house.W}
        for g in self._topdown():
            par = d["_parent"].get(g)
            self.avail[g] = self.avail[par] - 2 * house.PAD
        for nid in self.nodes:
            lv = d["_parent"].get(nid)
            if sizes["nodes"][nid]["w"] > self.avail[lv]:
                raise TooWide(
                    nid,
                    sizes["nodes"][nid]["w"],
                    self.avail[lv],
                    f"group {lv!r}" if lv else "the canvas",
                )
        for gid in self.groups:
            lv = d["_parent"].get(gid)
            if sizes["groups"][gid]["title_w"] > self.avail[lv]:
                raise TooWide(
                    gid,
                    sizes["groups"][gid]["title_w"],
                    self.avail[lv],
                    f"group {lv!r}" if lv else "the canvas",
                )
        for bid, lab in sizes["labels"].items():
            lv = d["_parent"].get(bid)
            if lab["w"] > self.avail[lv]:
                raise TooWide(
                    bid, lab["w"], self.avail[lv], f"group {lv!r}" if lv else "the canvas"
                )

    def _topdown(self):
        out, todo = [], [g for g in self.groups if g not in self.d["_parent"]]
        while todo:
            g = todo.pop(0)
            out.append(g)
            todo += [c for c in self.children[g] if c in self.groups]
        return out

    def _hbound(self, members, sh, gid, cons):
        """A height no layout of this level can need: every box stacked, every gap at the largest
        gap any rule or house rule of the level asks for. The page budget never enters here."""
        gaps = [house.CLEAR, house.TAP_ROOM, 2 * house.ARROW_ROOM + 2, house.CORRIDOR]
        gaps += [a for _, c in cons for a in c[1:] if isinstance(a, int)]
        labels = [self.sizes["labels"][b]["h"] for b in members if b in self.sizes["labels"]]
        boxes = [max(h for _, h in sh[m]) for m in members if m in sh]
        n = len(boxes) + len(labels) + len(self.level_bundles.get(gid, {})) + 2
        return (
            sum(boxes)
            + sum(labels)
            + n * max(gaps)
            + house.CORRIDOR * len(self.level_bundles.get(gid, {}))
        )

    def bottomup(self):
        return list(reversed(self._topdown()))

    def level(self, gid, shapes):
        members = self.children[gid] if gid is not None else self.top_members
        kind, sh, inner, owner = {}, {}, {}, {}
        for m in members:
            if m in self.nodes:
                kind[m] = "node"
                sh[m] = [(self.sizes["nodes"][m]["w"], self.sizes["nodes"][m]["h"])]
                inner[m] = [{}]
            elif m in self.groups:
                kind[m] = "group"
                sh[m] = [(s["w"], s["h"]) for s in shapes[m]]
                inner[m] = [s["inner"] for s in shapes[m]]
                for e in shapes[m][0]["inner"]:
                    owner[e] = m
            else:
                kind[m] = "bus"
        cons = self.level_cons.get(gid, [])
        sx, sy = set(), set()
        for _, c in cons:
            if c[0] in ("equal-width", "fill-x"):
                sx |= set(c[1])
            elif c[0] == "span-x":
                sx.add(c[1])
            elif c[0] == "equal-height":
                sy |= set(c[1])
            elif c[0] == "span-y":
                sy.add(c[1])
        g = self.groups.get(gid)
        return Level(
            name=gid or "figure",
            gid=gid,
            top=gid is None,
            members=members,
            kind=kind,
            shapes=sh,
            inner=inner,
            owner=owner,
            buses=[self.buses[m] for m in members if m in self.buses],
            labels={b: self.sizes["labels"][b] for b in members if b in self.sizes["labels"]},
            avail_w=self.avail[gid],
            hmax=self._hbound(members, sh, gid, cons),
            constraints=cons,
            edges=self.level_edges.get(gid, []),
            bundles=self.level_bundles.get(gid, {}),
            stretch_x={m for m in sx if m in kind},
            stretch_y={m for m in sy if m in kind},
            leaves=set(self.nodes),
            layout=g["layout"] if g else "free",
            layout_order=[c for c in (g["children"] if g else []) if c not in self.buses],
            soft=self.d["soft"],
        )


class Clock:
    """The deterministic time a whole solve may spend; every CP-SAT call draws on it."""

    def __init__(self, total_s, cap_s):
        self.left, self.cap = total_s, cap_s

    def solver(self, where):
        if self.left <= 0:
            raise TimeLimit(
                f"the total deterministic time ran out before {where} (raise --total-s)"
            )
        s = cp_model.CpSolver()
        s.parameters.num_workers = 1
        s.parameters.random_seed = SEED
        s.parameters.max_deterministic_time = min(self.cap, self.left)
        return s

    def solve(self, s, m, where):
        """Solve; an answerless stop under the TOTAL limit (not the per-round cap) is TimeLimit."""
        by_total = self.left < self.cap
        st = s.Solve(m)
        self.left -= s.ResponseProto().deterministic_time
        if st == cp_model.MODEL_INVALID:
            raise Internal(f"{where}: CP-SAT calls the model invalid ({m.Validate()[:300]})")
        if st == cp_model.UNKNOWN and by_total:
            raise TimeLimit(
                f"the total deterministic time ran out during {where} (raise --total-s)"
            )
        return st


def _read(s, enc, L):
    """The solved level, in the level's own coordinates."""
    V = enc["V"]
    out = {
        "W": s.Value(enc["Wl"]),
        "H": s.Value(enc["Hl"]),
        "members": {},
        "buses": {},
        "extra": {},
    }
    for m, v in V.items():
        k = [s.Value(x) for x in v["sh"]].index(1) if len(v["sh"]) > 1 else 0
        out["members"][m] = dict(
            x=s.Value(v["x"]), y=s.Value(v["y"]), w=s.Value(v["w"]), h=s.Value(v["h"]), shape=k
        )
    for b, bb in enc["BUS"].items():
        out["buses"][b] = dict(
            p=s.Value(bb["p"]),
            orient=bb["orient"],
            sides={m: bool(s.Value(lit)) for m, lit in bb["side"].items()},
        )
    for k, v in enc["extra"].items():
        out["extra"][k] = {
            kk: (s.Value(vv) if kk in ("x", "y", "w", "h") and not isinstance(vv, int) else vv)
            for kk, vv in v.items()
        }
    return out


def flatten(L, sol, shapes):
    """A solved level -> every element inside it, offsets relative to the level's content origin."""
    inner, buses, extra = {}, {}, {}
    for m, v in sol["members"].items():
        inner[m] = (v["x"], v["y"], v["w"], v["h"])
        if L.kind[m] == "group":
            s = shapes[m][v["shape"]]
            for e, (dx, dy, w, h) in s["inner"].items():
                inner[e] = (v["x"] + dx, v["y"] + dy, w, h)
            for b, bv in s["buses"].items():
                buses[b] = _shift_bus(bv, v["x"], v["y"])
            for k, ev in s["extra"].items():
                extra[k] = dict(ev, x=ev["x"] + v["x"], y=ev["y"] + v["y"])
    for b, bv in sol["buses"].items():
        buses[b] = dict(bv, ox=0, oy=0, W=sol["W"], H=sol["H"])
    for k, ev in sol["extra"].items():
        extra[k] = dict(ev)
    return inner, buses, extra


def _shift_bus(bv, dx, dy):
    bv = dict(bv)
    if bv["orient"] == "v":
        bv["p"] += dx
    else:
        bv["p"] += dy
    bv["ox"] += dx
    bv["oy"] += dy
    return bv


def solve_groups(P, clock):
    """Every group, innermost first -> {gid: [shape, ...]}; raises Unsat / NoAnswer / TimeLimit."""
    shapes = {}
    for gid in P.bottomup():
        L = P.level(gid, shapes)
        B = Cp()
        enc = encode(L, B)
        B.m.Minimize(enc["objective"])
        meas = P.sizes["groups"][gid]
        found = []
        for _ in range(SHAPES_MAX):
            s = clock.solver(f"group {gid!r}")
            st = clock.solve(s, B.m, f"group {gid!r}")
            if st == cp_model.INFEASIBLE:
                if not found:
                    raise Unsat(L, f"group {gid!r}: no layout of its children satisfies its rules")
                break
            if st not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
                if not found:
                    raise NoAnswer(
                        f"group {gid!r}: CP-SAT found no layout within {clock.cap} deterministic s "
                        "(raise --cap-s)"
                    )
                break
            sol = _read(s, enc, L)
            inner, buses, extra = flatten(L, sol, shapes)
            ox, oy = house.PAD, meas["top"]
            shape = {
                "w": max(sol["W"] + 2 * house.PAD, meas["title_w"]),
                "h": oy + sol["H"] + house.PAD,
                "inner": {e: (x + ox, y + oy, w, h) for e, (x, y, w, h) in inner.items()},
                "buses": {b: _shift_bus(bv, ox, oy) for b, bv in buses.items()},
                "extra": {k: dict(v, x=v["x"] + ox, y=v["y"] + oy) for k, v in extra.items()},
            }
            found.append(shape)
            vals = [
                s.Value(b) for b in enc["sig"]
            ]  # and a different arrangement, not a spaced-out copy
            if vals:
                B.add(
                    sum((1 - b) if v else b for b, v in zip(enc["sig"], vals, strict=False))
                    >= DIFF_BITS,
                    None,
                )
        shapes[gid] = found
    return shapes


def step(w, h):
    """How far a box must move, per axis, to count as moved: DIFF units and at least half its own
    size along that axis — a 300-unit box nudged 16 units is the same picture."""
    return max(DIFF, math.ceil(w / 2)), max(DIFF, math.ceil(h / 2))


def leaves(P, L, sol, shapes):
    """Every leaf box of a top-level solution: id -> (x, y, natural w, natural h)."""
    inner, _, _ = flatten(L, sol, shapes)
    return {
        e: (inner[e][0], inner[e][1], P.sizes["nodes"][e]["w"], P.sizes["nodes"][e]["h"])
        for e in P.nodes
        if e in inner
    }


def visible_share(a, b):
    """Share of leaf-box area (natural sizes) that moved visibly (`step`) between leaf maps a and b."""
    total = sum(w * h for _, _, w, h in a.values())
    moved = sum(
        w * h
        for e, (x, y, w, h) in a.items()
        if abs(x - b[e][0]) >= step(w, h)[0] or abs(y - b[e][1]) >= step(w, h)[1]
    )
    return moved / total if total else 0.0


def _forbid_layout(B, enc, sol, rounds):
    """Exactly this solution's positions, shapes and bus positions may not come back."""
    opts = []
    for m, v in enc["V"].items():
        got = sol["members"][m]
        for k, ct in enumerate(
            (
                v["x"] >= got["x"] + 1,
                v["x"] <= got["x"] - 1,
                v["y"] >= got["y"] + 1,
                v["y"] <= got["y"] - 1,
            )
        ):
            lit = B.bool(f"ng{rounds}.{m}.{k}")
            B.add(ct, None, [lit])
            opts.append(lit)
        if len(v["sh"]) > 1:
            opts.append(B.neg(v["sh"][got["shape"]]))
    for bid, bb in enc["BUS"].items():
        for k, ct in enumerate(
            (bb["p"] >= sol["buses"][bid]["p"] + 1, bb["p"] <= sol["buses"][bid]["p"] - 1)
        ):
            lit = B.bool(f"ng{rounds}.{bid}.{k}")
            B.add(ct, None, [lit])
            opts.append(lit)
    B.any_of(opts, None)


def solve_top(P, shapes, n, clock, accept):
    """Rounds of CP-SAT at the top. `accept(sol)` draws, routes and checks one candidate and says
    whether it is kept; only kept candidates constrain later rounds.
    -> (L, kept, why): `why` says why fewer than n were kept. Raises Unsat, OverBudget, NoAnswer."""
    L = P.level(None, shapes)
    B = Cp()
    enc = encode(L, B)
    budget = B.bool("page-budget")
    B.add(enc["Hl"] <= house.BUDGET_H, None, [budget])
    B.m.Minimize(enc["objective"])
    B.m.AddAssumptions([budget])
    s = clock.solver("the figure")
    st = clock.solve(s, B.m, "the figure")
    if st == cp_model.INFEASIBLE:
        B.m.ClearAssumptions()
        B.m.ClearObjective()
        B.m.Minimize(enc["Hl"])
        s2 = clock.solver("the figure without the page budget")
        st2 = clock.solve(s2, B.m, "the figure without the page budget")
        if st2 == cp_model.INFEASIBLE:
            raise Unsat(L, "the figure: no placement satisfies its rules")
        if st2 not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
            raise NoAnswer(
                f"the figure does not fit the page budget, and CP-SAT found no layout of it at any "
                f"height within {clock.cap} deterministic s (raise --cap-s)"
            )
        raise OverBudget(s2.Value(enc["Hl"]))
    if st not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        raise NoAnswer(
            f"the figure: CP-SAT found no layout within {clock.cap} deterministic s (raise --cap-s)"
        )
    B.m.ClearAssumptions()
    B.force(budget, None)
    leafs = [e for e in P.nodes if e in L.leaves and (e in enc["V"] or e in L.owner)]
    kept, rounds, rejected = [], 0, 0
    failed_topos = set()
    why = None
    while True:
        rounds += 1
        sol = _read(s, enc, L)
        sol["objective"] = int(s.ObjectiveValue())
        sol["leaves"] = leaves(P, L, sol, shapes)
        for k in kept:
            share = visible_share(sol["leaves"], k["leaves"])
            if share < MOVED_AREA:
                raise Internal(
                    f"round {rounds}: the placer called a candidate visibly different that moves only "
                    f"{share:.0%} of the leaf area (needs {MOVED_AREA:.0%}) — placer and filter disagree"
                )
        if accept(sol):
            kept.append(sol)
            terms, total = [], 0
            for e in leafs:
                x, y, w, h = enc["geo"](e)
                x0, y0, nw, nh = sol["leaves"][e]
                tx, ty = step(nw, nh)
                area = nw * nh
                total += area
                lit = B.bool(f"mv{rounds}.{e}")
                ds = [B.bool(f"mv{rounds}.{e}.{k}") for k in range(4)]
                B.add(x >= x0 + tx, None, [ds[0]])
                B.add(x <= x0 - tx, None, [ds[1]])
                B.add(y >= y0 + ty, None, [ds[2]])
                B.add(y <= y0 - ty, None, [ds[3]])
                B.any_of(ds, None, [lit])
                terms.append(area * lit)
            B.add(sum(terms) >= math.ceil(MOVED_AREA * total), None)
        else:
            rejected += 1
            topo = tuple(s.Value(b) for b in enc["sig"])
            if topo in failed_topos:
                # the second failure of one arrangement: forbid that arrangement (its pair relations,
                # bus sides and shapes), not the geometry around it
                B.any_of(
                    [B.neg(b) if v else b for b, v in zip(enc["sig"], topo, strict=False)], None
                )
            else:
                failed_topos.add(topo)
                # the first failure: forbid exactly this layout and nothing more (a shift of one
                # unit may be all the router needed)
                _forbid_layout(B, enc, sol, rounds)
        if len(kept) >= n:
            why = {"reason": "n reached", "rounds": rounds, "rejected_by_checker": rejected}
            break
        if clock.left <= 0:
            why = {
                "reason": "time limit (total)",
                "rounds": rounds,
                "rejected_by_checker": rejected,
            }
            break
        if rounds >= ROUNDS_PER_TILE * n:
            why = {
                "reason": f"round limit: {rounds} rounds for {n} tiles",
                "rounds": rounds,
                "rejected_by_checker": rejected,
            }
            break
        s = clock.solver("the next candidate")
        st = clock.solve(s, B.m, "the next candidate")
        if st == cp_model.INFEASIBLE:
            why = {
                "reason": "search exhausted: no further visibly different layout exists",
                "rounds": rounds,
                "rejected_by_checker": rejected,
            }
            break
        if st not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
            why = {
                "reason": f"time limit: no further layout found within {clock.cap} deterministic s per round",
                "rounds": rounds,
                "rejected_by_checker": rejected,
            }
            break
    return L, kept, why


def resolve(P, L, sol, shapes):
    """A top-level solution -> the flat geometry the router and renderer take (absolute units)."""
    inner, buses, extra = flatten(L, sol, shapes)
    sizes, d = P.sizes, P.d
    W = house.W
    H = max([y + h for (x, y, w, h) in inner.values()] + [0])
    boxes = []

    def texts_abs(texts, x, y, w):
        out = []
        for dx, dy, st, s, anchor in texts:
            tx = x + w / 2 if dx is None else x + dx
            out.append([round(tx, 2), round(y + dy, 2), st, s, anchor])
        return out

    depth = {g: len(containers(d, g)) for g in P.groups}
    for g in sorted(P.groups, key=lambda g: (depth[g], list(P.groups).index(g))):
        x, y, w, h = inner[g]
        boxes.append(
            {
                "id": g,
                "kind": "group",
                "look": P.groups[g]["look"],
                "x": x,
                "y": y,
                "w": w,
                "h": h,
                "title_h": sizes["groups"][g]["top"],
                "texts": texts_abs(sizes["groups"][g]["texts"], x, y, w),
            }
        )
    for nid in P.nodes:
        x, y, w, h = inner[nid]
        m = sizes["nodes"][nid]
        boxes.append(
            {
                "id": nid,
                "kind": "note" if m["note"] else "node",
                "look": m["look"],
                "x": x,
                "y": y,
                "w": w,
                "h": h,
                "texts": texts_abs(m["texts"], x, y, w),
            }
        )
    out_buses = []
    for bid, b in P.buses.items():
        bv = buses[bid]
        v_or = b["orient"] == "v"
        taps, projs = [], []
        for t in b["taps"]:
            x, y, w, h = inner[t["node"]]
            if v_or:
                left = x + w <= bv["p"]
                proj = y + h / 2
                taps.append(
                    {"node": t["node"], "side": "e" if left else "w", "look": t["look"], "at": proj}
                )
            else:
                above = y + h <= bv["p"]
                proj = x + w / 2
                taps.append(
                    {
                        "node": t["node"],
                        "side": "s" if above else "n",
                        "look": t["look"],
                        "at": proj,
                    }
                )
            projs.append(proj)
        lab = extra.get(f"label:{bid}")
        if b["span"] == "canvas":
            lo, hi = (bv["oy"], bv["oy"] + bv["H"]) if v_or else (bv["ox"], bv["ox"] + bv["W"])
            if L.top and not v_or:
                lo, hi = 0, W
        else:
            lo, hi = min(projs) - 2, max(projs) + 2
        label = None
        if lab:
            m = sizes["labels"][bid]
            label = {
                "x": lab["x"],
                "y": lab["y"],
                "w": m["w"],
                "h": m["h"],
                "texts": texts_abs(m["texts"], lab["x"], lab["y"], m["w"]),
            }
            lo = (lab["y"] + m["h"] + 2) if v_or else (lab["x"] + m["w"] + 2)
        p = bv["p"]
        line = [p, lo, p, hi] if v_or else [lo, p, hi, p]
        out_buses.append(
            {
                "id": bid,
                "orient": b["orient"],
                "look": b["look"],
                "line": [round(v, 2) for v in line],
                "taps": taps,
                "label": label,
            }
        )
        if label:
            H = max(H, label["y"] + label["h"])
    for _k, ev in extra.items():
        H = max(H, ev["y"] + ev["h"])
    edges = [
        {
            "id": e["id"],
            "from": e["from"],
            "to": e["to"],
            "from-side": e.get("from-side"),
            "to-side": e.get("to-side"),
            "look": e["look"],
            "arrow": e["arrow"],
            "bundle": P.bundle_of.get(e["id"]),
        }
        for e in d["edges"]
    ]
    corridors = {k: v for k, v in extra.items() if k.startswith("corridor:")}
    return {
        "W": W,
        "H": math.ceil(H),
        "boxes": boxes,
        "buses": out_buses,
        "edges": edges,
        "corridors": corridors,
    }
