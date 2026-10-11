"""The pick record checked against its description — by structure, with no solver, no router, no
text shaper and no browser (this module imports only the description reader and the house).

`rebuild` trusts a record only after this: the record is well-formed; its boxes, buses and edges
are exactly the description's (the same ids, and for every box the same look and the same
(style, text) rows in the same order); every box lies on the 476-unit canvas. What it does NOT
prove: that the geometry is a good layout (it does not re-run the checker), or that boxes still
sit where the routed edges meet them — a record that moves a box but keeps every id, text and
look, and lies within the canvas, rebuilds."""

import math
import re

from . import house

SHA_RE = re.compile(r"[0-9a-f]{64}")


class RecordError(Exception):
    """A record that is malformed or does not describe this description's figure."""


def _num(v, where):
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
        raise RecordError(f"{where}: expected a number, got {v!r}")
    return v


def _rows(texts, where):
    if not isinstance(texts, list):
        raise RecordError(f"{where}: texts must be a list")
    out = []
    for i, t in enumerate(texts):
        if not (isinstance(t, list) and len(t) == 5):
            raise RecordError(f"{where}.texts[{i}]: expected [x y style text anchor], got {t!r}")
        _num(t[0], f"{where}.texts[{i}].x")
        _num(t[1], f"{where}.texts[{i}].y")
        if t[2] not in house.TEXT or not isinstance(t[3], str) or t[4] not in ("start", "middle"):
            raise RecordError(f"{where}.texts[{i}]: bad style, text or anchor {t[2:]!r}")
        out.append((t[2], t[3]))
    return out


def expected(d):
    """What the figure of description d must contain: boxes {id: (kind, look, rows)}, buses
    {id: (label rows, tapped ids)}, edge ids (the description's edges and one per bus tap)."""
    boxes = {
        n["id"]: (
            "note" if n["look"] == "note" else "node",
            n["look"],
            [tuple(r) for r in (n.get("rows") or n.get("cells"))],
        )
        for n in d["nodes"]
    }
    boxes.update(
        {g["id"]: ("group", g["look"], [tuple(r) for r in g.get("title", [])]) for g in d["groups"]}
    )
    buses = {
        b["id"]: ([tuple(r) for r in b.get("label", [])], sorted(t["node"] for t in b["taps"]))
        for b in d["buses"]
    }
    edges = {e["id"] for e in d["edges"]} | {
        f"{b['id']}~{t['node']}" for b in d["buses"] for t in b["taps"]
    }
    return boxes, buses, edges


def check(d, rec, phash):
    """Raise RecordError unless rec is a well-formed record of description d. Returns 'stale'
    when the description changed since the pick (the caller's exit 2), else None."""
    if not isinstance(rec, dict):
        raise RecordError("the record is not a JSON object")
    for k in ("id", "problem_hash", "svg_sha256", "geometry"):
        if k not in rec:
            raise RecordError(f"the record has no {k!r}")
    if not isinstance(rec["svg_sha256"], str) or not SHA_RE.fullmatch(rec["svg_sha256"]):
        raise RecordError(f"svg_sha256 {rec['svg_sha256']!r} is not a sha256")
    if rec["problem_hash"] != phash:
        return "stale"
    g = rec["geometry"]
    if not isinstance(g, dict) or not all(k in g for k in ("W", "H", "boxes", "buses", "edges")):
        raise RecordError("geometry needs W, H, boxes, buses and edges")
    if g["W"] != house.W:
        raise RecordError(f"geometry W is {g['W']!r}; the canvas is {house.W}")
    _num(g["H"], "geometry.H")
    want_boxes, want_buses, want_edges = expected(d)
    if not all(isinstance(x, list) for x in (g["boxes"], g["buses"], g["edges"])):
        raise RecordError("geometry boxes, buses and edges must be lists")
    got = {}
    for i, b in enumerate(g["boxes"]):
        if not isinstance(b, dict) or not all(
            k in b for k in ("id", "kind", "look", "x", "y", "w", "h", "texts")
        ):
            raise RecordError(f"geometry.boxes[{i}] is malformed")
        x, y, w, h = (_num(b[k], f"box {b['id']!r}.{k}") for k in ("x", "y", "w", "h"))
        if x < 0 or y < 0 or w <= 0 or h <= 0 or x + w > house.W or y + h > g["H"]:
            raise RecordError(
                f"box {b['id']!r} at ({x}, {y}) size {w} x {h} does not lie on the canvas "
                f"({house.W} x {g['H']})"
            )
        if b["look"] not in house.LOOKS:
            raise RecordError(f"box {b['id']!r}: look {b['look']!r} is not a house look")
        got[b["id"]] = (b["kind"], b["look"], _rows(b["texts"], f"box {b['id']!r}"))
    if set(got) != set(want_boxes):
        raise RecordError(
            f"the record's boxes {sorted(set(got) ^ set(want_boxes))} differ from the description's"
        )
    for k, v in got.items():
        if v != want_boxes[k]:
            raise RecordError(
                f"box {k!r}: the record has {v[0]} look {v[1]!r} rows {v[2]}, the description has "
                f"{want_boxes[k][0]} look {want_boxes[k][1]!r} rows {want_boxes[k][2]}"
            )
    gb = {}
    for i, b in enumerate(g["buses"]):
        if not isinstance(b, dict) or not all(
            k in b for k in ("id", "line", "taps", "label", "look")
        ):
            raise RecordError(f"geometry.buses[{i}] is malformed")
        rows = _rows(b["label"]["texts"], f"bus {b['id']!r} label") if b["label"] else []
        gb[b["id"]] = (rows, sorted(t["node"] for t in b["taps"]))
    if gb != want_buses:
        raise RecordError(f"the record's buses {gb} differ from the description's {want_buses}")
    ge = set()
    for i, e in enumerate(g["edges"]):
        if not isinstance(e, dict) or not all(
            k in e for k in ("id", "net", "look", "arrow", "points")
        ):
            raise RecordError(f"geometry.edges[{i}] is malformed")
        if e["look"] not in house.EDGE_LOOKS or e["arrow"] not in ("end", "both", "none"):
            raise RecordError(f"edge {e['id']!r}: bad look or arrow")
        for p in e["points"]:
            if not (isinstance(p, list) and len(p) == 2):
                raise RecordError(f"edge {e['id']!r}: a point is not [x y]")
            _num(p[0], f"edge {e['id']!r}")
            _num(p[1], f"edge {e['id']!r}")
        ge.add(e["id"])
    if ge != want_edges:
        raise RecordError(
            f"the record's edges {sorted(ge ^ want_edges)} differ from the description's"
        )
    return None
