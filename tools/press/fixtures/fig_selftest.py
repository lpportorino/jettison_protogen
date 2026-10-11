"""The figure pipeline's both-directions proof (`press selftest` runs it after the deck fixtures;
`fig_launcher_selftest.sh` then drives every subcommand through bin/press itself).
usage: fig_selftest.py <out-dir>   (inside the press image; the tool tree at /work)

Every refusal and every check is shown firing on a planted defect AND staying quiet on a good
figure; anything else is a FAIL. Sections:
  good      the reference descriptions and a side-less two-box figure solve, pick, check clean
  checker   every base scores 0; each registered check counts more on its plant than on its base;
            an SVG reaching outside itself is a defect
  rebuild   byte-identical twice, needs no solver/router/shaper/browser; a changed description
            -> 2; a tampered, a consistently re-hashed or a malformed record -> 3, no traceback
  unsat     a row that cannot fit -> 4 naming it; a box wider than the canvas -> 4 naming it
  budget    60 stacked rows -> 5 (not 4); collapsible groups -> 5 with a split in which every
            original edge survives as an edge or a marked stub
  determinism  two solves of book-cart and of a loose figure write identical manifests and candidates
  diversity    the placer's notion of "visibly different" is the filter's; the manifest says why
               a sheet is short
  input     hostile ids, ids that are not the file stem, non-canonical bytes, floats, unknown
            keys and constraints, bad arguments, missing files, nonexistent tiles -> 6, and no
            traceback ever reaches stderr
  router    the start of a route never moves; a short last leg is reserved by the placer
  limits    a deep group tree under a tiny total limit fails by name
  hardening a trailing-newline id, a doubled collapse, list-valued view entries and a mutation sweep
            over the reference descriptions are refused (6), never thrown (7)
(the shared description corpus is checked by desc_corpus.py, the LAST step of `press selftest`)"""

import copy
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import traceback
import xml.etree.ElementTree as ET

from fig_plants import plants

OUT = pathlib.Path(sys.argv[1])
FIGS = pathlib.Path("/work/fixtures/figs")
ENV = dict(os.environ, PYTHONPATH="/work/press")
fails, passes = [], []


def canon(t):
    return (
        json.dumps(t, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n"
    ).encode()


def write_fig(t, sub=None):
    """Write description t as <OUT>/<sub or id>/<id>.fig.json (the file stem IS the id)."""
    d = OUT / (sub or t["id"])
    d.mkdir(parents=True, exist_ok=True)
    f = d / f"{t['id']}.fig.json"
    f.write_bytes(canon(t))
    return f


def run_press(*args, env=None):
    r = subprocess.run(
        [sys.executable, "-m", "fig.cli", *map(str, args)],
        capture_output=True,
        text=True,
        env=env or ENV,
    )
    try:
        out = json.loads(r.stdout.strip().splitlines()[-1]) if r.stdout.strip() else {}
    except json.JSONDecodeError:
        out = {"raw": r.stdout[-300:]}
    return r.returncode, out, r.stderr


def expect(name, ok, detail=""):
    (passes if ok else fails).append(name)
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}", flush=True)


def section(fn):
    """Run one section; an exception inside it is a FAIL of that section, never a crash."""
    try:
        fn()
    except Exception as e:  # noqa: BLE001 - the harness reports, it does not judge
        expect(
            f"section {fn.__name__} ran to the end",
            False,
            f"{type(e).__name__}: {e}\n{traceback.format_exc()[-600:]}",
        )


def clean(rc, err):
    return "Traceback" not in err


OUT.mkdir(parents=True, exist_ok=True)
picks = OUT / "picks"
picks.mkdir(exist_ok=True)
figs = {}


