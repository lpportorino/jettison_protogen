"""`press pdfcheck` -- the layout detectors for an ARCHIVED PDF whose HTML is gone.

With no DOM there is no join: every element is guessed from typography -- glyph boxes, font names,
sizes, colours and ruled table grids. Measured against the DOM-backed detectors on twelve prints
where both exist (84 DOM hits, 108 PDF-only, 75 shared), only part of the set holds up, and only
that part runs here:

  runs     runt-page, heading-stranded, section-not-page-start, figure-text-below-floor (any text:
           without a DOM, figure text cannot be told from body text), overprint, outside-printable,
           split-table (ruled grids only), intro-stranded (a last line ending in ':' or a short bold
           line -- it MISSES intro lines that end otherwise: 9 such on the twelve prints)
  not run  broken-token (every checked PDF-only hit was false: 17 extra, 0 confirmed),
           starved-column, split-list, row-split, orphan-line, widow-line (they need to know which
           glyphs are one cell, one list, one row, one paragraph), text-not-printed (it needs the
           source text)

The page margins cannot be read from a PDF, so `--margins T,R,B,L` (mm) is required.

    press pdfcheck <file.pdf> --margins 22,21,24,21 [--json report.json]

One JSON line on stdout; exit 0 clean, 1 on a blocking finding (fix and info are reported), 6 on
bad input, 7 on a defect in press.
"""

import argparse
import collections
import json
import os
import re
import sys
import time

from . import detect, model

PT = 72 / 25.4
RUNS = [
    "runt-page",
    "heading-stranded",
    "intro-stranded",
    "section-not-page-start",
    "figure-text-below-floor",
    "overprint",
    "outside-printable",
    "split-table",
]
NOT_RUN = {
    "broken-token": "unreliable without the DOM: every checked PDF-only hit was false",
    "starved-column": "needs to know which glyphs form one table cell",
    "split-list": "needs to know which glyphs form one list",
    "row-split": "needs to know which glyphs form one table row",
    "orphan-line": "needs to know which lines form one paragraph",
    "widow-line": "needs to know which lines form one paragraph",
    "text-not-printed": "needs the source text",
}
if sorted(RUNS + list(NOT_RUN)) != sorted(detect.DETECTORS):
    raise RuntimeError(
        "pdfcheck must account for every detector: ran + not run != detect.DETECTORS"
    )


def pdf_lines(chars):
    """Glyph rows -> text lines with their typography (size, bold share, mono share, colour)."""
    gl = [(0, c[0], c[1], c[2], c[3], c[4], c[5], c[6], None, c[8]) for c in chars]
    out = []
    for L in detect.lines(gl):
        gs = L[2]
        txt, prev = "", None
        for g in gs:
            if prev is not None and g[1] - prev[3] > 0.22 * g[5]:
                txt += " "
            txt += g[7]
            prev = g
        sizes = collections.Counter(round(g[5], 1) for g in gs)
        out.append(
            {
                "top": L[0],
                "bottom": L[1],
                "x0": gs[0][1],
                "x1": gs[-1][3],
                "text": txt.strip(),
                "size": max(sizes, key=lambda s: sizes[s]),
                "bold": sum(1 for g in gs if "Bold" in g[6]) / len(gs),
                "mono": sum(1 for g in gs if detect.MONO.search(g[6])) / len(gs),
                "color": collections.Counter(g[9] for g in gs).most_common(1)[0][0],
                "glyphs": gs,
            }
        )
    return out


def luma(col):
    v = [float(x) for x in re.findall(r"[\d.]+", col or "")]
    return sum(v[:3]) / 3 if len(v) >= 3 else (v[0] if v else 0.0)


def is_heading(L):
    return L["bold"] > 0.8 and L["size"] >= 10.9 and luma(L["color"]) < 0.6


def is_section_head(L):
    return L["bold"] > 0.8 and L["size"] >= 15.5


class Unreadable(Exception):
    """A PDF the reader cannot parse, or one with nothing for the detectors to read: bad input."""


def read(pdf_path):
    """Read the PDF in full, or refuse it as bad input. Any failure of the READER is the file's
    (a corrupt or truncated PDF); a failure in the detectors afterwards is press's. A PDF with no text
    layer (a scan, images only) leaves every detector here with nothing to read."""
    try:
        r = model.read_pdf(pdf_path)
    except Exception as e:  # noqa: BLE001 - whatever the reader trips on, the file is not a readable PDF
        raise Unreadable(
            f"{pdf_path}: not a readable PDF ({type(e).__name__}: {str(e)[:160]})"
        ) from e
    if not r["pages"]:
        raise Unreadable(f"{pdf_path}: the PDF has no pages")
    if not any(c for p in r["pages"] for c in p["chars"]):
        raise Unreadable(
            f"{pdf_path}: the PDF has no text layer (a scan or images only): none of the detectors "
            "has anything to read"
        )
    return r


