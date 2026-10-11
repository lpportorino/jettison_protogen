"""The paged-layout detectors' planted fixtures: pairs of one-or-two-page documents that differ ONLY
in the planted defect (`<name>.bad.html` / `<name>.good.html`), styled by the dedicated `paged.css` fixture stylesheet.

CASES pins, per page, the EXACT set of (detector, severity) findings and the exit code `press pdf` must
give; PDFCHECK_CASES the same for `press pdfcheck`. A case with no bad page is a control: the good page
holds the bad page's geometry with the house rule that prevents the defect left on.

    python3 paged_fixtures.py <out-dir>      -> writes the pages
"""

import pathlib
import sys

CSS = (pathlib.Path(__file__).resolve().parent / "paged.css").read_text()
WORDS = (
    "the hub reads each module state from the bus and publishes one record per tick so that every "
    "consumer sees the same value at the same time while the node keeps its own clock in step"
).split()


def prose(n, seed=0):
    out = []
    for i in range(n):
        w = WORDS[(i * 7 + seed) % len(WORDS)]
        out.append(w.capitalize() if i % 14 == 0 else w)
        if i % 14 == 13:
            out[-1] += "."
    return " ".join(out) + "."


def page(body, extra_css=""):
    return (
        f'<!doctype html><html><head><meta charset="utf-8"><title>fixture</title>'
        f"<style>{CSS}\n{extra_css}</style></head><body>{body}</body></html>"
    )


def spacer(mm):
    return f'<div style="height:{mm}mm"></div>'


def table(rows, cols=3, cellwords=6, colw=None):
    cg = ""
    if colw:
        cg = "<colgroup>" + "".join(f'<col style="width:{w}%">' for w in colw) + "</colgroup>"
    head = (
        "<thead><tr>" + "".join(f"<th>column {c + 1}</th>" for c in range(cols)) + "</tr></thead>"
    )
    body = "".join(
        "<tr>" + "".join(f"<td>{prose(cellwords, r * 3 + c)}</td>" for c in range(cols)) + "</tr>"
        for r in range(rows)
    )
    return f'<table class="fixed">{cg}{head}<tbody>{body}</tbody></table>'


def ul(n, words=10):
    return "<ul>" + "".join(f"<li>{prose(words, i)}</li>" for i in range(n)) + "</ul>"


def svg(viewbox_w, fs, label="label text in the figure"):
    h = 160
    return (
        f'<svg class="svgtxt" viewBox="0 0 {viewbox_w} {h}" xmlns="http://www.w3.org/2000/svg">'
        f'<rect x="4" y="4" width="{viewbox_w - 8}" height="{h - 8}" fill="#dbe7f3" stroke="#1f3a5f"/>'
        f'<text x="20" y="40" font-size="{fs}">{label}</text>'
        f'<text x="20" y="80" font-size="{fs}">a second {label}</text></svg>'
    )


FIX = {}

