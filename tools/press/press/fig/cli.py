"""`press fig solve | pick | rebuild | check | shoot` — the figure commands of the browser half.

Each prints ONE JSON object on stdout (a refusal too: {"status", "message"}); progress goes to
stderr; no input, however malformed, reaches the user as a traceback. Exit codes, one table for
every command (the README carries the same table, bin/press follows it):
  0  done
  1  no candidate passed every hard check, or the solve ran out of time (`status` says which);
     `check` / `pick`: the checker found defects
  2  `pick` / `rebuild`: the description changed since the solve or the pick (problem_hash differs)
  3  `rebuild`: the pick record does not match — its hash, its ids, texts or looks differ from the
     description, a box lies off the canvas, or the record is malformed
  4  `solve`: unsatisfiable (explain.json names the rules), including a box wider than the canvas
  5  `solve`: over the page budget, always (budget.json: what it needs, and a split proposal)
  6  invalid input: the description, an argument, a missing file, a nonexistent tile
  7  a defect in press itself (the two solver encodings disagree, an invalid model, the placer and
     its filter disagree) — never the description's fault
Imports are lazy on purpose: `rebuild` of a diagram loads only the description reader, the record
check and the renderer, so it runs with no solver, no router, no text shaper and no browser present.
`rebuild` of a CHART (kind "chart", drawn by `press chart`) re-renders the description -- its record
holds no geometry, only the two hashes -- so it needs the text shaper, still no solver or browser."""

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import sys
import time


class Refusal(Exception):
    def __init__(self, code, status, message, **extra):
        super().__init__(message)
        self.code, self.out = code, dict(status=status, message=message, **extra)


def _host(x):
    """Container paths (/m<k>/...) back to the host paths the caller gave (PRESS_PATHMAP from bin/press)."""
    if isinstance(x, dict):
        return {k: _host(v) for k, v in x.items()}
    if isinstance(x, list):
        return [_host(v) for v in x]
    if isinstance(x, str):
        for pair in filter(None, os.environ.get("PRESS_PATHMAP", "").split("\n")):
            c, h = pair.split("=", 1)
            if x == c or x.startswith(c + "/"):
                return h + x[len(c) :]
    return x


def emit(obj, code):
    print(json.dumps(_host(obj), ensure_ascii=False))
    return code


def log(*a):
    print("press fig:", *a, file=sys.stderr, flush=True)


def _load(path):
    from .desc import DescError, load

    try:
        return load(path)
    except DescError as e:
        raise Refusal(6, "invalid-description", str(e), file=path) from e


def _read_json(path, what, code):
    try:
        return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise Refusal(6, "missing-file", f"{path}: no such {what}") from None
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
        raise Refusal(
            code, f"malformed-{what.replace(' ', '-')}", f"{path}: not a readable {what} ({e})"
        ) from e