def run(pdf_path, margins, r):
    """-> (findings, stats). margins = (top, right, bottom, left) in mm; r = read(pdf_path)."""
    pages = r["pages"]
    if not pages:
        return [], {"pages": 0}
    top, right, bottom, left = (v * PT for v in margins)
    W, H = pages[0]["w"], pages[0]["h"]
    prt, prb, prl, prr = top, H - bottom, left, W - right
    ph = prb - prt
    F = []
    body = [[L for L in pdf_lines(p["chars"]) if L["text"]] for p in pages]
    starts = {0}
    for i, ls in enumerate(body):
        if ls and (is_section_head(ls[0]) or luma(ls[0]["color"]) > 0.7):
            starts.add(i)
    n = len(pages)
    for i, ls in enumerate(body):
        if not ls:
            continue
        bot = max(L["bottom"] for L in ls)
        for d in pages[i]["draws"]:
            if d[1] >= prt - 2 and d[3] <= prb + 2 and not d[6]:
                bot = max(bot, d[3])
        fill = (bot - prt) / ph
        section_end = (i + 1 in starts) or i == n - 1
        if (len(ls) <= 3 and i not in starts) or (fill < 0.2 and not section_end):
            F.append(
                detect.finding(
                    "runt-page",
                    i,
                    f"page {i + 1}",
                    {"lines": len(ls), "fill": round(fill, 2)},
                    "blocking",
                )
            )
        last = ls[-1]
        room = (prb - last["bottom"]) / PT
        if i + 1 < n:
            if is_heading(last):
                F.append(
                    detect.finding(
                        "heading-stranded",
                        i,
                        f'"{last["text"][:50]}"',
                        {"space_below_mm": round(room)},
                        "blocking",
                    )
                )
            elif last["text"].endswith(":") or (
                last["bold"] > 0.9 and (last["x1"] - last["x0"]) < 0.6 * (prr - prl)
            ):
                F.append(
                    detect.finding(
                        "intro-stranded",
                        i,
                        f'"{last["text"][:50]}"',
                        {"space_below_mm": round(room)},
                        "blocking",
                    )
                )
        for k, L in enumerate(ls):
            if is_section_head(L) and re.match(r"^\d+\.\s*[A-Z]", L["text"]):
                above = [M for M in ls[:k] if luma(M["color"]) < 0.7]
                if above:
                    F.append(
                        detect.finding(
                            "section-not-page-start",
                            i,
                            f'"{L["text"][:50]}"',
                            {"lines_above_on_page": len(above)},
                            "blocking",
                        )
                    )
        small = [
            g
            for L in ls
            for g in L["glyphs"]
            if g[5] < detect.FLOOR_PT - 0.05 and not g[7].isspace()
        ]
        if small:
            F.append(
                detect.finding(
                    "figure-text-below-floor",
                    i,
                    f"{len(small)} glyphs",
                    {
                        "min_pt_on_paper": round(min(g[5] for g in small), 1),
                        "glyphs_below": len(small),
                        "scope": "any text (no DOM: figure text cannot be told apart)",
                        "floor_pt": detect.FLOOR_PT,
                    },
                    "blocking",
                )
            )
    m = {
        "nodes": {},
        "frag": {},
        "sparent": {},
        "pages": pages,
        "page_rule": {
            "top": margins[0],
            "right": margins[1],
            "bottom": margins[2],
            "left": margins[3],
        },
    }
    D = detect.Doc(m)
    F += detect.overprint(D)
    F += detect.outside_printable(D)
    F += split_tables(pdf_path, prt, prb)
    return F, {"pages": n}


def _frag(t, chars):
    """One table fragment: its row count, bottom edge, and the height of the text inside it (the
    same measure the DOM detector sums -- glyph extents, not the ruled box with its padding)."""
    x0, top, x1, bottom = t.bbox
    inside = [
        c
        for c in chars
        if x0 - 1 <= c["x0"]
        and c["x1"] <= x1 + 1
        and top - 1 <= c["top"]
        and c["bottom"] <= bottom + 1
    ]
    ink_h = (max(c["bottom"] for c in inside) - min(c["top"] for c in inside)) if inside else 0.0
    return {"rows": len(t.rows), "bottom": bottom, "ink_h": ink_h}