# split table: a short table pushed across a page boundary
FIX["split_table"] = (
    page(spacer(205) + "<p>The table lists the parts.</p>" + table(6)),
    page(spacer(60) + "<p>The table lists the parts.</p>" + table(6)),
)
# split list: a short list pushed across a page boundary
FIX["split_list"] = (
    page(spacer(222) + "<p>Every module does four things.</p>" + ul(6)),
    page(spacer(60) + "<p>Every module does four things.</p>" + ul(6)),
)
# a list of long paragraph items flowing onto the next page is normal flow (info), even when only
# ONE item starts there; an item-count-only rule would mistake this for a stub;
# the same list whose last, one-line item lands alone on the next page leaves a real stub (warn)
long_items = "".join(f"<li>{prose(90, i)}</li>" for i in range(3))
FIX["split_list_stub"] = (
    page(spacer(140) + f"<ul>{long_items}<li>One more short item.</li></ul>"),
    page(spacer(140) + f"<ul>{long_items}<li>{prose(120, 9)}</li></ul>"),
)
# row split: rows allowed to break, a tall row at the page end
tall = (
    f'<table class="fixed"><colgroup><col style="width:35%"><col style="width:65%"></colgroup>'
    f"<tbody><tr><td>{prose(12)}</td><td>{prose(260, 1)}</td></tr></tbody></table>"
)
FIX["row_split"] = (
    page(spacer(200) + tall, "tr { break-inside: auto; }"),
    page(spacer(10) + tall, "tr { break-inside: auto; }"),
)
# heading stranded: the heading may break after, an unbreakable block follows
note = f'<div class="note keep">{prose(150, 2)}</div>'
FIX["heading_stranded"] = (
    page(spacer(215) + "<h3>4.2 The harness</h3>" + note, "h3 { break-after: auto; }"),
    page(spacer(100) + "<h3>4.2 The harness</h3>" + note, "h3 { break-after: auto; }"),
)
# control: the same geometry with the house rule (break-after: avoid) left on -- Chromium honours it
FIX["heading_avoid_honoured"] = (None, page(spacer(215) + "<h3>4.2 The harness</h3>" + note))
# intro line stranded before an unbreakable list
klist = '<ul class="keep">' + "".join(f"<li>{prose(18, i)}</li>" for i in range(6)) + "</ul>"
FIX["intro_stranded"] = (
    page(spacer(222) + "<p>The node keeps three counters:</p>" + klist),
    page(spacer(100) + "<p>The node keeps three counters:</p>" + klist),
)
# a caption separated from its figure (the figure allowed to break)
fig = f"<figure>{svg(476, 7.5)}<figcaption><b>Figure 1 — the bus.</b> {prose(30)}</figcaption></figure>"
FIX["caption_stranded"] = (
    page(spacer(185) + fig, "figure { break-inside: auto; }"),
    page(spacer(60) + fig, "figure { break-inside: auto; }"),
)
# runt page: two lines spill onto a page before a forced section break
nxt = f'<div class="section"><h2>2. Next</h2><p>{prose(60)}</p></div>'
FIX["runt_page"] = (
    page(spacer(241) + f"<p>{prose(40)}</p>" + nxt),
    page(spacer(200) + f"<p>{prose(40)}</p>" + nxt),
)
# orphan / widow lines when the house allows them
para = f"<p>{prose(120, 3)}</p>"
FIX["orphan"] = (page(spacer(242) + para, "p { orphans: 1; widows: 1; }"), page(spacer(242) + para))
after = f"<p>{prose(80, 7)}</p>"
FIX["widow"] = (
    page(spacer(210) + para + after, "p { orphans: 1; widows: 1; }"),
    page(spacer(210) + para + after),
)
# overprint: a fixed-height flex block whose content overflows onto the next block
cover = (
    f'<div class="cover" style="height:60mm"><div class="title"><h1>A title</h1><p>{prose(60)}</p></div>'
    f'<div class="meta">{prose(40, 5)}</div></div><p>{prose(80, 6)}</p>'
)
FIX["overprint_text"] = (page(cover), page(cover.replace("height:60mm", "height:auto")))
# a SYNTHETIC italic (a family with only an upright face: the house font, Inter, ships none) right
# before punctuation: the printer leans the upright glyph, and the slanted glyph's box (built from
# two corners of its sheared em-box) covers most of the next full stop's box while the ink is clear.
# That is NOT an overprint. The bad twin pulls the same italic span back over the word before it --
# a real overprint, which must still be reported.
slant_css = (
    '@font-face { font-family: "UprightOnly"; src: local("Liberation Sans"); font-style: normal; '
    'font-weight: 400; } em { font-family: "UprightOnly"; }'
)


def slant(pull):
    return (
        "<p>"
        + prose(30)
        + f" The step counted <em{pull}>starved</em>. The <em>device bus</em>, the "
        "<em>module bus</em>: the hub (<em>silent-device</em>) and its <em>leaves</em>; then "
        + prose(30, 4)
        + "</p>"
    )


