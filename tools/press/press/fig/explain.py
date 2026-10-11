"""Explain an unsatisfiable level with Z3.

The level is re-encoded by the SAME `model.encode` the placer uses, on the Z3 backend: every
rule sits behind its own tracked literal. Z3's unsat core, minimised, is a smallest set of rules
that cannot hold together; each is reported in the description's own terms — a constraint as it
was written (with its index), a house rule naming the elements it binds."""

import z3

from .model import Z3, encode

TIMEOUT_MS = 60000


def explain(level):
    z3.set_param("smt.core.minimize", True)
    z3.set_param("smt.random_seed", 1)
    z3.set_param("sat.random_seed", 1)
    B = Z3()
    B.s.set("timeout", TIMEOUT_MS)
    enc = encode(level, B)
    names = {lit: t for t, lit in B.tags.items()}
    st = B.s.check(*B.tags.values())
    out = {"level": level.name, "status": str(st), "core": []}
    if st == z3.unsat:
        core = sorted((names[c] for c in B.s.unsat_core()), key=_order)
        out["core"] = [enc["tagged"][t] for t in core]
        cons = [c for c in out["core"] if "constraint" in c]
        rules = [c for c in out["core"] if "rule" in c]
        out["message"] = (
            f"{level.name}: these {len(out['core'])} rules cannot all hold — "
            + "; ".join([c["text"] for c in cons] + [_rule_text(r) for r in rules])
        )
    elif st == z3.sat:
        out["message"] = (
            f"{level.name}: Z3 finds the rules satisfiable while CP-SAT did not — "
            "the two encodings disagree; this is a defect in press, not in the description"
        )
    else:
        out["message"] = (
            f"{level.name}: Z3 gave no answer within {TIMEOUT_MS // 1000} s ({B.s.reason_unknown()})"
        )
    return out


def _order(t):
    head = t.split(" ", 1)[0]
    rank = {"constraint": 0, "canvas-width": 1, "arrow-room": 2, "corridor": 3}.get(head, 4)
    return rank, t


def _rule_text(r):
    k = r["rule"]
    if k == "canvas-width":
        return f"{r['element']} must fit in the {r['width']}-unit width of {r['level']}"
    if k == "no-overlap":
        return f"{r['elements'][0]} and {r['elements'][1]} may not overlap (clearance {r['clearance']})"
    if k == "bus-side":
        return f"{r['element']} must lie wholly on one side of bus {r['bus']}"
    if k == "arrow-room":
        return f"edge {r['edge']} needs {r['min']} units of straight run for its arrowhead"
    if k == "tap-room":
        return f"the tap from bus {r['bus']} to {r['element']} needs {r['min']} units"
    if k == "corridor":
        return f"edges {r['edges']} need an {r['width']}-unit corridor beside their boxes"
    if k == "layout":
        return f"group {r['group']} is a {r['layout']} of {r['children']}"
    if k == "bus-label-room":
        return f"the label of bus {r['bus']} needs room before its first tap"
    return str(r)