def good():
    for name in ("library-map", "book-cart", "repair-flow", "reading-tables", "vocab"):
        rc, o, err = run_press("solve", FIGS / f"{name}.fig.json", OUT / name)
        expect(
            f"solve {name} exit 0",
            rc == 0,
            f"rc={rc} tiles={o.get('tiles')} {o.get('message', '')[:160]}",
        )
        rc, o, err = run_press(
            "pick",
            FIGS / f"{name}.fig.json",
            OUT / name,
            1,
            picks / f"{name}.pick.json",
            picks / f"{name}.svg",
        )
        expect(
            f"pick {name} tile 1 exit 0",
            rc == 0,
            f"rc={rc} {o.get('status')} {o.get('message', '')[:160]}",
        )
        rc, o, _ = run_press("check", picks / f"{name}.svg")
        expect(
            f"check {name} pick: 0 defects",
            rc == 0 and o.get("defects") == 0,
            f"rc={rc} by={o.get('by')}",
        )
        if (
            name in ("library-map", "book-cart", "repair-flow")
            and (picks / f"{name}.pick.json").exists()
        ):
            figs[name] = (
                (picks / f"{name}.svg").read_text(encoding="utf-8"),
                json.loads((picks / f"{name}.pick.json").read_text())["geometry"],
            )
    recs = [
        picks / f"{n}.pick.json"
        for n in ("library-map", "book-cart", "repair-flow", "reading-tables", "vocab")
    ]
    timed = [
        r.name
        for r in recs
        if r.exists() and (b'_ms"' in r.read_bytes() or b"timing" in r.read_bytes())
    ]
    expect(
        "F12 no pick record carries a timing",
        all(r.exists() for r in recs) and not timed,
        f"timed={timed}",
    )
    # F1: a plain arrow between two boxes, no sides given — the router picks the facing sides
    ctl = {
        "id": "ctl",
        "kind": "diagram",
        "constraints": [["order-x", ["a", "b"], 30]],
        "edges": [{"from": "a", "kind": "arrow", "to": "b"}],
        "nodes": [
            {"id": "a", "look": "node", "rows": [["b", "box a"]]},
            {"id": "b", "look": "node", "rows": [["b", "box b"]]},
        ],
    }
    f = write_fig(ctl)
    rc, o, err = run_press("solve", f, OUT / "ctl" / "out")
    m = OUT / "ctl" / "out" / "manifest.json"
    rej = json.loads(m.read_text()).get("rejected", []) if m.exists() else []
    expect(
        "F1 side-less arrow between two boxes solves clean",
        rc == 0,
        f"rc={rc} {o.get('status')} rejected={[r.get('checks') for r in rej][:2]}",
    )
    # F12: picking the same tile twice writes the same record bytes; no timing in a record
    if rc == 0:
        run_press(
            "pick", f, OUT / "ctl" / "out", 1, OUT / "ctl" / "p1.json", OUT / "ctl" / "p1.svg"
        )
        run_press(
            "pick", f, OUT / "ctl" / "out", 1, OUT / "ctl" / "p2.json", OUT / "ctl" / "p2.svg"
        )
        a, b = (OUT / "ctl" / "p1.json").read_bytes(), (OUT / "ctl" / "p2.json").read_bytes()
        expect(
            "F12 two picks of one tile write identical records without timings",
            a == b and b"_ms" not in a and b"timing" not in a,
            f"identical={a == b} has_ms={b'_ms' in a}",
        )


def checker():
    sys.path.insert(0, "/work/press")
    from fig.check import CHECKS, Checker

    if len(figs) < 3:
        expect("checker: good figures to plant into", False, f"only {sorted(figs)}")
        return
    P = plants(figs)
    expect(
        "a plant for every check",
        sorted(P) == sorted(CHECKS),
        f"{len(P)} plants, {len(CHECKS)} checks",
    )
    expect("F8 the checker has an external-reference check", "external" in CHECKS, str(len(CHECKS)))
    with Checker() as c:
        base = {n: c.check(s, n) for n, (s, _) in figs.items()}
        for n, r in base.items():
            expect(f"base {n} scores 0", r["defects"] == 0, str(r["by"]))
        pd = OUT / "plants"
        pd.mkdir(exist_ok=True)
        for chk in CHECKS:
            if chk not in P:
                continue
            b, svg = P[chk]
            (pd / f"{chk}.svg").write_text(svg, encoding="utf-8")
            r = c.check(svg, chk)
            got, was = r["by"].get(chk, 0), base[b]["by"].get(chk, 0)
            # pinned, not relative: the base scores 0 for this check and the plant at least 1
            expect(
                f"check {chk} fires on its plant",
                was == 0 and got >= 1,
                f"base {was} -> plant {got} ({b})",
            )
        # F8: the reviewer's SVG — an @import and an <image> pointing off the machine
        net = (
            '<svg viewBox="0 0 476 100" xmlns="http://www.w3.org/2000/svg"><style>@import url(http://203.0.113.7/x.css);'
            '</style>\n<image href="http://203.0.113.7/beacon.png" x="0" y="0" width="10" height="10"/>\n'
            '<text x="20" y="50" font-size="8">hello</text></svg>'
        )
        r = c.check(net, "net")
        expect(
            "F8 an SVG that reaches outside itself is a defect",
            r["by"].get("external", 0) >= 1,
            str(r["by"]),
        )