FIX["italic_punct"] = (
    page(slant(' style="margin-left:-2.4em"'), slant_css),
    page(slant(""), slant_css),
)
FIX["overprint_figure"] = (
    page(
        f"<p>{prose(40)}</p>" + fig.replace("<figcaption>", '<figcaption style="margin-top:-90pt">')
    ),
    page(f"<p>{prose(40)}</p>" + fig),
)
# outside the printable area: a negative margin (no page shrink, unlike a too-wide box); the glyphs
# pushed past the sheet edge are not printed at all
FIX["outside"] = (
    page(f'<p>{prose(40)}</p><p style="margin-left:-14mm">{prose(40, 2)}</p>'),
    page(f"<p>{prose(40)}</p><p>{prose(40, 2)}</p>"),
)
# starved column: prose in a column 8 % wide
FIX["starved"] = (page(table(3, 3, 14, colw=[8, 46, 46])), page(table(3, 3, 14, colw=[30, 35, 35])))
# the same narrow column holding a LIST that wraps at its own separators is not starved
names = ["fan", "tap", "hub", "sprayer", "heater", "curtains", "dimmers"]


def narrow(t):
    return (
        f'<table class="fixed"><colgroup><col style="width:16%"><col style="width:84%"></colgroup>'
        f"<tbody><tr><td>{t}</td><td>{prose(30)}</td></tr></tbody></table>"
    )


FIX["starved_list"] = (page(narrow(" ".join(names))), page(narrow(" · ".join(names))))
# a code token broken mid-word in a narrow fixed column
path = "tools/toolchain/local/build/run-inside.sh"


def cell(t):
    return (
        f'<table class="fixed"><colgroup><col style="width:16%"><col style="width:84%"></colgroup>'
        f'<tbody><tr><td><span class="mono">{t}</span></td><td>{prose(30)}</td></tr></tbody></table>'
    )


FIX["broken_token"] = (page(cell(path)), page(cell(path.replace("/", "/<wbr>"))))
# figure text below the floor ON PAPER: a 900-unit viewBox printed 476 pt wide
FIX["figure_floor"] = (
    page(f"<figure>{svg(900, 7.5)}</figure>"),
    page(f"<figure>{svg(476, 7.5)}</figure>"),
)
# a numbered section that does not start a page
FIX["section_start"] = (
    page(
        f'<div class="section"><h2>2. First</h2><p>{prose(80)}</p></div>'
        f"<h2>3. Second</h2><p>{prose(40)}</p>"
    ),
    page(
        f'<div class="section"><h2>2. First</h2><p>{prose(80)}</p></div>'
        f'<div class="section"><h2>3. Second</h2><p>{prose(40)}</p></div>'
    ),
)

# a page that reaches outside itself: a sibling stylesheet, an @import, a file:// iframe and image.
# A printed page must be self-contained: every one of those requests is aborted and reported.
ext_head = (
    '<link rel="stylesheet" href="side.css"><style>@import url("file:///work/README.md");</style>'
)
ext_body = (
    '<iframe src="file:///etc/hostname" style="width:120mm;height:30mm"></iframe>'
    '<img src="file:///etc/hostname" alt="">'
)
FIX["external"] = (
    page(f"{ext_head}<p>{prose(40)}</p>{ext_body}<p>{prose(30, 4)}</p>"),
    page(f"<p>{prose(40)}</p><p>{prose(30, 4)}</p>"),
)
SIDE_CSS = "p { color: #a8352f; }\n"
# text a closed <details> hides lays out no text box: it is not rendered, so it is neither a broken
# join nor text that failed to print
FIX["details_hidden"] = (
    None,
    page(
        f"<p>{prose(40)}</p><details><summary>More</summary>"
        + "".join(f"<p>{prose(20, i)}</p>" for i in range(12))
        + "</details>"
    ),
)

