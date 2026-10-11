"""`press chart`'s both-directions proof (`press selftest` runs it inside the image, no network; the
launcher half is chart_launcher_selftest.sh).
usage: chart_selftest.py <out-dir>   (the tool tree at /work)

  good         the three reference charts (fixtures/charts: loans, volunteer hours, a lending exchange)
               draw with exit 0 and 0 checker defects, write an SVG and a pick record whose
               svg_sha256 is the SVG's, keep the house margin after every text, match their pinned
               golden SVG hashes, and a second run writes identical bytes
  rebuild      `press fig rebuild` redraws each chart byte-identically from its record; a changed
               description -> 2; a tampered hash, a wrong renderer, a missing or an extra key -> 3
  unfit        a planted over-wide bar label, a segment label wider than its segment, a word wider
               than the canvas and colliding boxes -> 4 naming what it needs, and NOTHING written;
               too many rows -> 5
  contrast     a value label moved onto its own bar fails the checker's text-low-contrast check
  sweep        every mutation of the three reference charts is drawn or refused, never thrown
  input        unknown keys, floats, an integer out of range,
               a decimal that is not one, a trailing-newline id, a colour that is not a house colour,
               a message tone with no arrowhead, a chart handed to `press fig solve` -> 6, no traceback"""

import copy
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import traceback

OUT = pathlib.Path(sys.argv[1])
MARGIN = 6  # house.json `pad`: the free space a text keeps from the canvas edge
# the sha256 of each reference chart's SVG, pinned by hand after inspecting the invented reference render; a rendering change turns this red
# until a person has looked again and re-pinned it
GOLDEN = {
    "lending-timeline": "49f8bffb737992acf3f71ad05b25093ddf062815a0fb69c9767e98927819e73d",
    "workshop-hours": "b5582c16d91b20df2cb45523d1d0cab5605f67ff3bfad109c2a149f29c36f0bb",
    "branch-visits": "4843741998b521dd835a77f70d539fc13d6ee0070a81262d492fd701d651a714",
}
REFS = pathlib.Path("/work/fixtures/charts")
ENV = dict(os.environ, PYTHONPATH="/work/press")
fails, passes = [], []


def expect(name, ok, detail=""):
    (passes if ok else fails).append(name)
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}", flush=True)


def section(fn):
    try:
        fn()
    except Exception as e:  # noqa: BLE001 - the harness reports, it does not judge
        expect(
            f"section {fn.__name__} ran to the end",
            False,
            f"{type(e).__name__}: {e}\n{traceback.format_exc()[-600:]}",
        )


