"""Views: one description drawn at several depths.

  {"view": {"collapse": ["modules"], "refs": {"modules": "Figure 3"}}}
      the group `modules` is drawn as ONE box: its title plus "<n> parts · detail: Figure 3".
      Every edge and bus tap that reached inside it now ends on that box (parallel ones merge).
  {"view": {"depth": 1, "refs": {...}}}
      every collapsible group nested deeper than 1 (a group directly in the figure has depth 1)
      is collapsed as above.
  {"view": {"detail": "modules", "refs": {"modules": "Figure 2"}}}
      only `modules` and what is inside it, with the buses that tap it, and a note
      "part of: Figure 2". An edge that leaves `modules` ends on a small marker naming the box
      at its other end and the figure that box is drawn in: the ref of the innermost group
      around it that has one, else the ref of `modules` itself (the figure it is part of).

`refs` holds the text a reader follows to a linked figure; the document (press.doc) owns the
figure numbers, so press never invents them: a collapsed or detailed group without a ref is an error.

No edge disappears silently. Edges a collapse merges into one, edges a collapse swallows (both
ends inside one collapsed group) and edges a detail cuts down to a marker are listed in
`_view_edges` ({merged, dropped, stubbed}); `press fig solve` copies them into the manifest and
`budget.json`. Rules that named an element hidden by a collapse are rewritten onto the collapsed
box where that still means something (an order) and dropped where it cannot (an alignment, a
size, a hang of a hidden box); dropped rules are listed in the manifest (`view_dropped`)."""

import copy

from .desc import DescError, VIEW_KEYS, _int, _keys, _str, default_edge_id

ORDERS = ("order-x", "order-y")
LISTS = ("same-x", "same-y", "equal-width", "equal-height", "same-shape", "fill-x")


def _strip(d):
    t = {k: v for k, v in d.items() if not k.startswith("_") and k != "view"}
    return copy.deepcopy(t)


def descendants(d, gid):
    groups = {g["id"]: g for g in d["groups"]}
    out = []
    for c in groups[gid]["children"]:
        out.append(c)
        if c in groups:
            out += descendants(d, c)
    return out


def apply(d):
    from .desc import validate

    v = d["view"]
    _keys(v, VIEW_KEYS, "view")
    groups = {g["id"]: g for g in d["groups"]}
    refs = v.get("refs", {})
    if not isinstance(refs, dict):
        raise DescError("view.refs: expected an object {group-id: text}")
    for k, t in refs.items():
        if k not in groups:
            raise DescError(f"view.refs: {k!r} is not a group")
        _str(t, f"view.refs.{k}")
    collapse = v.get("collapse", [])
    if not isinstance(collapse, list):
        raise DescError(f"view.collapse: expected a list of group ids, got {collapse!r}")
    collapse = list(collapse)
    odd = [g for g in collapse if not isinstance(g, str)]
    if odd:
        raise DescError(f"view.collapse: {odd[0]!r} is not a group id (a string)")
    twice = sorted({g for g in collapse if collapse.count(g) > 1})
    if twice:
        raise DescError(f"view.collapse: {twice} listed more than once; name each group once")
    dropped, edges = [], {"merged": [], "dropped": [], "stubbed": []}
    tree = _strip(d)
    if "detail" in v:
        gid = v["detail"]
        if not isinstance(gid, str) or gid not in groups:
            raise DescError(f"view.detail: {gid!r} is not a group")
        if gid not in refs:
            raise DescError(
                f"view.refs: the detail of {gid!r} needs a ref (the text naming the figure it is part of)"
            )
        tree = _detail(tree, d, gid, refs, dropped, edges)
    if "depth" in v:
        n = _int(v["depth"], "view.depth", lo=1)
        cur = validate(tree) if "detail" in v else d
        par = cur["_parent"]
        for g in cur["groups"]:
            k, p = 1, par.get(g["id"])
            while p is not None:
                k, p = k + 1, par.get(p)
            if k > n and g.get("collapsible") and g["id"] not in collapse:
                collapse.append(g["id"])
    for gid in collapse:
        if gid not in groups:
            raise DescError(f"view.collapse: {gid!r} is not a group")
        if not groups[gid].get("collapsible"):
            raise DescError(f"view.collapse: group {gid!r} is not marked collapsible")
        if gid not in refs:
            raise DescError(
                f"view.refs: collapsing {gid!r} needs a ref (the text naming its detail figure)"
            )
    if collapse:
        tree = _collapse(tree, collapse, refs, dropped, edges)
    out = validate(tree)
    out["_view_dropped"] = dropped
    out["_view_edges"] = edges
    return out


