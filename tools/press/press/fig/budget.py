"""The page budget: one figure fits 476 x 620 units (168 x 219 mm) with every text at its own
size. A figure that needs more is REFUSED with what it needs — never shrunk, never let overflow.

When the description marks groups `collapsible`, `split` proposes a linked figure set instead:
collapse the largest collapsible group (and its collapsible siblings, so a level collapses
evenly) until the overview fits; each collapsed group becomes a detail figure, split the same
way in turn. Every part is written as a description of its own — the original content plus a
`view` — and is shown to fit by solving it. The parts are a PROPOSAL: a split adds figures the
document must reference, so the author adopts them (press.doc), press does not."""

import copy

from . import desc, house, measure, place

REFS = "figure {}"


def min_pt(d):
    sizes = [house.TEXT[st][0] for n in d["nodes"] for st, _ in n.get("rows", n.get("cells", []))]
    sizes += [house.TEXT[st][0] for g in d["groups"] for st, _ in g.get("title", [])]
    return min(sizes)


def needs(tree, clock):
    """-> (fits?, needed height, effective description) for a raw tree (view included)."""
    d = desc.validate(copy.deepcopy(tree))
    P = place.Problem(d, measure.size_all(d))
    shapes = place.solve_groups(P, clock)
    try:
        L, sols, _ = place.solve_top(P, shapes, 1, clock, lambda sol: True)
    except place.OverBudget as e:
        return False, e.needs_h, d
    return True, sols[0]["H"], d


def _strip(tree):
    return {k: v for k, v in copy.deepcopy(tree).items() if k != "view"}


def split(tree, clock):
    """-> (parts, refused): parts = [{id, tree, needs_h, edges}], refused names a part that cannot
    fit even with everything collapsible collapsed (None when every part fits).

    Two passes. The first decides which groups collapse where. The second writes every part's
    final view with the refs of ALL parts, so an edge that leaves a detail figure ends on a marker
    naming the figure that draws its other end (the part of the innermost group around it that
    has one, else the figure this detail is part of), and measures each final part again."""
    base = _strip(tree)
    full = desc.validate(copy.deepcopy(base))
    parent = full["_parent"]
    groups = {g["id"]: g for g in full["groups"]}
    plan, refused = [], [None]

    def descendants(g):
        out = []
        for c in groups[g]["children"]:
            out.append(c)
            if c in groups:
                out += descendants(c)
        return out

    def view_for(detail, collapsed, parent_part, refs_all):
        view, refs = {}, {g: refs_all[g] for g in refs_all}
        if detail:
            view["detail"] = detail
            refs[detail] = REFS.format(parent_part)
        if collapsed:
            view["collapse"] = list(collapsed)
        if refs and (detail or collapsed):
            view["refs"] = refs
        return view

    def make(part_id, detail, parent_part):
        inside = (
            set(descendants(detail)) if detail else set(groups) | {n["id"] for n in full["nodes"]}
        )
        collapsed = []
        while True:
            refs = {g: REFS.format(f"{base['id']}-{g}") for g in collapsed}
            t = dict(copy.deepcopy(base), id=part_id)
            v = view_for(detail, collapsed, parent_part, refs)
            if v:
                t["view"] = v
            ok, h, _ = needs(t, clock)
            if ok:
                plan.append((part_id, detail, list(collapsed), parent_part))
                break
            cand = [
                g
                for g in groups
                if groups[g].get("collapsible")
                and g in inside
                and g != detail
                and g not in collapsed
                and not any(g in descendants(c) for c in collapsed)
            ]
            if not cand:
                plan.append((part_id, detail, list(collapsed), parent_part))
                refused[0] = part_id
                return
            big = max(cand, key=lambda g: (len(descendants(g)), g))
            collapsed += [g for g in cand if parent.get(g) == parent.get(big)]
        for g in collapsed:
            make(f"{base['id']}-{g}", g, part_id)

    make(f"{base['id']}-overview", None, None)
    own = {detail: REFS.format(pid) for pid, detail, _, _ in plan if detail}
    parts = []
    for pid, detail, collapsed, parent_part in plan:
        t = dict(copy.deepcopy(base), id=pid)
        v = view_for(detail, collapsed, parent_part, own)
        if v:
            t["view"] = v
        ok, h, d = needs(t, clock)
        if not ok and refused[0] is None:
            refused[0] = pid
        parts.append({"id": pid, "tree": t, "needs_h": h, "edges": d.get("_view_edges")})
    return parts, refused[0]


def report(tree, d, needs_h, cap_s, total_s, out_dir):
    """Write budget.json (+ split/<id>.fig.json for every proposed part); return the report."""
    import pathlib

    out = pathlib.Path(out_dir)
    pt = min_pt(d)
    msg = (
        f'"{d["id"]}" needs {house.W} x {needs_h} units (168 x {round(needs_h / house.W * 168)} mm) at '
        f">= {pt} pt; the page budget is {house.BUDGET_W} x {house.BUDGET_H} units "
        f"(168 x {round(house.BUDGET_H / house.W * 168)} mm). Not shrinking text, not overflowing."
    )
    rep = {
        "id": d["id"],
        "budget": [house.BUDGET_W, house.BUDGET_H],
        "needs": [house.W, needs_h],
        "min_pt": pt,
        "splittable": any(g.get("collapsible") for g in d["groups"]),
        "split": None,
        "message": msg,
    }
    if rep["splittable"]:
        parts, refused = split(tree, place.Clock(total_s, cap_s))
        (out / "split").mkdir(parents=True, exist_ok=True)
        rep["split"] = []
        rep["edges_merged"], rep["edges_dropped"], rep["edges_stubbed"] = [], [], []
        for p in parts:
            f = out / "split" / f"{p['id']}.fig.json"
            f.write_bytes(desc.canonical_bytes(p["tree"]))
            rep["split"].append(
                {
                    "id": p["id"],
                    "file": f"split/{f.name}",
                    "view": p["tree"].get("view"),
                    "needs": [house.W, p["needs_h"]],
                    "fits": p["needs_h"] <= house.BUDGET_H,
                }
            )
            for k in ("merged", "dropped", "stubbed"):
                rep[f"edges_{k}"] += [dict(x, part=p["id"]) for x in (p["edges"] or {}).get(k, [])]
        rep["refused_part"] = refused
        rep["message"] += (
            f" Proposed split into {len(parts)} linked figures (split/*.fig.json); adopt them in the "
            "document or mark more groups collapsible."
            if refused is None
            else f" Even split, part {refused!r} does not fit: mark more of its groups collapsible."
        )
    else:
        rep["message"] += (
            " Mark groups collapsible to let press propose a split into linked figures."
        )
    return rep
