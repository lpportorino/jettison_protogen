"""deck_pdf -- print ONE self-contained HTML page to PDF, and to one PNG per page.

The sibling of `deck_qa.py`: that one shoots a SLIDE deck and runs the twelve
defect detectors over it; this one prints a PAGED document (a report, a
guide) through the same Chromium, at A4, with print CSS honoured and
backgrounds on.

The PNGs are rasterised from the FINISHED PDF with `pdftoppm`, not screenshotted
from the DOM, and that choice is the point: the eyeball has to see what the PDF
actually contains -- the browser's own pagination, every `break-inside: avoid`
it honoured or ignored, every figure that landed across a page boundary. A DOM
screenshot shows the page the author hoped for; the raster shows the page the
reader will open.

    press pdf report.html out/            # report.pdf + page-01.png ... + pdf_report.json

The page images are the PDF's pages and nothing else: every `page-<digits>.png` an earlier print
left in out/ is removed before this one prints (pdftoppm overwrites only the pages it writes, so
a shorter print would otherwise sit beside the tail of a longer one), and an image count that is
not the PDF's own page count fails the print. Other files in out/ are kept.

Every internal link is audited against the destinations the PDF defines
(`pdf_links.py`): a `<a href="#id">` whose target has no layout box on any
printed page -- hidden under print media, or absent -- leaves a link that
looks right and does nothing. A page with dead links fails the print, so a
report cannot ship marks that lead nowhere; `--allow-dead-links` records
them in the report instead, for a page whose links are decorative.

Every element is also measured at the PRINTABLE width, derived from the
document's own `@page` margins on A4.  An element wider than that makes
Chromium shrink EVERY page to fit it. A wide table can therefore reduce the
body's printed type size even when the browser reports a successful print.
The outermost overflowing elements are named and the print fails;
`--allow-overflow` records them instead.

The print is TAGGED, and a layout pass joins every DOM node to its printed
glyphs (`paged/model.py`) and runs the fifteen paged-layout detectors
(`paged/detect.py`). The join proves itself on every run (three shares, each
at least 95 %); under a floor the print fails with exit 7 saying the tagged-PDF
join no longer holds, instead of reporting "no findings". A page with no text
that paints (an empty body, only images, an empty file) is bad input: exit 6.

A printed page must be SELF-CONTAINED: every request other than the document
itself and `data:` URLs (a sibling stylesheet, a file:// iframe or image, an
@import, http) is aborted and reported as an `external-resource` finding.

`pdf_report.json` carries ONE findings list: `findings`,
each {detector, page, element, measure, severity, allowed}, severity
blocking | fix | info. `layout` keeps the mechanism, the join, `counts` and
`allowed`. Only a blocking finding that is not allowed fails the print;
`--allow-layout a,b` (once; a comma list) records the named findings without
failing it -- for `external-resource` it also lets the request through.
`html_sha256` hashes the page bytes read BEFORE the print; a file that changes
during the print is refused.

    python3 -m deck_pdf <page.html> <out-dir> [--name n] [--allow-layout d,...]

Prints ONE JSON line on stdout (host paths); details go to stderr and the
report. Exit 0 when the PDF was written, every page rasterised, every internal
link resolves, nothing is wider than the printable width and no blocking
finding stands; 1 when any of those fails; 6 on bad input (a missing page, a
bad argument, a page with nothing to print, a file that changed during the
print); 7 on a defect in press itself -- including a tagged-PDF join that no
longer holds.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time

import pdf_links


# the printer's own page images: pdftoppm's `page-<n>.png`, n zero-padded to the page count's width
PAGE_PNG = re.compile(r"page-(\d+)\.png")


def _own_pages(out_dir):
    """The page images in `out_dir` that this printer names (`page-<digits>.png`), in page order."""
    hits = [(int(m.group(1)), n) for n in os.listdir(out_dir) if (m := PAGE_PNG.fullmatch(n))]
    return [os.path.join(out_dir, n) for _, n in sorted(hits)]


def _clear_pages(out_dir):
    """Remove the page images an EARLIER print left in `out_dir`, before this print writes any.
    pdftoppm overwrites only the pages it writes, so a 55-page print followed by a 52-page one
    left page-53..55 beside the new PDF: a reader of the directory was handed pages that are not
    in the document, and the report counted them. Only files named exactly `page-<digits>.png`
    go; anything else in the directory is not ours and is kept."""
    for p in _own_pages(out_dir):
        if not os.path.isdir(p):
            os.remove(p)


def _pdf_page_count(pdf):
    """The page count the PDF itself declares (pdfplumber), independent of the rasteriser."""
    import pdfplumber

    with pdfplumber.open(pdf) as doc:
        return len(doc.pages)


def _png_pages(pdf, out_dir, dpi):
    """Rasterise every page of `pdf` with pdftoppm; return the file list. The list must hold
    exactly one image per page of the PDF -- anything else is an error, never a page count."""
    exe = shutil.which("pdftoppm")
    if not exe:
        return None, "pdftoppm not in the image -- add poppler-utils to the Dockerfile"
    prefix = os.path.join(out_dir, "page")
    proc = subprocess.run(
        [exe, "-r", str(dpi), "-png", pdf, prefix],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        return None, f"pdftoppm exit {proc.returncode}: {proc.stderr.strip()[:400]}"
    pages = _own_pages(out_dir)
    want = _pdf_page_count(pdf)
    if len(pages) != want:
        return None, f"{len(pages)} page image(s) for a {want}-page PDF in {out_dir}"
    return pages, None


PAGE_RULE_JS = """() => {
  const mm = v => { const m = /^([\\d.]+)mm$/.exec((v || '').trim()); return m ? parseFloat(m[1]) : null; };
  let ml = 0, mr = 0, found = false;
  for (const ss of document.styleSheets) {
    let rules; try { rules = ss.cssRules; } catch (e) { continue; }
    for (const r of rules) {
      if (r.type !== CSSRule.PAGE_RULE) continue;
      found = true;
      const L = mm(r.style.marginLeft), R = mm(r.style.marginRight);
      if (L !== null) ml = L;
      if (R !== null) mr = R;
    }
  }
  return {found, width_mm: 210 - ml - mr};
}"""

OFFENDERS_JS = """(w) => {
  // two ways an element makes the printer shrink the page: its BOX runs past the
  // edge (a table grows to its unbreakable content), or its box fits and its
  // CONTENT spills past the edge (nowrap text in a block).  Report the outermost
  // box overflow, and a content overflow only where no child's box explains it.
  const boxOver = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.right > w + 1; };
  const contentOver = el => {
    const r = el.getBoundingClientRect();
    return r.width > 0 && !boxOver(el) && el.scrollWidth > el.clientWidth + 1 && r.left + el.scrollWidth > w + 1;
  };
  const name = el => el.tagName.toLowerCase() +
    (typeof el.className === 'string' && el.className ? '.' + el.className.split(/\\s+/)[0] : '');
  const out = [];
  for (const el of document.body.querySelectorAll('*')) {
    // An element INSIDE an inline <svg> cannot widen the page: the svg root is a
    // replaced box that clips its content (the UA stylesheet's `svg:not(:root)
    // { overflow: hidden }`), and scrollWidth on an SVG text element reports its
    // advance in USER units, which a scaled viewBox does not map to CSS px --
    // measured: a 760-unit viewBox printed at 672 px flagged every long line
    // that visibly fit.  The svg root itself stays under the box check.
    if (el.ownerSVGElement) continue;
    const parent = el.parentElement && el.parentElement !== document.body ? el.parentElement : null;
    let hit = false, how = '';
    if (boxOver(el) && !(parent && boxOver(parent))) { hit = true; how = 'box'; }
    else if (contentOver(el) && ![...el.children].some(c => boxOver(c) || contentOver(c))) { hit = true; how = 'content'; }
    if (!hit) continue;
    const r = el.getBoundingClientRect();
    out.push({what: name(el), how, right_px: Math.round(Math.max(r.right, r.left + el.scrollWidth)),
              text: (el.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 70)});
  }
  const doc = document.documentElement.scrollWidth;
  if (!out.length && doc > w + 1) out.push({what: 'document', how: 'scroll', right_px: doc, text: ''});
  return out;
}"""


def _overflow(page):
    """The outermost elements wider than the printable width, measured at
    that width under print media.  The width comes from the page's own
    `@page` left/right margins on A4 (210 mm); without a rule it is the full
    sheet, which is what Chromium prints with `--margin 0mm`."""
    rule = page.evaluate(PAGE_RULE_JS)
    width_px = int(rule["width_mm"] * 96.0 / 25.4)
    page.set_viewport_size({"width": width_px, "height": 1754})
    page.wait_for_timeout(200)
    offenders = page.evaluate(OFFENDERS_JS, width_px)
    return {
        "page_rule": rule["found"],
        "width_mm": rule["width_mm"],
        "width_px": width_px,
        "offenders": offenders[:40],
    }


def _blank(path):
    """A page whose every pixel is the same colour is a rendering failure, not
    a design choice -- a white-on-white page and a crashed renderer look
    identical in a file listing."""
    try:
        from PIL import Image
    except ImportError:
        return False
    with Image.open(path) as im:
        ex = im.convert("L").getextrema()
    return ex[0] == ex[1]


class Refusal(Exception):
    """Bad input: one JSON line, exit 6, never a traceback."""


class _Args(argparse.ArgumentParser):
    def error(self, message):
        raise Refusal(f"press pdf: {message}")


def _host(x):
    """Container paths back to the host paths bin/press mounted (PRESS_PATHMAP), for the stdout line."""
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


ALLOWABLE_EXTRA = [
    "external-resource"
]  # report-level findings an author may wave through (not layout-join)


def _layout_names(v):
    from paged.detect import DETECTORS

    names = [x for x in v.split(",") if x]
    bad = [x for x in names if x not in DETECTORS + ALLOWABLE_EXTRA]
    if bad or not names:
        raise argparse.ArgumentTypeError(
            f"unknown finding id(s) {bad or [v]}; allowable: {DETECTORS + ALLOWABLE_EXTRA}"
        )
    return sorted(set(names))


def _layout(snap, pdf, allowed):
    """The paged-layout pass: join the DOM to the printed PDF, run the detectors.
    -> (layout block, findings, failing findings, join-broken message or None). There is ONE
    findings list: {detector, page, element, measure, severity, allowed},
    severity blocking | fix | info; `layout` keeps mechanism, the join, counts and allowed."""
    from paged import detect, model

    t0 = time.time()
    m = model.build(snap, pdf)
    t1 = time.time()
    try:
        join = model.join_check(m)
    except model.JoinBroken as e:
        block = {
            "mechanism": MECHANISM,
            "status": "join-broken",
            "message": str(e),
            "join": e.stats,
            "allowed": allowed,
            "counts": {"layout-join:blocking": 1},
        }
        whole = {
            "detector": "layout-join",
            "page": 0,
            "element": "the whole document (page 0)",
            "measure": e.stats["shares"],
            "severity": "blocking",
            "allowed": False,
        }
        return block, [whole], [whole], str(e)
    found, stats = detect.run_all(m)
    t2 = time.time()
    for f in found:
        f["allowed"] = f["detector"] in allowed
    # what fails the print: a blocking finding whose detector was not allowed
    failing = [f for f in found if f["severity"] == "blocking" and not f["allowed"]]
    counts = dict(
        sorted(collections.Counter(f"{f['detector']}:{f['severity']}" for f in found).items())
    )
    block = {
        "mechanism": MECHANISM,
        "status": "ok",
        "join": join,
        "counts": counts,
        "allowed": allowed,
        "seconds": {"readback": round(t1 - t0, 1), "detect": round(t2 - t1, 1)},
    }
    return block, found, failing, None


MECHANISM = "tagged-pdf structure /ID node<N> == CDP backendNodeId; (page, MCID) -> glyphs"


def _read(path):
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError as e:
        raise Refusal(f"press pdf: cannot read {path}: {e.strerror}") from e


def _guard_requests(page, doc_url, allowed_external):
    """Every request the page makes, other than the document itself and data: URLs, is recorded;
    it is ABORTED unless `external-resource` is allowed (then it goes through, still recorded).
    A printed page must be self-contained: a sibling stylesheet, a file:// iframe or image, an
    @import or an http fetch would otherwise print content the HTML does not hold."""
    seen = []

    def handle(route):
        url = route.request.url
        if url == doc_url or url.startswith("data:"):
            route.continue_()
            return
        seen.append(url[:300])
        if allowed_external:
            route.continue_()
        else:
            route.abort()

    page.route("**/*", handle)
    return seen


def run(a):
    t0 = time.time()
    if not os.path.isfile(a.html):
        raise Refusal(f"press pdf: no such page: {a.html}")
    # the bytes judged are the bytes read BEFORE the print; a re-read after it must agree
    before = _read(a.html)
    html_sha256 = hashlib.sha256(before).hexdigest()
    os.makedirs(a.out, exist_ok=True)
    # before anything is printed: an earlier print's page images never sit beside this PDF
    # (also under --no-pngs, which writes none of its own)
    _clear_pages(a.out)
    stem = a.name or os.path.splitext(os.path.basename(a.html))[0]
    pdf = os.path.join(a.out, stem + ".pdf")
    allowed = a.allow_layout or []

    from playwright.sync_api import sync_playwright
    from paged import model

    console, failed = [], []
    doc_url = "file://" + os.path.abspath(a.html)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(args=["--font-render-hinting=none"])
        page = browser.new_page(viewport={"width": 1240, "height": 1754})
        page.on("console", lambda m: console.append(f"{m.type}: {m.text}"[:300]))
        page.on("requestfailed", lambda r: failed.append(r.url[:200]))
        external = _guard_requests(page, doc_url, "external-resource" in allowed)
        # a self-contained page has nothing to wait for on the network, but
        # `load` still settles the inlined images and the font decode
        page.goto(doc_url, wait_until="load", timeout=a.timeout_ms)
        page.emulate_media(media="print")
        try:
            page.evaluate("() => document.fonts && document.fonts.ready")
        except Exception:  # pragma: no cover - a page with no font API
            pass
        page.wait_for_timeout(400)
        # the DOM as it prints, taken FIRST: the overflow probe below reads innerText and
        # bounding boxes, which lays out text a closed <details> never paints (measured: 20 hidden
        # paragraphs gained text boxes after it). The layout pass joins this snapshot to the tagged
        # PDF by node id; node ids do not change with a reflow.
        snap = model.snapshot(page)
        title = page.title()
        # a `#fragment` whose id does not exist prints NO annotation at all --
        # Chromium drops it silently, so the PDF audit below cannot see it;
        # ask the DOM before printing
        unresolved = page.evaluate(
            "() => [...document.querySelectorAll('a[href^=\"#\"]')]"
            ".map(a => a.getAttribute('href').slice(1))"
            ".filter(id => id && !document.getElementById(id))"
        )
        overflow = _overflow(page)
        page.set_viewport_size({"width": 1240, "height": 1754})
        page.pdf(
            path=pdf,
            format="A4",
            print_background=True,
            prefer_css_page_size=True,
            margin={"top": a.margin, "right": a.margin, "bottom": a.margin, "left": a.margin},
            tagged=True,
        )
        browser.close()
    t_print = time.time()
    if hashlib.sha256(_read(a.html)).hexdigest() != html_sha256:
        raise Refusal(
            f"press pdf: {a.html}: the file changed during the print; print it again once it is settled"
        )

    if not os.path.exists(pdf) or os.path.getsize(pdf) == 0:
        return emit({"status": "no-pdf", "message": "press pdf: no PDF was written"}, 1)

    links = pdf_links.audit_file(pdf)
    links["html_unresolved"] = sorted(set(unresolved))
    try:
        layout, findings, failing, broken = _layout(snap, pdf, allowed)
    except model.NothingToPrint as e:
        raise Refusal(f"press pdf: {a.html}: {e}") from e
    ext = [
        {
            "detector": "external-resource",
            "page": 0,
            "element": url[:120],
            "measure": {
                "url": url,
                "action": "allowed" if "external-resource" in allowed else "aborted",
            },
            "severity": "blocking",
            "allowed": "external-resource" in allowed,
        }
        for url in external
    ]
    findings = ext + findings
    failing = [f for f in ext if not f["allowed"]] + failing
    for _ in ext:
        key = "external-resource:blocking"
        layout["counts"][key] = layout["counts"].get(key, 0) + 1

    pages, err, blanks = [], None, []
    if not a.no_pngs:
        pages, err = _png_pages(pdf, a.out, a.dpi)
        if err:
            print(f"deck_pdf: {err}", file=sys.stderr)
        else:
            blanks = [os.path.basename(p) for p in pages if _blank(p)]

    layout.setdefault("seconds", {})["print"] = round(t_print - t0, 1)
    aborted = set(external) if "external-resource" not in allowed else set()
    report = {
        "html": os.path.abspath(a.html),
        "html_sha256": html_sha256,
        "pdf": os.path.abspath(pdf),
        "title": title,
        "pdf_bytes": os.path.getsize(pdf),
        "pages": [os.path.basename(p) for p in (pages or [])],
        "page_count": len(pages or []),
        "blank_pages": blanks,
        "dpi": a.dpi,
        "console": console[:40],
        "requests_failed": failed[:40],
        "links": links,
        "overflow": overflow,
        "layout": layout,
        "findings": findings,
    }
    with open(os.path.join(a.out, "pdf_report.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=1, ensure_ascii=False)

    reasons = []
    other_failed = [u for u in failed if u not in aborted]
    if other_failed:
        # a request that failed on its own (an allowed sibling that does not exist)
        reasons.append(f"{len(other_failed)} request(s) failed: {other_failed[:3]}")
    if blanks:
        reasons.append(f"BLANK page(s): {blanks}")
    dead = links["dead_links"] or links["html_unresolved"]
    if links["html_unresolved"]:
        print(
            f"deck_pdf: {len(links['html_unresolved'])} href(s) name an id the page does not "
            f"have (e.g. {links['html_unresolved'][:4]}) -- Chromium prints no link for those",
            file=sys.stderr,
        )
    if links["dead_links"]:
        print(
            f"deck_pdf: {links['dead_links']} internal link(s) point at destinations the PDF "
            f"never defines ({len(links['dead'])} names, e.g. {links['dead'][:4]}) -- the "
            "target has no layout box on any printed page (hidden under print media)",
            file=sys.stderr,
        )
    if dead and not a.allow_dead_links:
        reasons.append("dead or unresolved internal links")
    if overflow["offenders"]:
        print(
            f"deck_pdf: {len(overflow['offenders'])} element(s) wider than the printable "
            f"{overflow['width_mm']:.1f} mm ({overflow['width_px']} px) -- Chromium shrinks EVERY page "
            f"to fit them: {[o['what'] for o in overflow['offenders'][:4]]}",
            file=sys.stderr,
        )
        if not a.allow_overflow:
            reasons.append("elements wider than the printable width")
    for f in failing[:12]:
        print(
            f"deck_pdf: layout {f['severity']} p{f['page']} {f['detector']}: {f['element']}",
            file=sys.stderr,
        )
    if failing and not broken:
        reasons.append(f"{len(failing)} blocking finding(s) not allowed")
    if err:
        reasons.append(err)
    summary = {
        "status": "ok",
        "pdf": pdf,
        "pages": report["page_count"],
        "report": os.path.join(a.out, "pdf_report.json"),
        "links": {
            "named": links["named"],
            "dead": links["dead_links"],
            "unresolved": len(links["html_unresolved"]),
        },
        "findings": {
            "counts": layout["counts"],
            "failing": len(failing),
            "allowed": layout["allowed"],
        },
        "seconds": round(time.time() - t0, 1),
    }
    if broken:
        print(f"deck_pdf: {broken}", file=sys.stderr)
        return emit(dict(summary, status="join-broken", message=broken, reasons=reasons), 7)
    if reasons:
        return emit(dict(summary, status="defects", reasons=reasons), 1)
    return emit(summary, 0)


class _Once(argparse.Action):
    """An option given twice is refused, never silently replaced."""

    def __call__(self, parser, ns, values, option_string=None):
        if getattr(ns, self.dest, None) is not None:
            parser.error(f"{option_string} given twice; give it once, with a comma list")
        setattr(ns, self.dest, values)


def main(argv=None):
    ap = _Args(prog="press pdf", description=__doc__.splitlines()[0])
    ap.add_argument("html")
    ap.add_argument("out")
    ap.add_argument("--name", default=None, action=_Once, help="pdf basename (default: the html's)")
    ap.add_argument(
        "--dpi", type=int, default=None, action=_Once, help="page-PNG raster density (110)"
    )
    ap.add_argument(
        "--margin",
        default=None,
        action=_Once,
        help="printer margin (0mm); the page CSS owns the real margins",
    )
    ap.add_argument("--no-pngs", action="store_true")
    ap.add_argument(
        "--allow-dead-links",
        action="store_true",
        help="report dead internal links without failing the print",
    )
    ap.add_argument(
        "--allow-overflow",
        action="store_true",
        help="report elements wider than the printable width without failing the print",
    )
    ap.add_argument(
        "--allow-layout",
        type=_layout_names,
        default=None,
        action=_Once,
        metavar="DETECTOR,...",
        help="record the named findings (layout detectors, external-resource) without failing the print",
    )
    ap.add_argument("--timeout-ms", type=int, default=None, action=_Once)
    try:
        a = ap.parse_args(argv)
        a.dpi = 110 if a.dpi is None else a.dpi
        a.margin = "0mm" if a.margin is None else a.margin
        a.timeout_ms = 180000 if a.timeout_ms is None else a.timeout_ms
        return run(a)
    except Refusal as e:
        return emit({"status": "bad-argument", "message": str(e)}, 6)
    except Exception as e:  # noqa: BLE001 - a defect in press: say so in the JSON, keep the trace on stderr
        import traceback

        traceback.print_exc()
        return emit(
            {"status": "internal", "message": f"a defect in press: {type(e).__name__}: {e}"}, 7
        )


if __name__ == "__main__":
    raise SystemExit(main())