def split_tables(pdf_path, prt, prb):
    """A ruled grid that is the LAST content on its page and one that is the FIRST content of the
    next. fix, as the DOM detector does, when the two fragments together are short enough (under 35 %
    of the printable height) to have moved to the next page intact, or when either fragment has at
    most 2 rows (a stub; a repeated header row counts); a long table that must flow is info."""
    import pdfplumber

    def ink(o):
        if o.get("object_type") == "rect" and not o.get("stroke"):
            c = model._num_color(o.get("non_stroking_color"))
            if c is not None and min(c) > 0.98:
                return False
        return True

    out, info = [], []
    with pdfplumber.open(pdf_path) as pdf:
        for p in pdf.pages:
            ts = [
                t
                for t in p.filter(ink).find_tables(
                    {"vertical_strategy": "lines", "horizontal_strategy": "lines"}
                )
                if len(t.rows) >= 2
            ]
            ls = pdf_lines(
                [
                    [
                        c["x0"],
                        c["top"],
                        c["x1"],
                        c["bottom"],
                        c["size"],
                        c["fontname"],
                        c["text"],
                        None,
                        str(c.get("non_stroking_color")),
                    ]
                    for c in p.chars
                ]
            )
            last = max(ts, key=lambda t: t.bbox[3]) if ts else None
            first = min(ts, key=lambda t: t.bbox[1]) if ts else None
            is_last = last is not None and not any(L["top"] > last.bbox[3] + 1 for L in ls)
            is_first = first is not None and not any(L["bottom"] < first.bbox[1] - 1 for L in ls)
            info.append(
                (
                    _frag(last, p.chars) if is_last else None,
                    _frag(first, p.chars) if is_first else None,
                )
            )
    for i in range(len(info) - 1):
        foot, head = info[i][0], info[i + 1][1]
        if foot is not None and head is not None:
            height = foot["ink_h"] + head["ink_h"]
            short = height < 0.35 * (prb - prt)
            stub = min(foot["rows"], head["rows"]) <= 2
            out.append(
                detect.finding(
                    "split-table",
                    i,
                    f"table ending at {round(foot['bottom'] / PT)} mm",
                    {
                        "rows_here": foot["rows"],
                        "rows_next_page": head["rows"],
                        "height_pt": round(height),
                        "short_block": short,
                    },
                    "fix" if short or stub else "info",
                )
            )
    return out


class _Args(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError(f"press pdfcheck: {message}")


def _margins(v):
    try:
        m = tuple(float(x) for x in v.split(","))
    except ValueError:
        m = ()
    if len(m) != 4 or any(not 0 <= x < 100 for x in m):
        raise argparse.ArgumentTypeError(
            f"{v!r}: expected four margins in mm, T,R,B,L (each 0..100)"
        )
    return m


def _host(x):
    if isinstance(x, str):
        for pair in filter(None, os.environ.get("PRESS_PATHMAP", "").split("\n")):
            c, h = pair.split("=", 1)
            if x == c or x.startswith(c + "/"):
                return h + x[len(c) :]
    return x


def main(argv):
    t0 = time.time()
    ap = _Args(prog="press pdfcheck")
    ap.add_argument("pdf")
    ap.add_argument("--margins", type=_margins, required=True, help="page margins in mm: T,R,B,L")
    ap.add_argument("--json")
    try:
        a = ap.parse_args(argv)
        if not os.path.isfile(a.pdf):
            raise ValueError(f"{a.pdf}: no such file")
        with open(a.pdf, "rb") as fh:
            if fh.read(5) != b"%PDF-":
                raise ValueError(f"{a.pdf}: not a PDF")
    except ValueError as e:
        print(json.dumps({"status": "bad-argument", "message": _host(str(e))}, ensure_ascii=False))
        return 6
    try:
        r = read(a.pdf)
    except Unreadable as e:
        print(json.dumps({"status": "unreadable", "message": _host(str(e))}, ensure_ascii=False))
        return 6
    try:
        F, stats = run(a.pdf, a.margins, r)
    except Exception as e:  # noqa: BLE001 - a defect in press (or a PDF the reader cannot parse): say so
        import traceback

        traceback.print_exc()
        print(
            json.dumps(
                {"status": "internal", "message": f"a defect in press: {type(e).__name__}: {e}"}
            )
        )
        return 7
    failing = [f for f in F if f["severity"] == "blocking"]  # as press pdf: only blocking fails
    counts = dict(
        sorted(collections.Counter(f"{f['detector']}:{f['severity']}" for f in F).items())
    )
    rep = {
        "pdf": a.pdf,
        "mode": "pdf-only",
        "margins_mm": list(a.margins),
        "pages": stats["pages"],
        "ran": RUNS,
        "not_run": NOT_RUN,
        "counts": counts,
        "failing": len(failing),
        "findings": F,
    }
    if a.json:
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump(rep, fh, indent=1, ensure_ascii=False)
    summary = {
        "status": "defects" if failing else "ok",
        "pdf": _host(a.pdf),
        "pages": stats["pages"],
        "counts": counts,
        "failing": len(failing),
        "not_run": sorted(NOT_RUN),
        "report": _host(a.json) if a.json else None,
        "seconds": round(time.time() - t0, 1),
    }
    print(json.dumps(summary, ensure_ascii=False))
    return 1 if failing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