def rebuild():
    f7, rec7 = FIGS / "repair-flow.fig.json", picks / "repair-flow.pick.json"
    shas = []
    for i in (1, 2):
        rc, o, _ = run_press("rebuild", f7, rec7, OUT / f"rebuild{i}.svg")
        expect(f"rebuild {i} exit 0", rc == 0, f"rc={rc} {o.get('status')}")
        shas.append(
            hashlib.sha256((OUT / f"rebuild{i}.svg").read_bytes()).hexdigest() if rc == 0 else None
        )
    pick_sha = hashlib.sha256((picks / "repair-flow.svg").read_bytes()).hexdigest()
    expect(
        "rebuild twice is byte-identical and equals the pick",
        shas[0] == shas[1] == pick_sha,
        f"{shas} vs {pick_sha[:12]}",
    )
    stub = OUT / "nosolver"
    for mod in ("ortools", "z3", "uharfbuzz", "playwright"):
        (stub / mod).mkdir(parents=True, exist_ok=True)
        (stub / mod / "__init__.py").write_text(
            f'raise ImportError("{mod} is deliberately absent: rebuild must not need it")\n'
        )
    senv = dict(ENV, PYTHONPATH=f"{stub}:/work/press")
    rc, o, err = run_press("rebuild", f7, rec7, OUT / "rebuild-nosolver.svg", env=senv)
    expect(
        "rebuild needs no solver, router, shaper or browser",
        rc == 0
        and hashlib.sha256((OUT / "rebuild-nosolver.svg").read_bytes()).hexdigest() == pick_sha,
        f"rc={rc} {err[-200:]}",
    )
    rc, _, _ = run_press("solve", f7, OUT / "stubcontrol", env=senv)
    expect("control: solve DOES fail without the solver (the stub bites)", rc != 0, f"rc={rc}")
    t = json.loads(f7.read_text(encoding="utf-8"))
    t["nodes"][-1]["rows"][0][1] += " (changed)"
    changed = write_fig(t, "changed")
    rc, o, _ = run_press("rebuild", changed, rec7, OUT / "rebuild-changed.svg")
    expect(
        "rebuild refuses a changed description: exit 2",
        rc == 2,
        f"rc={rc} {o.get('message', '')[:80]}",
    )
    r = json.loads(rec7.read_text())
    r["geometry"]["boxes"][0]["x"] += 1
    tampered = OUT / "repair-flow-tampered.pick.json"
    tampered.write_text(json.dumps(r))
    rc, o, _ = run_press("rebuild", f7, tampered, OUT / "rebuild-tampered.svg")
    expect(
        "rebuild refuses a tampered record: exit 3",
        rc == 3 and not (OUT / "rebuild-tampered.svg").exists(),
        f"rc={rc}",
    )
    rc, o, _ = run_press(
        "pick", changed, OUT / "repair-flow", 1, OUT / "x.pick.json", OUT / "x.svg"
    )
    expect("pick refuses a description changed since the solve: exit 2", rc == 2, f"rc={rc}")
    # F4: the reviewer's consistent tamper — texts changed, a box moved, the hash recomputed
    sys.path.insert(0, "/work/press")
    from fig import render

    r = json.loads(rec7.read_text())
    b = next(b for b in r["geometry"]["boxes"] if b["texts"])
    b["texts"][0][3] = "TAMPERED TEXT NOT IN THE DESCRIPTION"
    b["x"] += 40
    r["svg_sha256"] = hashlib.sha256(render.svg(r["geometry"]).encode()).hexdigest()
    (OUT / "consistent.pick.json").write_text(json.dumps(r))
    rc, o, err = run_press(
        "rebuild", f7, OUT / "consistent.pick.json", OUT / "rebuild-consistent.svg", env=senv
    )
    expect(
        "F4 a consistently re-hashed tamper is refused: exit 3, no solver needed",
        rc == 3 and clean(rc, err),
        f"rc={rc} {o.get('message', '')[:100]}",
    )
    r = json.loads(rec7.read_text())
    off = next(b for b in r["geometry"]["boxes"] if b["kind"] == "node")
    off["x"] = 900
    r["svg_sha256"] = hashlib.sha256(render.svg(r["geometry"]).encode()).hexdigest()
    (OUT / "offcanvas.pick.json").write_text(json.dumps(r))
    rc, o, err = run_press("rebuild", f7, OUT / "offcanvas.pick.json", OUT / "rebuild-off.svg")
    expect(
        "F4 a re-hashed record with a box off the canvas is refused: exit 3", rc == 3, f"rc={rc}"
    )
    for label, mut in (
        ("no svg_sha256", lambda r: r.pop("svg_sha256")),
        ("an unknown look", lambda r: r["geometry"]["boxes"][0].__setitem__("look", "nosuchlook")),
        ("no geometry", lambda r: r.pop("geometry")),
        ("not JSON", None),
    ):
        p = OUT / f"malformed-{label.replace(' ', '-')}.pick.json"
        if mut is None:
            p.write_text('{"id": ')
        else:
            r = json.loads(rec7.read_text())
            mut(r)
            p.write_text(json.dumps(r))
        rc, o, err = run_press("rebuild", f7, p, OUT / "rebuild-malformed.svg")
        expect(
            f"F4 a malformed record ({label}): exit 3, no traceback",
            rc == 3 and clean(rc, err),
            f"rc={rc} {err[-120:]}",
        )


