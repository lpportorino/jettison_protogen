"""The figure description: reading, validation, canonical bytes, problem hash, views.

A description is the canonical JSON that press.doc writes from `figs/<id>.edn` (keywords as
strings, kebab-case). Canonical bytes are fixed so both halves compute the same hash:
    json.dumps(tree, sort_keys=True, ensure_ascii=False, separators=(',', ':')) + '\\n'
in UTF-8. press REFUSES a file whose bytes are not canonical (it never re-serialises behind the
author's back), so `problem_hash` = sha256 of the file bytes, and the JVM half's writer is held
to the same form by that refusal. The description carries integers only — a float anywhere is
an error — so number formatting cannot differ between the two languages.

Every key and every constraint name is checked: an unknown one is an error naming it and where
it is. Every id (figure, node, group, bus, edge) matches ID_RE, so it can never break out of an
SVG attribute or a file name, and a description file is named `<id>.fig.json`. Every integer
lies in -INT_MAX..INT_MAX: a gap or a pad beyond any page is a mistake, and the solver's integer domains
stay far from overflow. `validate` returns the effective description (the view applied, edge
ids filled in)."""

import copy
import hashlib
import json
import pathlib
import re

from . import house


ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*")
INT_MAX = 100000


class DescError(Exception):
    """A description the pipeline refuses; the message names the offending key or value."""


TOP_KEYS = {"id", "kind", "nodes", "groups", "buses", "edges", "constraints", "soft", "view"}
NODE_KEYS = {"id", "look", "rows", "cells", "column-set", "pad", "align"}
GROUP_KEYS = {"id", "look", "title", "align", "children", "layout", "collapsible"}
BUS_KEYS = {"id", "orient", "mode", "label", "taps", "look", "span"}
TAP_KEYS = {"node", "look"}
EDGE_KEYS = {"id", "from", "to", "kind", "from-side", "to-side", "look", "arrow"}
SOFT_KEYS = {"height", "edges"}
VIEW_KEYS = {"collapse", "depth", "detail", "refs"}
SIDES = {"n", "s", "e", "w"}
EDGE_KINDS = {
    # kind -> (default look, default arrow); a skip edge bundles with the other skip edges
    # leaving the same box from the same side into ONE trunk.
    "arrow": ("solid", "end"),
    "tap": ("tap", "none"),
    "skip": ("dashed", "end"),
}
# constraint name -> argument kinds; 'ids' = list of >= 2 element ids, 'id' = one element id,
# 'bus' = a bus id, 'int' = integer >= 0, 'int?' = optional trailing integer, 'side:<set>' = one
# of a fixed set, 'edges' = list of >= 1 edge ids.
CONSTRAINTS = {
    "order-x": ["ids", "int"],
    "order-y": ["ids", "int", "int?"],
    "same-x": ["ids"],
    "same-y": ["ids"],
    "equal-width": ["ids"],
    "equal-height": ["ids"],
    "same-shape": ["ids"],
    "span-x": ["id"],
    "fill-x": ["ids", "int"],
    "hang": ["id", "bus", "int", "int", "side:free,above,below,left,right"],
    "follow": ["id", "id", "bus", "int"],
    "beside": ["id", "id", "int"],
    "within-y": ["id", "id"],
    "span-y": ["id", "id", "id"],
    "near": ["id", "edges", "side:n,s,e,w", "int"],
}