def _collapse(tree, collapse, refs, dropped, edges):
    groups = {g["id"]: g for g in tree["groups"]}
    top = [
        g
        for g in collapse
        if g in groups and not any(g in descendants(tree, h) for h in collapse if h != g)
    ]
    rep = {}
    nodes_set = {n["id"] for n in tree["nodes"]}
    for gid in top:
        g = groups[gid]
        members = descendants(tree, gid)
        n = len([m for m in members if m in nodes_set])
        for m in members:
            rep[m] = gid
        tree["nodes"] = [x for x in tree["nodes"] if x["id"] not in members]
        tree["nodes"].append(
            {
                "id": gid,
                "look": g["look"],
                "rows": g.get("title", []) + [["m", f"{n} parts · detail: {refs[gid]}"]],
            }
        )
        tree["groups"] = [x for x in tree["groups"] if x["id"] != gid and x["id"] not in members]
        tree["buses"] = [b for b in tree.get("buses", []) if b["id"] not in members]

    def m(i):
        return rep.get(i, i)

    for b in tree.get("buses", []):
        seen, taps = set(), []
        for t in b["taps"]:
            t = {"node": t} if isinstance(t, str) else dict(t)
            k = m(t["node"])
            if k not in seen:
                seen.add(k)
                taps.append(dict(t, node=k))
        b["taps"] = taps
    es, kept, emap = [], {}, {}
    for e in tree.get("edges", []):
        a, z = m(e["from"]), m(e["to"])
        eid = e.get("id", default_edge_id(e))
        if a == z:
            emap[eid] = None
            edges["dropped"].append({"edge": eid, "inside": a})
            continue
        if (a, z) in kept:
            emap[eid] = None
            edges["merged"].append({"edge": eid, "into": kept[(a, z)]})
            continue
        ne = dict(e, **{"from": a, "to": z})
        ne["id"] = eid
        kept[(a, z)] = eid
        es.append(ne)
        emap[eid] = eid
    tree["edges"] = es
    cs = []
    for c in tree.get("constraints", []):
        name, args = c[0], c[1:]
        if name in ORDERS:
            ns = []
            for i in args[0]:
                if m(i) not in ns:
                    ns.append(m(i))
            if len(ns) >= 2:
                cs.append([name, ns] + list(args[1:]))
            else:
                dropped.append(c)
        elif name in LISTS:
            if any(i in rep for i in args[0]):
                dropped.append(c)
            else:
                cs.append(c)
        elif name == "near":
            if args[0] in rep or any(emap.get(x) is None for x in args[1]):
                dropped.append(c)
            else:
                cs.append([name, args[0], [emap[x] for x in args[1]]] + list(args[2:]))
        else:
            if any(isinstance(a, str) and a in rep for a in args):
                dropped.append(c)
            else:
                cs.append(c)
    tree["constraints"] = cs
    return tree


def _label(d, x):
    """The text a reader knows box x by: its first row, cell or title line, else its id."""
    for n in d["nodes"]:
        if n["id"] == x:
            return (n.get("rows") or n.get("cells"))[0][1]
    for g in d["groups"]:
        if g["id"] == x and g.get("title"):
            return g["title"][0][1]
    return x


def _detail(tree, d, gid, refs, dropped, edges):
    members = set(descendants(tree, gid)) | {gid}
    keep_nodes = [n for n in tree["nodes"] if n["id"] in members]
    note = f"{gid}-part-of"
    keep_nodes.append({"id": note, "look": "note", "rows": [["m", f"part of: {refs[gid]}"]]})

    def where(x):
        """The ref of the figure that draws box x: its innermost group with a ref, else the parent's."""
        p = d["_parent"].get(x)
        while p is not None:
            if p in refs and p != gid:
                return refs[p]
            p = d["_parent"].get(p)
        return refs[gid]

    buses = []
    for b in tree.get("buses", []):
        if b["id"] in members:
            buses.append(b)
            continue
        taps = [t for t in b["taps"] if (t if isinstance(t, str) else t["node"]) in members]
        if taps:
            buses.append(dict(b, taps=taps, span="taps"))
    kept_edges, markers = [], {}
    for e in tree.get("edges", []):
        ins = (e["from"] in members, e["to"] in members)
        if all(ins):
            kept_edges.append(e)
        elif any(ins):
            out_end = "to" if ins[0] else "from"
            other = e[out_end]
            mid = f"ref-{other}"
            if mid not in markers:
                markers[mid] = {
                    "id": mid,
                    "look": "note",
                    "rows": [["m", f"{_label(d, other)} · {where(other)}"]],
                }
            ne = dict(e, **{out_end: mid})
            ne.pop("from-side" if out_end == "from" else "to-side", None)
            kept_edges.append(ne)
            edges["stubbed"].append({"edge": e.get("id", default_edge_id(e)), "marker": mid})
    keep_nodes += list(markers.values())
    bus_ids = {b["id"] for b in buses}
    keep = members | bus_ids | {note} | set(markers)
    groups = [g for g in tree["groups"] if g["id"] in members]
    eids = {e.get("id", default_edge_id(e)) for e in kept_edges}
    cs = []
    for c in tree.get("constraints", []):
        ids = [a for a in c[1:] if isinstance(a, str)] + [
            x for a in c[1:] if isinstance(a, list) for x in a
        ]
        named = [i for i in ids if i in d["_ids"]]
        if c[0] == "near":
            ok = c[1] in keep and all(x in eids for x in c[2])
        else:
            ok = all(i in keep for i in named)
        if ok and c[0] != "span-x":
            cs.append(c)
        else:
            dropped.append(c)
    cs.append(["order-y", [gid, note], 4])
    out = {
        k: v
        for k, v in tree.items()
        if k not in ("nodes", "groups", "buses", "edges", "constraints")
    }
    out.update(nodes=keep_nodes, groups=groups, buses=buses, edges=kept_edges, constraints=cs)
    return out