def unsat():
    row = [f"m{i}" for i in range(6)]
    t = {
        "id": "unsat",
        "kind": "diagram",
        "nodes": [
            {
                "id": m,
                "look": "node",
                "rows": [["b", f"module {i} on the bus"], ["m", "a longer second line"]],
            }
            for i, m in enumerate(row)
        ],
        "constraints": [["same-y", row], ["order-x", row, 9]],
    }
    rc, o, _ = run_press("solve", write_fig(t), OUT / "unsat" / "out")
    ex = OUT / "unsat" / "out" / "explain.json"
    ex = json.loads(ex.read_text()) if ex.exists() else {}
    named = [c.get("constraint") for c in ex.get("core", [])]
    expect("unsatisfiable row: exit 4", rc == 4, f"rc={rc}")
    expect(
        "explain.json names the order-x row",
        ["order-x", row, 9] in named,
        f"core={[c.get('text') or c.get('rule') for c in ex.get('core', [])]}",
    )
    ok = copy.deepcopy(t)
    ok["id"] = "unsatfixed"
    ok["constraints"] = [["order-y", row, 4]]
    rc, o, _ = run_press("solve", write_fig(ok), OUT / "unsatfixed" / "out")
    expect("control: the same boxes as a column solve: exit 0", rc == 0, f"rc={rc}")
    # F3: one box wider than the canvas — named, before any solving, never "no answer"
    long = "a single row of text that is much too long to fit in the one hundred and sixty eight millimetre print column"
    for tid, t in (
        (
            "wide2",
            {
                "id": "wide2",
                "kind": "diagram",
                "nodes": [{"id": "w", "look": "node", "rows": [["b", f"{long} {long}"]]}],
            },
        ),
        (
            "widein",
            {
                "id": "widein",
                "kind": "diagram",
                "nodes": [
                    {"id": "w", "look": "node", "rows": [["b", long + " of this invented sample"]]}
                ],
                "groups": [
                    {"id": "g", "look": "white", "children": ["w"]},
                    {"id": "h", "look": "white", "children": ["g"]},
                ],
            },
        ),
    ):
        rc, o, err = run_press("solve", write_fig(t), OUT / tid / "out")
        ex = OUT / tid / "out" / "explain.json"
        ex = json.loads(ex.read_text()) if ex.exists() else {}
        core = ex.get("core", [{}])
        expect(
            f"F3 {tid}: a box wider than the canvas is exit 4 naming it",
            rc == 4
            and core
            and core[0].get("element") == "w"
            and core[0].get("width", 0) > core[0].get("avail", 999),
            f"rc={rc} {o.get('message', '')[:120]}",
        )
    # F2: the two encodings disagreeing is its own exit path, with its own message
    code = (
        'import sys; sys.argv=["x"]\n'
        "from fig import cli, place\n"
        "real = place.solve_top\n"
        "def fake(P, shapes, *a, **kw):\n"
        "    L = P.level(None, shapes)\n"
        '    raise place.Unsat(L, "forced")\n'
        "place.solve_top = fake\n"
        f'sys.exit(cli.main(["solve", "{FIGS / "repair-flow.fig.json"}", "{OUT / "disagree"}"]))\n'
    )
    r = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, env=ENV)
    o = json.loads(r.stdout.strip().splitlines()[-1]) if r.stdout.strip() else {}
    expect(
        "F2 encodings that disagree: exit 7 with its own message",
        r.returncode == 7 and o.get("status") == "encodings-disagree",
        f"rc={r.returncode} {o}",
    )