def canon(t):
    return (
        json.dumps(t, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n"
    ).encode()


def run(mod, *args):
    r = subprocess.run(
        [sys.executable, "-m", mod, *map(str, args)], capture_output=True, text=True, env=ENV
    )
    lines = r.stdout.strip().splitlines()
    try:
        out = json.loads(lines[-1]) if lines else {}
    except json.JSONDecodeError:
        out = {"raw": r.stdout[-300:]}
    return r.returncode, out, r.stderr, len(lines)


def chart(fig, pick, svg):
    return run("chart.cli", fig, pick, svg)


def write(t, sub):
    d = OUT / sub
    d.mkdir(parents=True, exist_ok=True)
    f = d / f"{t['id']}.fig.json"
    f.write_bytes(canon(t))
    return f


drawn = {}


def good():
    for f in sorted(REFS.glob("*.fig.json")):
        name = f.name.split(".")[0]
        d = OUT / "good"
        d.mkdir(parents=True, exist_ok=True)
        rc, o, err, n = chart(f, d / f"{name}.pick.json", d / f"{name}.svg")
        svg = (d / f"{name}.svg").read_bytes() if (d / f"{name}.svg").exists() else b""
        rec = (
            json.loads((d / f"{name}.pick.json").read_text())
            if (d / f"{name}.pick.json").exists()
            else {}
        )
        expect(
            f"{name}: draws with exit 0, one JSON line, no traceback",
            rc == 0 and n == 1 and "Traceback" not in err,
            f"rc={rc} {o.get('status')} {o.get('message', '')[:120]} {o.get('by', '')}",
        )
        expect(
            f"{name}: the pick record is {{id, problem_hash, svg_sha256, renderer: chart}} and names the SVG bytes",
            sorted(rec) == ["id", "problem_hash", "renderer", "svg_sha256"]
            and rec.get("renderer") == "chart"
            and rec.get("svg_sha256") == hashlib.sha256(svg).hexdigest()
            and rec.get("problem_hash") == hashlib.sha256(f.read_bytes()).hexdigest(),
            str(rec)[:160],
        )
        rep = OUT / "good" / f"{name}.check.json"
        rc, o, err, _ = run("fig.cli", "check", d / f"{name}.svg", "--json", rep)
        expect(
            f"{name}: the figure checker finds 0 defects in it",
            rc == 0 and o.get("defects") == 0,
            str(o.get("by")),
        )
        right = json.loads(rep.read_text()).get("text_right_u") if rep.exists() else None
        expect(
            f"{name}: every text ends at least the house margin ({MARGIN} u) from the canvas edge",
            right is not None and right <= 476 - MARGIN,
            f"right-most text ends at {right} u",
        )
        expect(
            f"{name}: the SVG is the pinned golden one (visually reviewed reference)",
            hashlib.sha256(svg).hexdigest() == GOLDEN[name],
            f"{hashlib.sha256(svg).hexdigest()}",
        )
        d2 = OUT / "again"
        d2.mkdir(exist_ok=True)
        chart(f, d2 / f"{name}.pick.json", d2 / f"{name}.svg")
        same = (d2 / f"{name}.svg").read_bytes() == svg and (
            d2 / f"{name}.pick.json"
        ).read_bytes() == (d / f"{name}.pick.json").read_bytes()
        expect(f"{name}: a second run writes identical bytes", same)
        drawn[name] = (f, d / f"{name}.pick.json", svg)


def rebuild():
    for name, (f, pick, svg) in sorted(drawn.items()):
        out = OUT / "rebuild" / f"{name}.svg"
        out.parent.mkdir(parents=True, exist_ok=True)
        rc, o, err, _ = run("fig.cli", "rebuild", f, pick, out)
        expect(
            f"rebuild {name}: byte-identical, exit 0",
            rc == 0 and out.exists() and out.read_bytes() == svg,
            f"rc={rc} {o}",
        )
    f, pick, svg = drawn["branch-visits"]
    t = json.loads(f.read_text())
    t["data"]["rows"][0]["value"] = 20
    changed = write(t, "rebuild-changed")
    rc, o, err, _ = run("fig.cli", "rebuild", changed, pick, OUT / "rebuild" / "x.svg")
    expect(
        "rebuild: a changed description -> 2",
        rc == 2 and o.get("status") == "stale",
        f"rc={rc} {o}",
    )
    rec = json.loads(pick.read_text())
    for label, mut in (
        ("a tampered svg_sha256", lambda r: r.__setitem__("svg_sha256", "0" * 64)),
        ("a wrong renderer", lambda r: r.__setitem__("renderer", "cp-sat")),
        ("a missing key", lambda r: r.pop("renderer")),
        ("an extra key", lambda r: r.__setitem__("geometry", {})),
        ("a hash that is not one", lambda r: r.__setitem__("svg_sha256", "abc")),
    ):
        r2 = copy.deepcopy(rec)
        mut(r2)
        p = OUT / "rebuild" / f"bad-{label.replace(' ', '-')}.json"
        p.write_text(json.dumps(r2))
        rc, o, err, _ = run("fig.cli", "rebuild", f, p, OUT / "rebuild" / "y.svg")
        expect(
            f"rebuild: {label} -> 3, no traceback",
            rc == 3 and "Traceback" not in err,
            f"rc={rc} {o.get('message', '')[:100]}",
        )


def unfit():
    base6 = json.loads((REFS / "branch-visits.fig.json").read_text())
    base5 = json.loads((REFS / "workshop-hours.fig.json").read_text())
    base3 = json.loads((REFS / "lending-timeline.fig.json").read_text())
    cases = []
    t = copy.deepcopy(base6)
    t["id"] = "widelabel"
    t["data"]["rows"][2]["label"] = (
        "a volunteer searches every shelf in the library for the requested atlas and "
        "carries it to the welcome desk before opening time"
    )
    cases.append(("a planted over-wide bar label", t, 4, "leave"))
    t = copy.deepcopy(base5)
    t["id"] = "seglabel"
    t["data"]["bars"][0]["segments"][1]["label"] = (
        "shelving every donated and returned book in alphabetical order before opening time"
    )
    cases.append(("a segment label wider than its segment", t, 4, "segment"))
    t = copy.deepcopy(base6)
    t["id"] = "longword"
    t["data"]["notes"] = ["x" * 160]
    cases.append(("a word wider than the canvas", t, 4, "word"))
    t = copy.deepcopy(base3)
    t["id"] = "collide"
    t["data"]["steps"][4]["items"][1]["title"] = [
        "the welcome desk checks every paper slip before handing over the book"
    ]
    cases.append(("colliding boxes on one row", t, 4, "overlap"))
    t = copy.deepcopy(base3)
    t["id"] = "widebox"
    t["data"]["steps"][4]["items"] = [
        {
            "at": "desk",
            "title": [
                "the volunteer reads every title aloud, compares every handwritten reservation slip, "
                "and searches every shelf before returning to the reader with the requested book"
            ],
        }
    ]
    cases.append(("a timeline box wider than the canvas", t, 4, "canvas holds"))
    t = copy.deepcopy(base6)
    t["id"] = "tall"
    t["data"]["rows"] = [dict(base6["data"]["rows"][0], label=f"row {i}") for i in range(40)]
    cases.append(("forty bar rows", t, 5, "page budget"))
    for label, t, code, needle in cases:
        f = write(t, f"unfit-{t['id']}")
        pick, svg = f.parent / "p.json", f.parent / "o.svg"
        rc, o, err, _ = chart(f, pick, svg)
        expect(
            f"unfit: {label} -> {code} naming what it needs, nothing written",
            rc == code
            and needle in o.get("message", "")
            and "needs" in o
            and not pick.exists()
            and not svg.exists()
            and "Traceback" not in err,
            f"rc={rc} {o.get('message', '')[:140]} needs={o.get('needs')}",
        )


def bad_input():
    base6 = json.loads((REFS / "branch-visits.fig.json").read_text())
    base5 = json.loads((REFS / "workshop-hours.fig.json").read_text())
    base3 = json.loads((REFS / "lending-timeline.fig.json").read_text())
    cases = [
        (
            "an unknown data key",
            base6,
            lambda t: t["data"].__setitem__("colour", "red"),
            "'colour'",
        ),
        (
            "an unknown row key",
            base6,
            lambda t: t["data"]["rows"][0].__setitem__("size", 3),
            "'size'",
        ),
        ("a float value", base6, lambda t: t["data"]["rows"][0].__setitem__("value", 1.5), "float"),
        (
            "a volunteer-hours total over the integer limit",
            base5,
            lambda t: t["data"]["bars"][0].__setitem__("total", 524288),
            "range",
        ),
        (
            "a decimal string out of range",
            base5,
            lambda t: t["data"]["bars"][0].__setitem__("total", "524288.5"),
            "range",
        ),
        (
            "a decimal string that is not a decimal",
            base6,
            lambda t: t["data"]["rows"][0].__setitem__("value", "1e5"),
            "decimal",
        ),
        (
            "a decimal with a trailing newline",
            base6,
            lambda t: t["data"]["rows"][0].__setitem__("value", "19.5\n"),
            "decimal",
        ),
        (
            "a negative value",
            base6,
            lambda t: t["data"]["rows"][0].__setitem__("value", -1),
            ">= 0",
        ),
        (
            "a lifeline id with a trailing newline",
            base3,
            lambda t: t["data"]["lifelines"][0].__setitem__("id", "reader\n"),
            "not an id",
        ),
        (
            "a colour that is not a house colour",
            base6,
            lambda t: t["data"]["rows"][0].__setitem__("tone", "PINK"),
            "PINK",
        ),
        (
            "a message tone with no arrowhead",
            base3,
            lambda t: t["data"]["steps"][0].__setitem__("tone", "GREEN"),
            "GREEN",
        ),
        (
            "a message to its own lifeline",
            base3,
            lambda t: t["data"]["steps"][0].__setitem__("to", "reader"),
            "itself",
        ),
        (
            "segments over their total",
            base5,
            lambda t: t["data"]["bars"][0].__setitem__("total", 1),
            "more than the total",
        ),
        (
            "a legend naming another bar's segment",
            base5,
            lambda t: t["data"]["bars"][0]["legend"][0].__setitem__("of", "story"),
            "not a segment of this bar",
        ),
        ("an unknown chart kind", base6, lambda t: t.__setitem__("chart", "pie"), "'pie'"),
        ("a step with no kind", base3, lambda t: t["data"]["steps"][0].pop("step"), "step"),
    ]
    for label, base, mutate, needle in cases:
        t = copy.deepcopy(base)
        mutate(t)
        f = write(t, f"inv-{label.replace(' ', '-').replace(chr(39), '')}")
        rc, o, err, _ = chart(f, f.parent / "p.json", f.parent / "o.svg")
        expect(
            f"input: {label} -> 6 naming it, no traceback",
            rc == 6 and needle in o.get("message", "") and "Traceback" not in err,
            f"rc={rc} {o.get('message', '')[:140]}",
        )
    rc, o, err, _ = run("fig.cli", "solve", REFS / "branch-visits.fig.json", OUT / "solve-chart")
    expect(
        "input: a chart handed to press fig solve -> 6 pointing at press chart",
        rc == 6 and "press chart" in o.get("message", ""),
        f"rc={rc} {o.get('message', '')[:120]}",
    )
    rc, o, err, _ = chart(OUT / "nosuch.fig.json", OUT / "p.json", OUT / "o.svg")
    expect("input: a missing description -> 6", rc == 6 and "Traceback" not in err, f"rc={rc}")


def contrast():
    """The reviewer's plant: a value label moved onto its own (dark) bar. The figure checker's
    text-low-contrast check must fire on it (INK on NAVY is about 1.4:1), and not on the chart as drawn."""
    import re

    _, _, svg = drawn["branch-visits"]
    text = svg.decode()
    m = re.search(
        r'<rect class="bar" data-id="[^"]+" x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="12" rx="1.5" '
        r'fill="#1f3a5f"',
        text,
    )
    t = re.search(
        r'<text x="[\d.]+" y="([\d.]+)" font-size="7.5" font-weight="700" fill="#16202a" '
        r'style="white-space:pre">32</text>',
        text,
    )
    ok = bool(m and t)
    if ok:
        moved = t.group(0).replace(t.group(0).split('"')[1], f"{float(m.group(1)) + 10:.2f}", 1)
        planted = text.replace(t.group(0), moved)
        p = OUT / "onbar.svg"
        p.write_text(planted)
        rc, o, err, _ = run("fig.cli", "check", p)
        ok = rc == 1 and "text-low-contrast" in o.get("by", {})
    expect(
        "contrast: a value label moved onto its dark bar is a text-low-contrast defect",
        ok,
        f"{o.get('by') if m and t else 'plant not built'}",
    )


def sweep():
    """Every mutation of the three reference charts -- each leaf replaced by null, a bool, an int, a
    string, a decimal string, a list, a nested list and an object -- is drawn, refused as invalid (6)
    or refused as unfit (4/5), and NEVER throws (7). In-process: desc.validate, then render."""
    sys.path.insert(0, "/work/press")
    from fig.desc import DescError
    from chart import desc, render

    subs = [
        None,
        True,
        0,
        -1,
        7,
        100001,
        "zz",
        "",
        "19.5",
        "1e5",
        [],
        ["zz"],
        [["zz"]],
        {},
        {"zz": 1},
    ]

    def paths(x, p=()):
        yield p
        if isinstance(x, dict):
            for k, v in x.items():
                yield from paths(v, p + (k,))
        elif isinstance(x, list):
            for i, v in enumerate(x):
                yield from paths(v, p + (i,))

    n, thrown = 0, []
    for f in sorted(REFS.glob("*.fig.json")):
        tree = json.loads(f.read_text())
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
                    render.render(t)
                except (DescError, render.Unfit):
                    pass
                except Exception as e:  # noqa: BLE001 - any other exception is the defect being hunted
                    thrown.append(f"{f.name} {p} = {v!r}: {type(e).__name__}: {e}")
    expect(
        "sweep: every mutation of the reference charts is drawn or refused, never thrown",
        n > 1000 and not thrown,
        f"{n} mutations, {len(thrown)} thrown: {thrown[:3]}",
    )


for s in (good, rebuild, unfit, bad_input, contrast, sweep):
    section(s)
print(
    f"chart selftest: {len(passes)} passed, {len(fails)} failed" + (f": {fails}" if fails else "")
)
sys.exit(1 if fails else 0)