def _write_json(path, obj):
    pathlib.Path(path).write_text(
        json.dumps(obj, indent=1, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8"
    )


def solve(a):
    from . import budget, measure, place
    from .model import LevelError

    t0 = time.time()
    phash, d = _load(a.fig)
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for old in list(out.glob("cand-*")) + [
        out / n for n in ("sheet.png", "sheet.html", "manifest.json", "explain.json", "budget.json")
    ]:
        if old.exists():
            old.unlink()
    if (out / "split").is_dir():
        shutil.rmtree(out / "split")
    raw_tree = json.loads(pathlib.Path(a.fig).read_text(encoding="utf-8"))
    clock = place.Clock(a.total_s, a.cap_s)
    try:
        sizes = measure.size_all(d)
        P = place.Problem(d, sizes)
        log(
            f"{d['id']}: {len(d['nodes'])} nodes, {len(d['groups'])} groups, {len(d['buses'])} buses; placing groups"
        )
        shapes = place.solve_groups(P, clock)
        log(
            f"group shapes: { ({g: len(s) for g, s in shapes.items()}) }; placing the figure, up to {a.n} candidates"
        )
        state = _Candidates(P, out, d, shapes)
        try:
            L, kept, why = place.solve_top(P, shapes, a.n, clock, state.accept)
        finally:
            state.close()
    except LevelError as e:
        raise Refusal(6, "invalid-description", str(e), file=a.fig) from e
    except place.TooWide as e:
        ex = {
            "level": e.level,
            "status": "unsat",
            "problem_hash": phash,
            "core": [
                {
                    "rule": "canvas-width",
                    "element": e.element,
                    "width": e.width,
                    "avail": e.avail,
                    "level": e.level,
                }
            ],
            "message": f"{e.element!r} is {e.width} units wide at its narrowest; {e.level} is {e.avail} units wide. "
            "Shorten its text or split it into rows; text is never shrunk.",
        }
        _write_json(out / "explain.json", ex)
        raise Refusal(
            4, "unsatisfiable", ex["message"], problem_hash=phash, explain=str(out / "explain.json")
        ) from e
    except place.Unsat as e:
        from . import explain

        ex = explain.explain(e.level)
        ex["problem_hash"] = phash
        if ex["status"] == "sat":
            raise Refusal(7, "encodings-disagree", ex["message"], problem_hash=phash) from e
        _write_json(out / "explain.json", ex)
        raise Refusal(
            4,
            "unsatisfiable",
            ex.get("message"),
            problem_hash=phash,
            explain=str(out / "explain.json"),
        ) from e
    except place.OverBudget as e:
        rep = budget.report(raw_tree, d, e.needs_h, a.cap_s, a.total_s, out)
        rep["problem_hash"] = phash
        _write_json(out / "budget.json", rep)
        raise Refusal(
            5, "over-budget", rep["message"], problem_hash=phash, budget=str(out / "budget.json")
        ) from e
    except place.TimeLimit as e:
        raise Refusal(1, "time-limit", str(e), problem_hash=phash) from e
    except place.NoAnswer as e:
        raise Refusal(1, "no-answer", str(e), problem_hash=phash) from e
    except place.Internal as e:
        raise Refusal(7, "internal", str(e), problem_hash=phash) from e
    tiles, rejected = state.tiles, state.rejected
    short = None
    if len(tiles) < a.n:
        short = dict(why, kept=len(tiles), wanted=a.n)
    man = {
        "id": d["id"],
        "problem_hash": phash,
        "solver": f"or-tools cp-sat 9.12.4544, 1 worker, seed {place.SEED}, deterministic limits",
        "n": a.n,
        "cap_s": a.cap_s,
        "total_s": a.total_s,
        "tiles": tiles,
        "rejected": rejected,
        "short_because": short,
        "view_dropped": d.get("_view_dropped", []),
        "view_edges": d.get("_view_edges"),
    }
    if tiles:
        from . import sheet

        sheet.build(
            out,
            f"{d['id']} — {len(tiles)} candidates (problem {phash[:12]})",
            [
                (
                    t["tile"],
                    (out / t["svg"]).read_text(encoding="utf-8"),
                    f"cand {t['candidate']} · {t['height_u']:.0f} u tall · score {t['objective']}",
                )
                for t in tiles
            ],
        )
    _write_json(out / "manifest.json", man)
    return emit(
        {
            "status": "ok" if tiles else "no-candidate-passed",
            "problem_hash": phash,
            "tiles": len(tiles),
            "rejected": len(rejected),
            "short_because": short,
            "sheet": str(out / "sheet.png") if tiles else None,
            "manifest": str(out / "manifest.json"),
            "seconds": round(time.time() - t0, 1),
        },
        0 if tiles else 1,
    )


class _Candidates:
    """Draw, route and check each candidate the moment the placer finds it, so only candidates
    that pass the checker constrain the next round."""

    def __init__(self, P, out, d, shapes):
        from .check import Checker

        self.P, self.out, self.d, self.shapes = P, out, d, shapes
        self.tiles, self.rejected, self.kept = [], [], []
        self.n = 0
        self.chk = Checker().__enter__()

    def close(self):
        self.chk.__exit__(None, None, None)

    def accept(self, sol):
        import subprocess
        from . import place, render

        self.n += 1
        L = self.P.level(None, self.shapes)
        g = place.resolve(self.P, L, sol, self.shapes)
        here = pathlib.Path(__file__).resolve().parent
        r = subprocess.run(
            ["node", str(here / "route.mjs")],
            input=json.dumps({"figures": [g]}),
            capture_output=True,
            text=True,
        )
        if r.returncode != 0:
            raise place.Internal(f"the router failed: {r.stderr[-400:]}")
        g["edges"] = json.loads(r.stdout)["figures"][0]["edges"]
        g.pop("corridors", None)
        name = f"cand-{self.n:02d}"
        text = render.svg(g)
        (self.out / f"{name}.svg").write_text(text, encoding="utf-8")
        _write_json(self.out / f"{name}.geom.json", g)
        rep = self.chk.check(text, name)
        moved = min((place.visible_share(sol["leaves"], k) for k in self.kept), default=None)
        info = {
            "candidate": self.n,
            "objective": sol["objective"],
            "height_u": rep["height_u"],
            "checks": rep["by"],
            "min_pt": rep["min_pt"],
            "moved_vs_kept": None if moved is None else round(moved, 3),
        }
        if rep["defects"]:
            self.rejected.append(dict(info, reason="checker", detail=rep["detail"]))
            return False
        self.kept.append(sol["leaves"])
        self.tiles.append(
            dict(info, tile=len(self.tiles) + 1, svg=f"{name}.svg", geom=f"{name}.geom.json")
        )
        return True


def pick(a):
    from . import render
    from .check import Checker

    phash, d = _load(a.fig)
    out = pathlib.Path(a.out)
    man = _read_json(out / "manifest.json", "manifest", 6)
    if man.get("problem_hash") != phash:
        raise Refusal(
            2,
            "stale",
            f"the description changed since the solve ({str(man.get('problem_hash'))[:12]} -> "
            f"{phash[:12]}); solve again",
        )
    t = next((t for t in man.get("tiles", []) if t.get("tile") == a.tile), None)
    if t is None:
        raise Refusal(
            6,
            "no-such-tile",
            f"tile {a.tile} is not on the sheet (tiles "
            f"{[x.get('tile') for x in man.get('tiles', [])]})",
        )
    g = _read_json(out / t["geom"], "candidate geometry", 6)
    text = render.svg(g)
    if text != (out / t["svg"]).read_text(encoding="utf-8"):
        raise Refusal(
            7,
            "render-drift",
            f"{t['svg']} does not re-render from {t['geom']}; the renderer is not "
            "deterministic (a press defect)",
        )
    with Checker() as chk:
        rep = chk.check(text, t["svg"])
    if rep["defects"]:
        return emit({"status": "defects", "by": rep["by"], "detail": rep["detail"]}, 1)
    sha = hashlib.sha256(text.encode("utf-8")).hexdigest()
    rec = {
        "id": d["id"],
        "problem_hash": phash,
        "svg_sha256": sha,
        "solver": man["solver"],
        "tile": a.tile,
        "candidate": t["candidate"],
        "geometry": g,
    }
    _write_json(a.pick, rec)
    pathlib.Path(a.svg).write_text(text, encoding="utf-8")
    return emit(
        {
            "status": "picked",
            "tile": a.tile,
            "candidate": t["candidate"],
            "svg_sha256": sha,
            "pick": a.pick,
            "svg": a.svg,
            "height_u": rep["height_u"],
            "min_pt": rep["min_pt"],
        },
        0,
    )


def _rebuild_chart(a):
    """A chart's record holds no geometry: rebuild re-renders the description and holds the bytes to
    the record, with the same refusals as a diagram (2 stale, 3 mismatch)."""
    import hashlib as _h
    from chart import cli as chart_cli, desc as chart_desc, render as chart_render
    from .desc import DescError

    try:
        phash, d = chart_desc.load(a.fig)
    except DescError as e:
        raise Refusal(6, "invalid-description", str(e), file=a.fig) from e
    rec = _read_json(a.pick, "pick record", 3)
    if chart_cli.check_record(rec, d, phash):
        raise Refusal(
            2,
            "stale",
            f"the description changed since the chart was drawn ({str(rec['problem_hash'])[:12]} -> "
            f"{phash[:12]}); draw it again with press chart",
        )
    try:
        text = chart_render.render(d)
    except chart_render.Unfit as e:
        raise Refusal(
            3,
            "mismatch",
            f"the recorded chart no longer renders ({e}); the house style changed since it was drawn",
        ) from e
    sha = _h.sha256(text.encode("utf-8")).hexdigest()
    if sha != rec["svg_sha256"]:
        raise Refusal(
            3,
            "mismatch",
            f"rendered {sha[:16]} != recorded {rec['svg_sha256'][:16]}; the record or the house "
            "style changed since the chart was drawn",
        )
    pathlib.Path(a.svg).write_text(text, encoding="utf-8")
    return emit({"status": "rebuilt", "svg": a.svg, "svg_sha256": sha, "renderer": "chart"}, 0)


def rebuild(a):
    from . import record, render
    from .desc import DescError, read

    try:
        _, tree = read(a.fig)
    except DescError as e:
        raise Refusal(6, "invalid-description", str(e), file=a.fig) from e
    if isinstance(tree, dict) and tree.get("kind") == "chart":
        return _rebuild_chart(a)
    phash, d = _load(a.fig)
    rec = _read_json(a.pick, "pick record", 3)
    try:
        stale = record.check(d, rec, phash)
    except record.RecordError as e:
        raise Refusal(3, "record-mismatch", f"{a.pick}: {e}") from e
    if stale:
        raise Refusal(
            2,
            "stale",
            f"the description changed since the pick ({str(rec['problem_hash'])[:12]} -> "
            f"{phash[:12]}); solve and pick again",
        )
    text = render.svg(rec["geometry"])
    sha = hashlib.sha256(text.encode("utf-8")).hexdigest()
    if sha != rec["svg_sha256"]:
        raise Refusal(
            3,
            "mismatch",
            f"rendered {sha[:16]} != recorded {rec['svg_sha256'][:16]}; the pick record or "
            "the house style changed since the pick",
        )
    pathlib.Path(a.svg).write_text(text, encoding="utf-8")
    return emit({"status": "rebuilt", "svg": a.svg, "svg_sha256": sha}, 0)


class _Args(argparse.ArgumentParser):
    def error(self, message):
        raise Refusal(6, "bad-argument", f"{self.prog}: {message}")


def _positive(kind):
    def conv(v):
        x = kind(v)
        if x <= 0:
            raise ValueError(v)
        return x

    conv.__name__ = f"positive {kind.__name__}"
    return conv


def main(argv):
    p = _Args(prog="press fig")
    sub = p.add_subparsers(dest="cmd", required=True, parser_class=_Args)
    s = sub.add_parser("solve")
    s.add_argument("fig")
    s.add_argument("out")
    s.add_argument("--n", type=_positive(int), default=8)
    s.add_argument(
        "--cap-s", type=_positive(float), default=3.0, help="deterministic seconds per round"
    )
    s.add_argument(
        "--total-s",
        type=_positive(float),
        default=60.0,
        help="deterministic seconds for the whole solve",
    )
    s = sub.add_parser("pick")
    s.add_argument("fig")
    s.add_argument("out")
    s.add_argument("tile", type=int)
    s.add_argument("pick")
    s.add_argument("svg")
    s = sub.add_parser("rebuild")
    s.add_argument("fig")
    s.add_argument("pick")
    s.add_argument("svg")
    s = sub.add_parser("check")
    s.add_argument("svg")
    s.add_argument("--json")
    s = sub.add_parser("shoot")
    s.add_argument("svg")
    s.add_argument("png")
    try:
        a = p.parse_args(argv)
        inputs = {
            "solve": ("fig",),
            "pick": ("fig",),
            "rebuild": ("fig", "pick"),
            "check": ("svg",),
            "shoot": ("svg",),
        }
        for k in inputs[a.cmd]:
            if not pathlib.Path(getattr(a, k)).is_file():
                raise Refusal(6, "missing-file", f"{getattr(a, k)}: no such file")
        if a.cmd == "check":
            from .check import run

            r = run(a.svg, a.json)
            return emit(
                {
                    "file": a.svg,
                    "defects": r["defects"],
                    "by": r["by"],
                    "detail": r["detail"],
                    "min_pt": r["min_pt"],
                    "width_u": r["width_u"],
                    "height_u": r["height_u"],
                },
                1 if r["defects"] else 0,
            )
        if a.cmd == "shoot":
            from .sheet import shoot

            shoot(a.svg, a.png)
            return emit({"status": "shot", "png": a.png}, 0)
        return {"solve": solve, "pick": pick, "rebuild": rebuild}[a.cmd](a)
    except Refusal as r:
        return emit(r.out, r.code)
    except Exception as e:  # noqa: BLE001 - a defect in press: say so in the JSON, keep the trace on stderr
        import traceback

        traceback.print_exc()
        return emit(
            {"status": "internal", "message": f"a defect in press: {type(e).__name__}: {e}"}, 7
        )


if __name__ == "__main__":
    # run through the PACKAGE module, so fig.cli.Refusal is one class whether it is raised here or
    # by chart.cli (which imports fig.cli): under `-m fig.cli` this file is also loaded as __main__
    from fig.cli import main as _main

    sys.exit(_main(sys.argv[1:]))