def budget():
    rows = [f"r{i}" for i in range(60)]
    t = {
        "id": "tall60",
        "kind": "diagram",
        "nodes": [
            {"id": r, "look": "rowteal", "rows": [["b", f"item {i}"], ["m", "what this item does"]]}
            for i, r in enumerate(rows)
        ],
        "constraints": [["order-y", rows, 4], ["same-x", rows]],
    }
    rc, o, _ = run_press("solve", write_fig(t), OUT / "tall60" / "out")
    bj = OUT / "tall60" / "out" / "budget.json"
    bj = json.loads(bj.read_text()) if bj.exists() else {}
    expect(
        'F2 60 stacked rows: exit 5 (over budget, not "unsatisfiable")',
        rc == 5,
        f"rc={rc} {o.get('message', '')[:100]}",
    )
    expect(
        "budget.json says what it needs, proposes no split",
        bj.get("needs", [0, 0])[1] > 620 and bj.get("split") is None,
        f"needs={bj.get('needs')} split={bj.get('split')}",
    )
    a, b = rows[:30], rows[30:]
    g = {
        "id": "tallgrp",
        "kind": "diagram",
        "nodes": copy.deepcopy(t["nodes"]),
        "groups": [
            {
                "id": "GA",
                "look": "white",
                "title": [["b", "first half"]],
                "children": a,
                "layout": "stack",
                "collapsible": True,
            },
            {
                "id": "GB",
                "look": "white",
                "title": [["b", "second half"]],
                "children": b,
                "layout": "stack",
                "collapsible": True,
            },
        ],
        "constraints": [["order-y", ["GA", "GB"], 4]],
    }
    rc, o, _ = run_press("solve", write_fig(g), OUT / "tallgrp" / "out", "--total-s", 120)
    bj = OUT / "tallgrp" / "out" / "budget.json"
    bj = json.loads(bj.read_text()) if bj.exists() else {}
    expect(
        "F2 two collapsible groups of 30 rows: exit 5 with a split",
        rc == 5 and bool(bj.get("split")),
        f"rc={rc} parts={[p['id'] for p in bj.get('split') or []]} {o.get('message', '')[:100]}",
    )
    # F7: the reviewer's split — edges between and within groups must all survive
    A = [f"a{i}" for i in range(14)]
    Bn = [f"b{i}" for i in range(14)]
    su = {
        "id": "su",
        "kind": "diagram",
        "nodes": [
            {"id": x, "look": "rowteal", "rows": [["b", f"item {x}"], ["m", "what this item does"]]}
            for x in A + Bn
        ],
        "groups": [
            {
                "id": "GA",
                "look": "white",
                "title": [["b", "group A"]],
                "children": A,
                "layout": "stack",
                "collapsible": True,
            },
            {
                "id": "GB",
                "look": "white",
                "title": [["b", "group B"]],
                "children": Bn,
                "layout": "stack",
                "collapsible": True,
            },
        ],
        "edges": [
            {"id": "e1", "from": "a0", "to": "b5", "kind": "arrow"},
            {"id": "e2", "from": "a3", "to": "b9", "kind": "arrow"},
            {"id": "e3", "from": "a1", "to": "a2", "kind": "arrow"},
        ],
    }
    rc, o, _ = run_press("solve", write_fig(su), OUT / "su" / "out")
    bj = OUT / "su" / "out" / "budget.json"
    bj = json.loads(bj.read_text()) if bj.exists() else {}
    seen = set()
    sys.path.insert(0, "/work/press")
    from fig import desc

    for p in bj.get("split") or []:
        _, eff = desc.load(
            str(OUT / "su" / "out" / p["file"])
        )  # the part as drawn: its view applied
        seen |= {e["id"] for e in eff["edges"]}
    expect(
        "F7 every original edge survives a split as an edge or a marked stub",
        {"e1", "e2", "e3"} <= seen,
        f"rc={rc} edges seen in parts: {sorted(x for x in seen if x)}",
    )
    expect(
        "F7 budget.json lists the edges a collapse merged or dropped",
        bool(bj.get("edges_merged") or bj.get("edges_dropped")),
        f"merged={bj.get('edges_merged')} dropped={bj.get('edges_dropped')}",
    )
    for p in bj.get("split") or []:
        rc, o, _ = run_press(
            "solve", OUT / "su" / "out" / p["file"], OUT / "su" / f"solve-{p['id']}"
        )
        expect(
            f"proposed part {p['id']} solves: exit 0",
            rc == 0,
            f"rc={rc} {o.get('message', '')[:100]}",
        )
    # the old three-level split still works, and its parts solve
    rows = [f"r{i}" for i in range(26)]
    tg = {
        "id": "tallg",
        "kind": "diagram",
        "nodes": [
            {
                "id": "head",
                "look": "wash",
                "rows": [["h", "a long list"], ["n", "one row per item"]],
            }
        ]
        + [
            {"id": r, "look": "rowteal", "rows": [["b", f"item {i}"], ["m", "what this item does"]]}
            for i, r in enumerate(rows)
        ],
        "groups": [
            {
                "id": "items",
                "look": "node",
                "title": [["t", "the items"]],
                "children": ["bank_a", "bank_b"],
                "layout": "stack",
                "collapsible": True,
            },
            {
                "id": "bank_a",
                "look": "white",
                "title": [["b", "items 0-12"]],
                "children": rows[:13],
                "layout": "stack",
                "collapsible": True,
            },
            {
                "id": "bank_b",
                "look": "white",
                "title": [["b", "items 13-25"]],
                "children": rows[13:],
                "layout": "stack",
                "collapsible": True,
            },
        ],
        "constraints": [["order-y", ["head", "items"], 4]],
    }
    rc, o, _ = run_press("solve", write_fig(tg), OUT / "tallg" / "out")
    bj = OUT / "tallg" / "out" / "budget.json"
    bj = json.loads(bj.read_text()) if bj.exists() else {}
    parts = bj.get("split") or []
    expect(
        "over budget, nested collapsible groups: exit 5 with a split",
        rc == 5 and len(parts) >= 2,
        f"rc={rc} parts={[p['id'] for p in parts]}",
    )
    expect(
        "every proposed part fits the budget",
        parts and all(p["fits"] for p in parts),
        str([(p["id"], p["needs"]) for p in parts]),
    )
    # F14: a later solve into the same directory leaves no stale split behind
    rc, o, _ = run_press("solve", FIGS / "repair-flow.fig.json", OUT / "tallg" / "out")
    expect(
        "F14 solve clears a previous split/ in its output directory",
        rc == 0 and not (OUT / "tallg" / "out" / "split").exists(),
        f"rc={rc}",
    )


def determinism():
    ids = ["p", "q", "r", "s", "t", "u"]
    free = {
        "id": "free",
        "kind": "diagram",
        "nodes": [
            {
                "id": i,
                "look": "node",
                "rows": [["b", f"stage {i}"], ["m", "does a thing to the frame"]],
            }
            for i in ids
        ],
        "edges": [
            {"from": a, "to": b, "kind": "arrow", "from-side": "s", "to-side": "n"}
            for a, b in zip(ids, ids[1:], strict=False)
        ],
    }
    for name, f in (("book-cart", FIGS / "book-cart.fig.json"), ("free", write_fig(free))):
        outs = []
        for run in (1, 2):
            o = OUT / "det" / f"{name}-{run}"
            rc, _, _ = run_press("solve", f, o)
            files = sorted(p.name for p in o.glob("cand-*")) + ["manifest.json"]
            outs.append((rc, {n: (o / n).read_bytes() for n in files if (o / n).exists()}))
        same = outs[0][1] == outs[1][1] and outs[0][0] == 0
        diff = [n for n in outs[0][1] if outs[0][1].get(n) != outs[1][1].get(n)]
        expect(
            f"F5 two solves of {name}: identical manifest and candidate bytes",
            same,
            f"rc={[x[0] for x in outs]} differ={diff[:4]}",
        )
        m = json.loads(outs[0][1].get("manifest.json", b"{}"))
        near = [r for r in m.get("rejected", []) if "near-identical" in r.get("reason", "")]
        expect(
            f"F6 {name}: no candidate the placer called different is rejected as near-identical",
            not near,
            f"{len(near)}",
        )
        if len(m.get("tiles", [])) < 8:
            expect(
                f"F6 {name}: the manifest says why the sheet is short",
                bool(m.get("short_because")),
                str(m.get("short_because")),
            )