def canonical_bytes(tree):
    return (
        json.dumps(tree, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n"
    ).encode("utf-8")


def problem_hash(raw):
    return hashlib.sha256(raw).hexdigest()


def read(path):
    """-> (raw bytes, parsed tree). Refuses a missing file, non-canonical bytes, floats and
    integers out of range."""
    try:
        with open(path, "rb") as fh:
            raw = fh.read()
    except OSError as e:
        raise DescError(f"{path}: cannot read the description ({e.strerror})") from e
    try:
        tree = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise DescError(f"{path}: not UTF-8 JSON ({e})") from e
    _no_floats(tree, "$")
    try:
        canon = canonical_bytes(tree)
    except UnicodeEncodeError as e:
        raise DescError(
            f"{path}: a string holds a lone surrogate escape ({e.reason}); not UTF-8 text"
        ) from e
    if raw != canon:
        at = next(
            (i for i, (a, b) in enumerate(zip(raw, canon, strict=False)) if a != b),
            min(len(raw), len(canon)),
        )
        raise DescError(
            f"{path}: not in canonical form (first difference at byte {at}: "
            f"{raw[max(0, at - 20) : at + 20]!r} vs {canon[max(0, at - 20) : at + 20]!r}); "
            "canonical = keys sorted, no whitespace, UTF-8 unescaped, one trailing newline"
        )
    return raw, tree


def _no_floats(x, where):
    if isinstance(x, float):
        raise DescError(f"{where}: {x!r} is a float; a description carries integers only")
    if isinstance(x, int) and not isinstance(x, bool) and not -INT_MAX <= x <= INT_MAX:
        raise DescError(f"{where}: {x} is out of range (integers lie within +-{INT_MAX})")
    if isinstance(x, dict):
        for k, v in x.items():
            _no_floats(v, f"{where}.{k}")
    elif isinstance(x, list):
        for i, v in enumerate(x):
            _no_floats(v, f"{where}[{i}]")


def _keys(obj, allowed, where, required=()):
    if not isinstance(obj, dict):
        raise DescError(f"{where}: expected an object, got {type(obj).__name__}")
    unknown = sorted(set(obj) - allowed)
    if unknown:
        raise DescError(f"{where}: unknown key(s) {unknown}; allowed {sorted(allowed)}")
    missing = [k for k in required if k not in obj]
    if missing:
        raise DescError(f"{where}: missing required key(s) {missing}")


def _str(v, where):
    if not isinstance(v, str) or not v:
        raise DescError(f"{where}: expected a non-empty string, got {v!r}")
    return v


def _id(v, where):
    if not isinstance(v, str) or not ID_RE.fullmatch(v):
        raise DescError(
            f'{where}: {v!r} is not an id (letters, digits, "_" and "-", starting with a letter or '
            "digit)"
        )
    return v


def _int(v, where, lo=0):
    if not isinstance(v, int) or isinstance(v, bool) or v < lo:
        raise DescError(f"{where}: expected an integer >= {lo}, got {v!r}")
    return v


def _enum(v, allowed, where):
    if not isinstance(v, str) or v not in allowed:
        raise DescError(f"{where}: {v!r} is not one of {sorted(allowed)}")
    return v


def _rows(rows, where, allow_empty=False):
    if not isinstance(rows, list) or (not rows and not allow_empty):
        raise DescError(f"{where}: expected a list of [style text] rows")
    for i, r in enumerate(rows):
        if not (isinstance(r, list) and len(r) == 2):
            raise DescError(f"{where}[{i}]: a row is [style-key text], got {r!r}")
        _enum(r[0], set(house.TEXT), f"{where}[{i}] style")
        _str(r[1], f"{where}[{i}] text")


def validate(tree):
    """Validate the whole tree; return the effective description (view applied)."""
    if isinstance(tree, dict) and tree.get("kind") == "chart":
        raise DescError(
            'kind "chart": a chart is drawn by `press chart <chart.fig.json> <pick.json> <out.svg>` '
            '(and rebuilt by `press fig rebuild`); `press fig solve` takes kind "diagram"'
        )
    _keys(tree, TOP_KEYS, "description", required=("id", "kind"))
    _id(tree["id"], "id")
    _enum(tree["kind"], {"diagram"}, "kind")
    d = copy.deepcopy(tree)
    for k in ("nodes", "groups", "buses", "edges", "constraints"):
        d.setdefault(k, [])
        if not isinstance(d[k], list):
            raise DescError(f"{k}: expected a list")
    if not d["nodes"]:
        raise DescError("nodes: a diagram needs at least one node")
    ids = {}
    for kind, items, allowed, req in (
        ("node", d["nodes"], NODE_KEYS, ("id", "look")),
        ("group", d["groups"], GROUP_KEYS, ("id", "look", "children")),
        ("bus", d["buses"], BUS_KEYS, ("id", "orient", "taps")),
    ):
        for i, it in enumerate(items):
            where = f"{kind}s[{i}]"
            _keys(it, allowed, where, required=req)
            iid = _id(it["id"], f"{where}.id")
            if iid in ids:
                raise DescError(f"{where}: id {iid!r} already used by a {ids[iid]}")
            ids[iid] = kind
    for i, n in enumerate(d["nodes"]):
        w = f"nodes[{i}] ({n['id']})"
        _enum(n["look"], set(house.LOOKS), f"{w}.look")
        if ("rows" in n) == ("cells" in n):
            raise DescError(
                f"{w}: give exactly one of rows (stacked lines) or cells (one line of table cells)"
            )
        if "rows" in n:
            _rows(n["rows"], f"{w}.rows")
            if "column-set" in n:
                raise DescError(f"{w}: column-set belongs with cells, not rows")
        else:
            _rows(n["cells"], f"{w}.cells")
            if "column-set" not in n:
                raise DescError(
                    f"{w}: cells need a column-set (the nodes whose cells share tab stops)"
                )
            _str(n["column-set"], f"{w}.column-set")
        if "pad" in n:
            _int(n["pad"], f"{w}.pad")
        if "align" in n:
            _enum(n["align"], {"left", "center"}, f"{w}.align")
    sets = {}
    for n in d["nodes"]:
        if "cells" in n:
            sets.setdefault(n["column-set"], []).append(n)
    for k, ns in sets.items():
        widths = {len(n["cells"]) for n in ns}
        if len(widths) != 1:
            raise DescError(
                f"column-set {k!r}: every node in it needs the same number of cells, got {sorted(widths)}"
            )
    parent = {}
    for i, g in enumerate(d["groups"]):
        w = f"groups[{i}] ({g['id']})"
        _enum(g["look"], set(house.LOOKS), f"{w}.look")
        if "title" in g:
            _rows(g["title"], f"{w}.title", allow_empty=True)
        if "align" in g:
            _enum(g["align"], {"left", "center"}, f"{w}.align")
        g.setdefault("layout", "free")
        _enum(g["layout"], {"stack", "row", "free"}, f"{w}.layout")
        if "collapsible" in g and not isinstance(g["collapsible"], bool):
            raise DescError(f"{w}.collapsible: expected true or false")
        if not isinstance(g["children"], list) or not g["children"]:
            raise DescError(f"{w}.children: expected a non-empty list of ids")
        for c in g["children"]:
            if not isinstance(c, str) or c not in ids:
                raise DescError(f"{w}.children: {c!r} is not a node, group or bus")
            if c == g["id"]:
                raise DescError(f"{w}.children: a group cannot contain itself")
            if c in parent:
                raise DescError(f"{w}.children: {c!r} is already a child of {parent[c]!r}")
            parent[c] = g["id"]
    for gid in (g["id"] for g in d["groups"]):  # no cycles
        seen, p = set(), gid
        while p in parent:
            if p in seen:
                raise DescError(f"groups: {gid!r} sits inside itself through {sorted(seen)}")
            seen.add(p)
            p = parent[p]
    for i, b in enumerate(d["buses"]):
        w = f"buses[{i}] ({b['id']})"
        _enum(b["orient"], {"h", "v"}, f"{w}.orient")
        b.setdefault("mode", "spine")
        if b["mode"] != "spine":
            raise DescError(
                f"{w}.mode: {b['mode']!r} is not built; a bus is drawn as a spine with taps "
                '(edges that share a trunk: kind "skip")'
            )
        if "label" in b:
            _rows(b["label"], f"{w}.label")
        b.setdefault("look", "bus")
        _enum(b["look"], set(house.EDGE_LOOKS), f"{w}.look")
        b.setdefault("span", "taps")
        _enum(b["span"], {"taps", "canvas"}, f"{w}.span")
        if not isinstance(b["taps"], list) or not b["taps"]:
            raise DescError(f"{w}.taps: expected a non-empty list")
        taps = []
        for j, t in enumerate(b["taps"]):
            if isinstance(t, str):
                t = {"node": t}
            _keys(t, TAP_KEYS, f"{w}.taps[{j}]", required=("node",))
            if not isinstance(t["node"], str) or t["node"] not in ids or ids[t["node"]] == "bus":
                raise DescError(f"{w}.taps[{j}]: {t['node']!r} is not a node or group")
            t.setdefault("look", "tap")
            _enum(t["look"], set(house.EDGE_LOOKS), f"{w}.taps[{j}].look")
            taps.append(t)
        if len({t["node"] for t in taps}) != len(taps):
            raise DescError(f"{w}.taps: a node is tapped twice")
        b["taps"] = taps
    eids = set()
    for i, e in enumerate(d["edges"]):
        w = f"edges[{i}]"
        _keys(e, EDGE_KEYS, w, required=("from", "to", "kind"))
        for end in ("from", "to"):
            if not isinstance(e[end], str) or e[end] not in ids or ids[e[end]] == "bus":
                raise DescError(f"{w}.{end}: {e[end]!r} is not a node or group")
        if e["from"] == e["to"]:
            raise DescError(f"{w}: an edge from {e['from']!r} to itself")
        _enum(e["kind"], set(EDGE_KINDS), f"{w}.kind")
        for s in ("from-side", "to-side"):
            if s in e:
                _enum(e[s], SIDES, f"{w}.{s}")
        look, arrow = EDGE_KINDS[e["kind"]]
        e.setdefault("look", look)
        e.setdefault("arrow", arrow)
        _enum(e["look"], set(house.EDGE_LOOKS), f"{w}.look")
        _enum(e["arrow"], {"end", "both", "none"}, f"{w}.arrow")
        e.setdefault("id", default_edge_id(e))
        _id(e["id"], f"{w}.id")
        if e["id"] in eids:
            raise DescError(f"{w}: edge id {e['id']!r} is used twice (give the edges distinct ids)")
        eids.add(e["id"])
    buses = {b["id"] for b in d["buses"]}
    for i, c in enumerate(d["constraints"]):
        _constraint(c, i, ids, buses, eids)
    if "soft" in d:
        _keys(d["soft"], SOFT_KEYS, "soft")
        for k, v in d["soft"].items():
            _int(v, f"soft.{k}")
    d["soft"] = {"height": 10, "edges": 1, **d.get("soft", {})}
    d["_parent"] = parent
    d["_ids"] = ids
    if "view" in d:
        from . import view  # the view module needs the validated tree

        d = view.apply(d)
    return d


def _constraint(c, i, ids, buses, eids):
    w = f"constraints[{i}]"
    if not isinstance(c, list) or not c or not isinstance(c[0], str):
        raise DescError(f'{w}: a constraint is ["name" args...], got {c!r}')
    name = c[0]
    if name not in CONSTRAINTS:
        raise DescError(f"{w}: unknown constraint {name!r}; known: {sorted(CONSTRAINTS)}")
    spec = CONSTRAINTS[name]
    args = c[1:]
    need = len([s for s in spec if not s.endswith("?")])
    if not need <= len(args) <= len(spec):
        raise DescError(
            f"{w} {name}: expected {need}..{len(spec)} arguments {spec}, got {len(args)}"
        )
    for j, (s, a) in enumerate(zip(spec, args, strict=False)):
        at = f"{w} {name} argument {j + 1}"
        if s == "ids":
            if not isinstance(a, list) or len(a) < 2:
                raise DescError(f"{at}: expected a list of at least two ids")
            for x in a:
                if not isinstance(x, str) or x not in ids:
                    raise DescError(f"{at}: {x!r} is not a node, group or bus")
            if len(set(a)) != len(a):
                raise DescError(f"{at}: an id appears twice")
        elif s == "id":
            if not isinstance(a, str) or a not in ids:
                raise DescError(f"{at}: {a!r} is not a node, group or bus")
        elif s == "bus":
            if not isinstance(a, str) or a not in buses:
                raise DescError(f"{at}: {a!r} is not a bus")
        elif s.startswith("int"):
            _int(a, at)
        elif s.startswith("side:"):
            _enum(a, set(s[5:].split(",")), at)
        elif s == "edges":
            if not isinstance(a, list) or not a:
                raise DescError(f"{at}: expected a list of edge ids")
            for x in a:
                if not isinstance(x, str) or x not in eids:
                    raise DescError(f"{at}: {x!r} is not an edge id")
    if name == "order-y" and len(args) == 3 and args[2] < args[1]:
        raise DescError(f"{w} order-y: the largest gap {args[2]} is below the smallest {args[1]}")
    if name == "hang" and args[3] < args[2]:
        raise DescError(f"{w} hang: the largest gap {args[3]} is below the smallest {args[2]}")


def label(c):
    """A constraint written back in the description's own terms, for messages."""
    return json.dumps(c, ensure_ascii=False)


def default_edge_id(e):
    """The id of an edge that names none: `<from>-<to>` (two edges between the same pair need ids)."""
    return f"{e['from']}-{e['to']}"


def load(path):
    """-> (problem_hash, effective description). The file must be named `<id>.fig.json`."""
    raw, tree = read(path)
    name = pathlib.Path(path).name
    d = validate(tree)
    if name != f"{d['id']}.fig.json":
        raise DescError(
            f"{path}: the description id {d['id']!r} is not the file stem; name the file "
            f"{d['id']}.fig.json"
        )
    return problem_hash(raw), d