B, F, INFO = "blocking", "fix", "info"
# THE EXPECTATION, pinned by hand per page: the exact set of (detector, severity) the layout pass must
# report and the exit code `press pdf` must give. Nothing here is derived from a run: a detector that
# changes severity, stops firing or starts firing elsewhere turns the selftest red. Side findings
# are real consequences of the plant (a caption pushed to page 2 alone also leaves a runt page; an
# 8 %-wide column's long words also overprint the next cell; a `/` break in a path is an info-level
# break at a non-letter).
CASES = {
    "split_table": {"bad": ({("split-table", F)}, 0), "good": (set(), 0)},
    "split_list": {"bad": ({("split-list", F)}, 0), "good": (set(), 0)},
    "split_list_stub": {
        "bad": ({("split-list", F), ("runt-page", B)}, 1),
        "good": ({("split-list", INFO)}, 0),
    },
    "row_split": {"bad": ({("row-split", B), ("split-table", INFO)}, 1), "good": (set(), 0)},
    "heading_stranded": {"bad": ({("heading-stranded", B)}, 1), "good": (set(), 0)},
    "heading_avoid_honoured": {"good": (set(), 0)},
    "intro_stranded": {"bad": ({("intro-stranded", B)}, 1), "good": (set(), 0)},
    "caption_stranded": {"bad": ({("intro-stranded", B), ("runt-page", B)}, 1), "good": (set(), 0)},
    "runt_page": {"bad": ({("runt-page", B)}, 1), "good": (set(), 0)},
    "orphan": {"bad": ({("orphan-line", F)}, 0), "good": (set(), 0)},
    "widow": {"bad": ({("widow-line", F)}, 0), "good": (set(), 0)},
    "overprint_text": {"bad": ({("overprint", B)}, 1), "good": (set(), 0)},
    "overprint_figure": {"bad": ({("overprint", B)}, 1), "good": (set(), 0)},
    "italic_punct": {"bad": ({("overprint", B)}, 1), "good": (set(), 0)},
    "outside": {
        "bad": ({("outside-printable", B), ("text-not-printed", B)}, 1),
        "good": (set(), 0),
    },
    "starved": {"bad": ({("starved-column", B), ("overprint", B)}, 1), "good": (set(), 0)},
    "starved_list": {"bad": ({("starved-column", B)}, 1), "good": (set(), 0)},
    "broken_token": {
        "bad": ({("broken-token", B), ("broken-token", INFO)}, 1),
        "good": ({("broken-token", INFO)}, 0),
    },
    "figure_floor": {"bad": ({("figure-text-below-floor", B)}, 1), "good": (set(), 0)},
    "section_start": {"bad": ({("section-not-page-start", B)}, 1), "good": (set(), 0)},
    "external": {"bad": ({("external-resource", B)}, 1), "good": (set(), 0)},
    "details_hidden": {"good": (set(), 0)},
}
# the same pages read by `press pdfcheck` (margins 22,21,24,21, paged.css's @page), pinned the same way
PDFCHECK_CASES = {
    "split_table": {"bad": ({("split-table", F)}, 0), "good": (set(), 0)},
    "heading_stranded": {"bad": ({("heading-stranded", B)}, 1), "good": (set(), 0)},
    "intro_stranded": {"bad": ({("intro-stranded", B)}, 1), "good": (set(), 0)},
    "runt_page": {"bad": ({("runt-page", B)}, 1), "good": (set(), 0)},
    "overprint_text": {"bad": ({("overprint", B)}, 1), "good": (set(), 0)},
    "italic_punct": {"bad": ({("overprint", B)}, 1), "good": (set(), 0)},
    "outside": {"bad": ({("outside-printable", B)}, 1), "good": (set(), 0)},
    "figure_floor": {"bad": ({("figure-text-below-floor", B)}, 1), "good": (set(), 0)},
    "section_start": {"bad": ({("section-not-page-start", B)}, 1), "good": (set(), 0)},
}


def write(out):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "side.css").write_text(SIDE_CSS, encoding="utf-8")
    for k, (bad, good) in FIX.items():
        if bad:
            (out / f"{k}.bad.html").write_text(bad, encoding="utf-8")
        (out / f"{k}.good.html").write_text(good, encoding="utf-8")
    return len(FIX)


if __name__ == "__main__":
    print(write(sys.argv[1]), "fixtures", file=sys.stderr)