def bad_input():
    # F9: a hostile id is refused; text with quote, angle bracket and ampersand renders escaped
    hostile = 'a" onclick="alert(document.domain)'
    inj = {
        "id": "inj",
        "kind": "diagram",
        "constraints": [["order-x", [hostile, "b"], 30]],
        "edges": [{"from": hostile, "kind": "arrow", "to": "b"}],
        "nodes": [
            {"id": hostile, "look": "node", "rows": [["b", "box a"]]},
            {"id": "b", "look": "node", "rows": [["b", "box b"]]},
        ],
    }
    rc, o, err = run_press("solve", write_fig(inj), OUT / "inj" / "out")
    expect(
        "F9 a hostile id is refused: exit 6",
        rc == 6 and clean(rc, err),
        f"rc={rc} {o.get('message', '')[:100]}",
    )
    esc = {
        "id": "esc",
        "kind": "diagram",
        "nodes": [{"id": "a", "look": "node", "rows": [["b", 'say "x" <y> & z']]}],
    }
    rc, o, _ = run_press("solve", write_fig(esc), OUT / "esc" / "out")
    svg = OUT / "esc" / "out" / "cand-01.svg"
    try:
        texts = (
            [
                t.text
                for t in ET.fromstring(svg.read_text(encoding="utf-8")).iter(
                    "{http://www.w3.org/2000/svg}text"
                )
            ]
            if svg.exists()
            else []
        )
    except ET.ParseError as e:
        texts = [f"XML parse error: {e}"]
    expect(
        'F9 a row with " < & renders escaped and parses back intact',
        'say "x" <y> & z' in texts,
        f"rc={rc} {texts}",
    )
    # F9: the renderer escapes attribute values itself, whatever reaches it
    sys.path.insert(0, "/work/press")
    from fig import render

    g = {
        "W": 476,
        "H": 40,
        "buses": [],
        "edges": [],
        "boxes": [
            {
                "id": 'a" onclick="x',
                "kind": "node",
                "look": "node",
                "x": 0,
                "y": 0,
                "w": 60,
                "h": 20,
                "texts": [[7, 12, "b", 'say "x" <y> & z', "start"]],
            }
        ],
    }
    try:
        el = ET.fromstring(render.svg(g))
        rect = next(el.iter("{http://www.w3.org/2000/svg}rect"))
        ok = rect.get("data-id") == 'a" onclick="x' and rect.get("onclick") is None
    except ET.ParseError:
        ok = False
    expect("F9 an attribute value with a quote is escaped by the renderer", ok)
    # F10: the id must be the file stem; a path-shaped id is refused
    t = {
        "id": "other",
        "kind": "diagram",
        "nodes": [{"id": "a", "look": "node", "rows": [["b", "x"]]}],
    }
    d = OUT / "stem"
    d.mkdir(exist_ok=True)
    (d / "notother.fig.json").write_bytes(canon(t))
    rc, o, err = run_press("solve", d / "notother.fig.json", d / "out")
    expect(
        "F10 an id that is not the file stem: exit 6",
        rc == 6 and "stem" in o.get("message", ""),
        f"rc={rc} {o.get('message', '')[:100]}",
    )
    trav = dict(t, id="../../escaped")
    (d / "trav.fig.json").write_bytes(canon(trav))
    rc, o, err = run_press("solve", d / "trav.fig.json", d / "tout")
    expect("F10 a path-shaped id is refused: exit 6", rc == 6 and clean(rc, err), f"rc={rc}")
    base7 = json.loads((FIGS / "repair-flow.fig.json").read_text(encoding="utf-8"))
    for label, mutate, needle in (
        ("unknown key", lambda t: t["nodes"][0].__setitem__("colour", "red"), "'colour'"),
        (
            "unknown constraint",
            lambda t: t["constraints"].append(["align-left", ["inspect", "clean"]]),
            "'align-left'",
        ),
        (
            "a float",
            lambda t: t["constraints"].append(["order-x", ["inspect", "clean"], 1.5]),
            "float",
        ),
        ("collapse is not a list", lambda t: t.__setitem__("view", {"collapse": 5}), "collapse"),
        ("an integer out of range", lambda t: t["nodes"][0].__setitem__("pad", 10**30), "range"),
    ):
        t = copy.deepcopy(base7)
        mutate(t)
        f = write_fig(t, f"inv-{label.replace(' ', '-')}")
        rc, o, err = run_press("solve", f, f.parent / "out")
        expect(
            f"F11 invalid description ({label}): exit 6 naming it, no traceback",
            rc == 6 and needle in o.get("message", "") and clean(rc, err),
            f"rc={rc} {o.get('message', '')[:120]}",
        )
    d = OUT / "inv-surrogate"
    d.mkdir(exist_ok=True)
    (d / "x.fig.json").write_text('{"id":"\\ud800","kind":"diagram"}\n')
    rc, o, err = run_press("solve", d / "x.fig.json", d / "out")
    expect(
        "F11 a lone surrogate escape: exit 6, no traceback", rc == 6 and clean(rc, err), f"rc={rc}"
    )
    d = OUT / "inv-noncanonical"
    d.mkdir(exist_ok=True)
    (d / "repair-flow.fig.json").write_text(
        json.dumps(base7, indent=1, ensure_ascii=False), encoding="utf-8"
    )
    rc, o, err = run_press("solve", d / "repair-flow.fig.json", d / "out")
    expect(
        "invalid description (non-canonical bytes): exit 6",
        rc == 6 and "canonical" in o.get("message", ""),
        f"rc={rc}",
    )
    rc, o, err = run_press(
        "pick",
        FIGS / "repair-flow.fig.json",
        OUT / "repair-flow",
        99,
        OUT / "nt.json",
        OUT / "nt.svg",
    )
    expect("F11 a nonexistent tile: exit 6, no traceback", rc == 6 and clean(rc, err), f"rc={rc}")
    (OUT / "empty").mkdir(exist_ok=True)
    rc, o, err = run_press(
        "pick", FIGS / "repair-flow.fig.json", OUT / "empty", 1, OUT / "nm.json", OUT / "nm.svg"
    )
    expect(
        "F11 pick with no manifest: exit 6, no traceback",
        rc == 6 and clean(rc, err),
        f"rc={rc} {err[-100:]}",
    )
    rc, o, err = run_press("solve", OUT / "nosuch.fig.json", OUT / "nosuch")
    expect("F11 a missing input file: exit 6, no traceback", rc == 6 and clean(rc, err), f"rc={rc}")
    rc, o, err = run_press("solve", FIGS / "repair-flow.fig.json", OUT / "badarg", "--n", "eight")
    expect(
        "F11 a bad argument: exit 6 with one JSON line",
        rc == 6 and o.get("message"),
        f"rc={rc} {err[-100:]}",
    )


