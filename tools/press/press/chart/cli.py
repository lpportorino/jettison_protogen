"""`press chart <chart.fig.json> <pick.json> <out.svg> [--json report.json]` -- draw a chart from data.

Renders the description deterministically, runs the figure checker (fig.check, the same checks a
diagram passes) on the result, and only when it is clean writes BOTH the SVG and the pick record
{id, problem_hash, svg_sha256, renderer: "chart"} -- so a chart is current by the same two hash
comparisons as a diagram, and `press fig rebuild` redraws it with the same refusals (2: the description
changed since the record; 3: the record does not match, or the rendered bytes differ). A chart the
checker rejects writes neither file. One JSON line on stdout; the exit codes are fig.cli's:
  0 drawn · 1 the checker found defects · 4 it cannot fit the 476-unit width (`needs` says what it
  would take) · 5 over the page budget · 6 invalid input · 7 a defect in press"""

import hashlib
import pathlib
import sys

from fig.cli import Refusal, _Args, _write_json, emit

RENDERER = "chart"


def draw(path):
    """-> (problem_hash, description, svg text); Refusal on invalid input or a chart that cannot fit."""
    from fig.desc import DescError
    from . import desc, render

    try:
        phash, d = desc.load(path)
    except DescError as e:
        raise Refusal(6, "invalid-description", str(e), file=path) from e
    try:
        return phash, d, render.render(d)
    except render.Unfit as e:
        raise Refusal(
            e.code,
            "unsatisfiable" if e.code == 4 else "over-budget",
            str(e),
            problem_hash=phash,
            needs=e.needs,
        ) from e


def record(d, phash, svg_text):
    return {
        "id": d["id"],
        "problem_hash": phash,
        "svg_sha256": hashlib.sha256(svg_text.encode("utf-8")).hexdigest(),
        "renderer": RENDERER,
    }


def check_record(rec, d, phash):
    """Raise Refusal 3 unless rec is a well-formed chart record of d; return True when it is stale
    (the description changed since: the caller's exit 2)."""
    if not isinstance(rec, dict) or sorted(rec) != ["id", "problem_hash", "renderer", "svg_sha256"]:
        raise Refusal(
            3,
            "record-mismatch",
            "a chart record is exactly {id, problem_hash, renderer, svg_sha256}, got "
            f"{sorted(rec) if isinstance(rec, dict) else type(rec).__name__}",
        )
    if rec["renderer"] != RENDERER:
        raise Refusal(3, "record-mismatch", f'renderer {rec["renderer"]!r} is not "chart"')
    if rec["id"] != d["id"]:
        raise Refusal(
            3, "record-mismatch", f"the record is for {rec['id']!r}, the description is {d['id']!r}"
        )
    from fig.record import SHA_RE

    for k in ("problem_hash", "svg_sha256"):
        if not isinstance(rec[k], str) or not SHA_RE.fullmatch(rec[k]):
            raise Refusal(3, "record-mismatch", f"{k} is not a sha256: {rec[k]!r}")
    return rec["problem_hash"] != phash


def main(argv):
    p = _Args(prog="press chart")
    p.add_argument("fig")
    p.add_argument("pick")
    p.add_argument("svg")
    p.add_argument("--json")
    try:
        a = p.parse_args(argv)
        if not pathlib.Path(a.fig).is_file():
            raise Refusal(6, "missing-file", f"{a.fig}: no such file")
        phash, d, text = draw(a.fig)
        from fig.check import Checker

        with Checker() as chk:
            rep = chk.check(text, a.svg)
        if a.json:
            _write_json(a.json, rep)
        if rep["defects"]:
            return emit(
                {
                    "status": "defects",
                    "by": rep["by"],
                    "detail": rep["detail"],
                    "problem_hash": phash,
                },
                1,
            )
        rec = record(d, phash, text)
        pathlib.Path(a.svg).write_text(text, encoding="utf-8")
        _write_json(a.pick, rec)
        return emit(
            {
                "status": "drawn",
                "chart": d["chart"],
                "svg": a.svg,
                "pick": a.pick,
                "svg_sha256": rec["svg_sha256"],
                "problem_hash": phash,
                "height_u": rep["height_u"],
                "min_pt": rep["min_pt"],
            },
            0,
        )
    except Refusal as r:
        return emit(r.out, r.code)
    except Exception as e:  # noqa: BLE001 - a defect in press: say so in the JSON, keep the trace on stderr
        import traceback

        traceback.print_exc()
        return emit(
            {"status": "internal", "message": f"a defect in press: {type(e).__name__}: {e}"}, 7
        )


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