def router():
    # the suspected room() defect: a one-bend route whose last leg is short must keep its start
    g = {
        "W": 476,
        "H": 80,
        "buses": [],
        "boxes": [
            {
                "id": "a",
                "kind": "node",
                "look": "node",
                "x": 0,
                "y": 0,
                "w": 40,
                "h": 20,
                "texts": [],
            },
            {
                "id": "b",
                "kind": "node",
                "look": "node",
                "x": 80,
                "y": 15,
                "w": 40,
                "h": 20,
                "texts": [],
            },
        ],
        "edges": [
            {
                "id": "ab",
                "from": "a",
                "to": "b",
                "from-side": "e",
                "to-side": "n",
                "look": "solid",
                "arrow": "end",
                "bundle": None,
            }
        ],
    }
    r = subprocess.run(
        ["node", "/work/press/fig/route.mjs"],
        input=json.dumps({"figures": [g]}),
        capture_output=True,
        text=True,
    )
    pts = json.loads(r.stdout)["figures"][0]["edges"][0]["points"] if r.returncode == 0 else []
    expect(
        "router: a route starts exactly where it leaves its box",
        bool(pts) and pts[0] == [40, 10],
        f"points={pts}",
    )


def limits():
    # a deep group tree under a tiny total limit fails BY NAME, without a traceback
    nodes, groups, parent = [], [], None
    for k in range(6):
        gid = f"g{k}"
        kids = [f"n{k}a", f"n{k}b"]
        nodes += [{"id": x, "look": "node", "rows": [["b", f"box {x}"]]} for x in kids]
        groups.append(
            {
                "id": gid,
                "look": "white",
                "title": [["b", f"level {k}"]],
                "children": kids,
                "layout": "free",
            }
        )
        if parent:
            next(g for g in groups if g["id"] == parent)["children"].append(gid)
        parent = gid
    t = {"id": "deep", "kind": "diagram", "nodes": nodes, "groups": groups}
    rc, o, err = run_press("solve", write_fig(t), OUT / "deep" / "out", "--total-s", "0.0001")
    expect(
        "limits: a deep tree under a tiny total limit fails by name",
        rc == 1 and o.get("status") == "time-limit" and clean(rc, err),
        f"rc={rc} {o}",
    )


def desc_hardening():
    """No description, however malformed, reaches the user as a press defect (exit 7): every refusal is
    exit 6 naming the rule. Plants: the whole-string id rule (a trailing newline is not an id), a
    view that collapses one group twice (it crashed with KeyError), list-valued view entries, and
    a mutation sweep over every reference description (each leaf replaced by null, a bool, an int,
    a string, a list, a nested list and an object -- each must be accepted or refused, never thrown)."""
    sys.path.insert(0, "/work/press")
    from fig import desc

    base = {
        "id": "hv",
        "kind": "diagram",
        "edges": [{"from": "a", "kind": "arrow", "to": "c"}],
        "groups": [{"children": ["a", "b"], "collapsible": True, "id": "g", "look": "white"}],
        "nodes": [{"id": x, "look": "node", "rows": [["b", x]]} for x in ("a", "b", "c")],
    }
    cases = [
        (
            "an id with a trailing newline",
            lambda t: t["nodes"][0].__setitem__("id", "a\n"),
            "not an id",
        ),
        (
            "a view collapsing one group twice",
            lambda t: t.__setitem__("view", {"collapse": ["g", "g"], "refs": {"g": "F9"}}),
            "more than once",
        ),
        (
            "a view collapse entry that is a list",
            lambda t: t.__setitem__("view", {"collapse": [["g"]], "refs": {"g": "F9"}}),
            "not a group id",
        ),
        (
            "a view detail that is a list",
            lambda t: t.__setitem__("view", {"detail": ["g"], "refs": {"g": "F9"}}),
            "not a group",
        ),
        (
            "an edge end that is a list",
            lambda t: t["edges"][0].__setitem__("from", ["a"]),
            "not a node",
        ),
    ]
    for label, mutate, needle in cases:
        t = copy.deepcopy(base)
        mutate(t)
        f = write_fig(t, f"hard-{label.replace(' ', '-')}")
        rc, o, err = run_press("solve", f, f.parent / "out")
        expect(
            f"hardening: {label} -> exit 6 naming it",
            rc == 6 and needle in o.get("message", "") and clean(rc, err),
            f"rc={rc} {o.get('message', '')[:110]}",
        )
    subs = [None, True, 0, -1, 7, "zz", "", [], ["zz"], [["zz"]], {}, {"zz": 1}]

    def paths(x, p=()):
        yield p
        if isinstance(x, dict):
            for k, v in x.items():
                yield from paths(v, p + (k,))
        elif isinstance(x, list):
            for i, v in enumerate(x):
                yield from paths(v, p + (i,))

    n, thrown = 0, []
    for f in sorted(FIGS.glob("*.fig.json")):
        tree = json.loads(f.read_text(encoding="utf-8"))
        for p in [p for p in paths(tree) if p]:
            for v in subs:
                t = copy.deepcopy(tree)
                at = t
                for k in p[:-1]:
                    at = at[k]
                at[p[-1]] = v
                n += 1
                try:
                    desc.validate(t)
                except desc.DescError:
                    pass
                except Exception as e:  # noqa: BLE001 - any other exception is the defect being hunted
                    thrown.append(f"{f.name} {p} = {v!r}: {type(e).__name__}: {e}")
    expect(
        "hardening: a mutation sweep over the reference descriptions never throws past the refusal",
        n > 1000 and not thrown,
        f"{n} mutations, {len(thrown)} thrown: {thrown[:3]}",
    )


def checker_degenerate():
    """The checker on paths that draw no line -- no d, an empty d, a lone moveto, in an SVG whose
    edges carry no data-net (it crashed: JS parse(null), then an index into an empty point list).
    They are not ink and score nothing; the control is a real line through a text in the same SVG,
    which must still fire edge-in-text."""
    d = OUT / "degenerate"
    d.mkdir(exist_ok=True)
    head = '<svg viewBox="0 0 476 100" xmlns="http://www.w3.org/2000/svg">'
    junk = (
        '<path stroke="#000" fill="none"/><path d="" stroke="#000" fill="none"/>'
        '<path d="M 10 10" stroke="#000" fill="none"/><line x1="10" y1="50" x2="200" y2="50" stroke="#000"/>'
    )
    (d / "empty.svg").write_text(
        head + junk + '<text x="20" y="80" font-size="8">hello</text></svg>\n'
    )
    (d / "cross.svg").write_text(
        head + junk + '<text x="20" y="80" font-size="8">hello</text>'
        '<line x1="5" y1="77" x2="300" y2="77" stroke="#000"/></svg>\n'
    )
    rc, o, err = run_press("check", d / "empty.svg")
    expect(
        "checker: paths with no points score nothing and never crash",
        rc == 0 and o.get("defects") == 0 and clean(rc, err),
        f"rc={rc} {o.get('by') or o.get('message', '')[:120]}",
    )
    rc, o, err = run_press("check", d / "cross.svg")
    expect(
        "checker: ...while a real line through a text beside them still fires",
        rc == 1 and "edge-in-text" in o.get("by", {}),
        f"rc={rc} {o.get('by')}",
    )


for s in (
    good,
    checker,
    rebuild,
    unsat,
    budget,
    determinism,
    bad_input,
    desc_hardening,
    checker_degenerate,
    router,
    limits,
):
    section(s)
print(f"fig selftest: {len(passes)} passed, {len(fails)} failed" + (f": {fails}" if fails else ""))
sys.exit(1 if fails else 0)
