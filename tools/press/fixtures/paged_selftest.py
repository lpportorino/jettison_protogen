"""The paged-layout pass's both-directions proof (`press selftest` runs it inside the image, no network;
`paged_launcher_selftest.sh` then drives `press pdf` and `press pdfcheck` through bin/press itself).
usage: paged_selftest.py <out-dir>   (the tool tree at /work)

Every expectation is PINNED in paged_fixtures.CASES / PDFCHECK_CASES -- the exact set of (detector,
severity) findings and the exit code per page -- and never computed from the run under test.
  fixtures   every planted page printed through deck_pdf exactly as `press pdf` prints it: findings and
             exit code equal the pinned ones, for every bad page and every good twin; every detector
             (and external-resource) is red on at least one page
  control    the pinning bites: a copy of the tool with one detector's severity demoted (blocking ->
             fix) is run on that detector's page and the comparison must FAIL
  report     ONE findings list, severities blocking | fix | info with `allowed`;
             `layout` records join and detector results with its measured duration; html_sha256 equals an
             independent hash of the page bytes
  allow      --allow-layout waves through exactly the named findings (and lets an allowed external
             request through); twice, or an unknown name, is refused (6)
  input      a page with nothing to print (empty body, image only, empty file) and a file that changes
             during the print are refused (6), never reported as a broken join
  join       the join guard: a tagged print passes; an UNTAGGED print, the same print with every
             structure id shifted by one, and a DOM paired with another page's PDF are refused
  tightened  the two tightened detectors: the good twin is quiet, and red with the tightening undone
  pdfcheck   the PDF-only detectors on the same prints, pinned the same way; a corrupt, a truncated and
             a text-less PDF are refused (6) with no traceback
  stale      a long page, then a short one, printed into ONE directory: the page images left are
             exactly the short PDF's pages (counted from the PDF), files that are not the printer's
             own page images are kept, and a --no-pngs print leaves no page image behind"""

import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import traceback

import paged_fixtures
from paged import detect, model, pdfonly

OUT = pathlib.Path(sys.argv[1])
ENV = dict(os.environ, PYTHONPATH="/work/press")
FX = OUT / "pages"
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
            f"{type(e).__name__}: {e}\n{traceback.format_exc()[-800:]}",
        )


def dq_pdf(html, out, *extra, tool="/work/press"):
    r = subprocess.run(
        [sys.executable, "-m", "deck_pdf", str(html), str(out), "--no-pngs", *extra],
        capture_output=True,
        text=True,
        env=dict(ENV, PYTHONPATH=tool),
    )
    lines = r.stdout.strip().splitlines()
    try:
        line = json.loads(lines[-1]) if lines else {}
    except json.JSONDecodeError:
        line = {"raw": r.stdout[-300:]}
    rep = pathlib.Path(out) / "pdf_report.json"
    return (
        r.returncode,
        line,
        (json.loads(rep.read_text()) if rep.exists() else None),
        r.stderr,
        len(lines),
    )


def got(rep):
    return {(f["detector"], f["severity"]) for f in rep["findings"]} if rep else None


def verdict(name, var, rc, rep, err, nlines):
    want, want_rc = paged_fixtures.CASES[name][var]
    have = got(rep)
    ok = have == want and rc == want_rc and "Traceback" not in err and nlines == 1
    return ok, f"rc={rc} want {want_rc}; findings {sorted(have or [])} want {sorted(want)}"


runs = {}


def fixtures():
    paged_fixtures.write(FX)
    red = set()
    for name, vs in sorted(paged_fixtures.CASES.items()):
        for var in ("bad", "good"):
            if var not in vs:
                continue
            out = OUT / "prints" / f"{name}.{var}"
            rc, line, rep, err, n = dq_pdf(FX / f"{name}.{var}.html", out)
            runs[(name, var)] = (rc, rep, out)
            ok, detail = verdict(name, var, rc, rep, err, n)
            expect(f"{name} {var}: findings and exit code as pinned", ok, detail)
            if var == "bad" and ok:
                red |= {d for d, s in vs["bad"][0] if s != "info"}
    missing = sorted(set(detect.DETECTORS + ["external-resource"]) - red)
    expect(
        "every detector (and external-resource) is red on at least one planted page",
        not missing,
        f"never red: {missing}",
    )


def control():
    """Demote heading-stranded (blocking -> fix) in a COPY of the tool: the pinned comparison of its
    page must then fail on both the findings and the exit code."""
    tool = OUT / "mutant"
    if tool.exists():
        shutil.rmtree(tool)
    shutil.copytree("/work/press", tool)
    p = tool / "paged" / "detect.py"
    src = p.read_text()
    needle = '"next": D.label(nb),\n                    },\n                    "blocking",'
    expect(
        "control: the demotion applies to the copy",
        src.count(needle) == 1,
        f"{src.count(needle)} sites",
    )
    p.write_text(src.replace(needle, needle.replace('"blocking"', '"fix"')))
    rc, line, rep, err, n = dq_pdf(
        FX / "heading_stranded.bad.html", OUT / "prints" / "mutant", tool=str(tool)
    )
    ok, detail = verdict("heading_stranded", "bad", rc, rep, err, n)
    expect(
        "control: a demoted severity turns the pinned comparison red",
        not ok and got(rep) == {("heading-stranded", "fix")} and rc == 0,
        detail,
    )


def report():
    reps = [r for (_, _), (_, r, _) in runs.items() if r]
    words = {f["severity"] for r in reps for f in r["findings"]}
    shape = all(
        sorted(f) == ["allowed", "detector", "element", "measure", "page", "severity"]
        and isinstance(f["page"], int)
        and f["allowed"] is False
        for r in reps
        for f in r["findings"]
    )
    expect(
        "report: ONE findings list, severities blocking | fix | info, each with allowed",
        words <= {"blocking", "fix", "info"} and words >= {"blocking", "fix", "info"} and shape,
        f"words {sorted(words)}",
    )
    expect(
        "report: layout holds mechanism, join, counts, allowed (no second list)",
        bool(reps)
        and all(
            set(r["layout"]) == {"mechanism", "status", "join", "counts", "allowed", "seconds"}
            for r in reps
        ),
        str(sorted(reps[0]["layout"])) if reps else "",
    )
    expect(
        "report: counts use the same three words",
        all(
            k.rsplit(":", 1)[1] in ("blocking", "fix", "info")
            for r in reps
            for k in r["layout"]["counts"]
        ),
        "",
    )
    same = [
        rep["html_sha256"] == hashlib.sha256((FX / f"{n}.{v}.html").read_bytes()).hexdigest()
        for (n, v), (_, rep, _) in runs.items()
        if rep
    ]
    expect(
        "report: html_sha256 is the hash of the page bytes",
        bool(same) and all(same),
        f"{sum(same)}/{len(same)}",
    )
    expect(
        "report: layout.allowed is [] without the flag",
        all(r["layout"]["allowed"] == [] for r in reps),
        "",
    )


def allow():
    html = FX / "intro_stranded.bad.html"
    rc, line, rep, err, _ = dq_pdf(
        html, OUT / "prints" / "allow", "--allow-layout", "intro-stranded,heading-stranded"
    )
    tf = [x for x in rep["findings"] if x["detector"] == "intro-stranded"] if rep else []
    expect(
        "allow: --allow-layout intro-stranded prints its blocking finding with exit 0, marked allowed",
        rc == 0
        and tf
        and all(x["allowed"] and x["severity"] == "blocking" for x in tf)
        and rep["layout"]["allowed"] == ["heading-stranded", "intro-stranded"],
        f"rc={rc} {rep and rep['layout']['allowed']}",
    )
    rc, line, rep, err, _ = dq_pdf(
        html, OUT / "prints" / "allow2", "--allow-layout", "heading-stranded"
    )
    expect("allow: allowing ANOTHER detector does not excuse intro-stranded", rc == 1, f"rc={rc}")
    for label, args in (
        ("an unknown name", ["--allow-layout", "no-such-detector"]),
        ("a name with a trailing newline", ["--allow-layout", "intro-stranded\n"]),
        (
            "the option given twice",
            ["--allow-layout", "intro-stranded", "--allow-layout", "runt-page"],
        ),
        ("layout-join (a broken join is never waved through)", ["--allow-layout", "layout-join"]),
    ):
        rc, line, rep, err, _ = dq_pdf(html, OUT / "prints" / "allowx", *args)
        expect(
            f"allow: {label} -> 6, no traceback",
            rc == 6 and "Traceback" not in err,
            f"rc={rc} {line.get('message', '')[:90]}",
        )
    ext = FX / "external.bad.html"
    rc, line, rep, err, _ = dq_pdf(
        ext, OUT / "prints" / "allowext", "--allow-layout", "external-resource"
    )
    fs = [x for x in rep["findings"] if x["detector"] == "external-resource"] if rep else []
    side = [x for x in fs if x["element"].endswith("/side.css")]
    expect(
        "allow: --allow-layout external-resource lets the requests through, each recorded as allowed",
        fs and all(x["allowed"] and x["measure"]["action"] == "allowed" for x in fs) and side,
        f"rc={rc} {len(fs)} requests",
    )
    rc, line, rep, err, _ = dq_pdf(ext, OUT / "prints" / "ext-abort")
    fs = [x for x in rep["findings"] if x["detector"] == "external-resource"] if rep else []
    urls = sorted(x["measure"]["url"].rsplit("/", 1)[-1] for x in fs)
    expect(
        "external: every outside request (sibling css, @import, iframe, image) is aborted and named",
        rc == 1
        and all(x["measure"]["action"] == "aborted" for x in fs)
        and {"side.css", "README.md", "hostname"} <= set(urls),
        f"{urls}",
    )


def bad_input():
    d = OUT / "nothing"
    d.mkdir(parents=True, exist_ok=True)
    png = (
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGA"
        "hKmMIQAAAABJRU5ErkJggg=="
    )
    for name, body in (
        ("empty_body", "<!doctype html><html><head><title>e</title></head><body></body></html>"),
        (
            "image_only",
            f'<!doctype html><html><body><img src="{png}" width="200" height="200"></body></html>',
        ),
        ("zero", ""),
    ):
        (d / f"{name}.html").write_text(body)
        rc, line, rep, err, _ = dq_pdf(d / f"{name}.html", d / f"out-{name}")
        expect(
            f'input: {name} -> 6 "nothing to print", not a broken join',
            rc == 6 and "paints" in line.get("message", "") and "Traceback" not in err,
            f"rc={rc} {line.get('message', '')[:100]}",
        )
    # the page changes between the read and the print: model.snapshot (it runs just before page.pdf)
    # is wrapped in-process to append to the file
    import deck_pdf

    page = d / "moving.html"
    page.write_text((FX / "intro_stranded.good.html").read_text())
    real = model.snapshot

    def moving(p):
        with open(page, "a") as fh:
            fh.write("<!-- changed -->")
        return real(p)

    model.snapshot = moving
    try:
        import contextlib
        import io

        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = deck_pdf.main([str(page), str(d / "out-moving"), "--no-pngs"])
    finally:
        model.snapshot = real
    line = json.loads(buf.getvalue().strip().splitlines()[-1])
    expect(
        "input: a file that changes during the print -> 6",
        rc == 6 and "changed during the print" in line.get("message", ""),
        f"rc={rc} {line}",
    )


def _print(html, pdf, tagged):
    from playwright.sync_api import sync_playwright

    with sync_playwright() as pw:
        b = pw.chromium.launch(args=["--font-render-hinting=none"])
        p = b.new_page(viewport={"width": 1240, "height": 1754})
        p.goto("file://" + str(html), wait_until="load")
        p.emulate_media(media="print")
        p.evaluate("() => document.fonts && document.fonts.ready")
        p.wait_for_timeout(400)
        snap = model.snapshot(p)
        p.pdf(
            path=str(pdf),
            format="A4",
            print_background=True,
            prefer_css_page_size=True,
            margin={k: "0mm" for k in ("top", "right", "bottom", "left")},
            tagged=tagged,
        )
        b.close()
    return snap


def _refused(m):
    try:
        model.join_check(m)
        return None
    except model.JoinBroken as e:
        return e.stats["shares"]


def join():
    d = OUT / "join"
    d.mkdir(parents=True, exist_ok=True)
    snap = _print(FX / "intro_stranded.bad.html", d / "tagged.pdf", True)
    m = model.build(snap, d / "tagged.pdf")
    expect("join: a tagged print passes the guard", _refused(m) is None, "")
    usnap = _print(FX / "intro_stranded.bad.html", d / "untagged.pdf", False)
    expect(
        "join: an UNTAGGED print is refused",
        _refused(model.build(usnap, d / "untagged.pdf")) is not None,
        "",
    )
    shifted = dict(
        m,
        tags={k + 1 if k >= 0 else k: v for k, v in m["tags"].items()},
        frag={k + 1 if k >= 0 else k: v for k, v in m["frag"].items()},
    )
    sh = _refused(shifted)
    expect(
        "join: structure ids shifted by one are refused, by glyphs_agree as well as struct_typed",
        sh is not None and sh["glyphs_agree"] < 0.5 and sh.get("struct_typed", 1) < 0.5,
        str(sh),
    )
    osnap = _print(FX / "runt_page.bad.html", d / "other.pdf", True)
    pair = model.build(osnap, d / "tagged.pdf")
    pr = _refused(pair)
    expect(
        "join: a DOM paired with ANOTHER page's PDF is refused, glyphs_agree low",
        pr is not None and pr["glyphs_agree"] < 0.5,
        str(pr),
    )
    from deck_pdf import _layout

    block, found, failing, broken = _layout(usnap, str(d / "untagged.pdf"), [])
    expect(
        'join: press pdf\'s layout pass turns a broken join into one blocking finding, not "no findings"',
        broken
        and block["status"] == "join-broken"
        and found
        and found[0]["severity"] == "blocking"
        and found[0]["detector"] == "layout-join",
        f"status={block['status']}",
    )


def tightened():
    d = OUT / "tight"
    d.mkdir(parents=True, exist_ok=True)
    for name, det, undo in (
        ("starved_list", "starved-column", ("ITEM_SEPARATORS", set())),
        ("split_list_stub", "split-list", ("STUB_LINES", 10**6)),
    ):
        snap = _print(FX / f"{name}.good.html", d / f"{name}.pdf", True)
        m = model.build(snap, d / f"{name}.pdf")

        def loud(m=m, det=det):
            return [
                f for f in detect.run_all(m)[0] if f["detector"] == det and f["severity"] != "info"
            ]

        now = loud()
        keep = getattr(detect, undo[0])
        setattr(detect, undo[0], undo[1])
        try:
            before = loud()
        finally:
            setattr(detect, undo[0], keep)
        expect(
            f"tightened {det}: {name}.good is quiet, and red with {undo[0]} undone",
            not now and bool(before),
            f"now={len(now)} untightened={len(before)}",
        )


def pdfcheck():
    margins = "22,21,24,21"  # paged.css's @page margins
    red = set()
    for name, vs in sorted(paged_fixtures.PDFCHECK_CASES.items()):
        for var in ("bad", "good"):
            want, want_rc = vs[var]
            _, _, out = runs[(name, var)]
            pdf = next(out.glob("*.pdf"))
            rj = out / "pdfcheck.json"
            r = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "paged.pdfonly",
                    str(pdf),
                    "--margins",
                    margins,
                    "--json",
                    str(rj),
                ],
                capture_output=True,
                text=True,
                env=ENV,
            )
            rep = json.loads(rj.read_text()) if rj.exists() else {"findings": []}
            have = {(f["detector"], f["severity"]) for f in rep["findings"]}
            ok = have == want and r.returncode == want_rc
            expect(
                f"pdfcheck {name} {var}: findings and exit code as pinned",
                ok,
                f"rc={r.returncode} want {want_rc}; {sorted(have)} want {sorted(want)}",
            )
            if var == "bad" and ok:
                red |= {d for d, s in want if s != "info"}
    expect(
        "pdfcheck: every detector it runs is red on a plant",
        red == set(pdfonly.RUNS),
        f"never red: {sorted(set(pdfonly.RUNS) - red)}",
    )
    rep = json.loads((runs[("split_table", "bad")][2] / "pdfcheck.json").read_text())
    expect(
        "pdfcheck: the report names every detector that did not run, with why",
        sorted(rep["not_run"]) == sorted(set(detect.DETECTORS) - set(pdfonly.RUNS))
        and all(rep["not_run"].values()),
        str(sorted(rep["not_run"])),
    )
    d = OUT / "pdfbad"
    d.mkdir(exist_ok=True)
    good = next(runs[("split_table", "bad")][2].glob("*.pdf")).read_bytes()
    (d / "cut.pdf").write_bytes(good[: len(good) // 2])
    (d / "trunc.pdf").write_bytes(good[:40])
    (d / "garbage.pdf").write_bytes(b"%PDF-1.7\n" + bytes(range(256)) * 8)
    _print_image_pdf(d / "notext.pdf")
    for name, needle in (
        ("cut", "not a readable PDF"),
        ("trunc", "not a readable PDF"),
        ("garbage", "not a readable PDF"),
        ("notext", "no text layer"),
    ):
        r = subprocess.run(
            [sys.executable, "-m", "paged.pdfonly", str(d / f"{name}.pdf"), "--margins", margins],
            capture_output=True,
            text=True,
            env=ENV,
        )
        line = r.stdout.strip().splitlines()
        msg = json.loads(line[-1]).get("message", "") if line else ""
        expect(
            f"pdfcheck: a {name} PDF -> 6 saying why, one JSON line, no traceback",
            r.returncode == 6 and needle in msg and len(line) == 1 and "Traceback" not in r.stderr,
            f"rc={r.returncode} {msg[:100]}",
        )
    for label, args in (
        ("no --margins", [str(d / "cut.pdf")]),
        ("a file that is not a PDF", [str(FX / "split_table.bad.html"), "--margins", margins]),
    ):
        r = subprocess.run(
            [sys.executable, "-m", "paged.pdfonly", *args], capture_output=True, text=True, env=ENV
        )
        expect(
            f"pdfcheck: {label} -> 6, no traceback",
            r.returncode == 6 and "Traceback" not in r.stderr,
            f"rc={r.returncode}",
        )


def _pdf_pages(pdf):
    """The page count read from the PDF itself (pdfplumber), never from the PNGs under test."""
    import pdfplumber

    with pdfplumber.open(str(pdf)) as doc:
        return len(doc.pages)


def stale_pages():
    """A long page printed, then a short one into the SAME directory: every `page-NN.png` left there
    must be a page of the short PDF -- the leftovers of the long print are removed, files that are
    not the printer's own page images are kept, and the report's page count is the PDF's. A
    `--no-pngs` print into that directory leaves no page image at all."""
    d = OUT / "stale"
    d.mkdir(parents=True, exist_ok=True)
    para = (
        "<p>" + "A line of plain text that fills the measure of the page and wraps. " * 12 + "</p>"
    )
    head = (
        "<!doctype html><html><head><title>t</title><style>@page { size: A4; margin: 22mm 21mm 24mm 21mm }"
        " body { font: 11pt serif }</style></head><body>"
    )
    (d / "long.html").write_text(head + para * 40 + "</body></html>")
    (d / "short.html").write_text(head + para + "</body></html>")
    out = d / "out"
    out.mkdir(exist_ok=True)
    keep = ["page-notes.png", "cover-page-01.png", "page-01.txt", "page-01.png.keep"]
    for k in keep:
        (out / k).write_text("not a page image of the printer")

    def show(html, *extra):
        r = subprocess.run(
            [sys.executable, "-m", "deck_pdf", str(html), str(out), "--name", "doc", *extra],
            capture_output=True,
            text=True,
            env=ENV,
        )
        rep = (
            json.loads((out / "pdf_report.json").read_text())
            if (out / "pdf_report.json").exists()
            else {}
        )
        imgs = sorted(p.name for p in out.glob("page-*.png") if p.name not in keep)
        return r.returncode, rep, imgs

    rc, rep, imgs = show(d / "long.html")
    n_long = _pdf_pages(out / "doc.pdf")
    expect(
        "stale: the long page prints more than one page, one image per page",
        rc in (0, 1) and n_long > 2 and len(imgs) == n_long,
        f"rc={rc} pdf={n_long} images={len(imgs)}",
    )
    rc, rep, imgs = show(d / "short.html")
    n_short = _pdf_pages(out / "doc.pdf")
    expect(
        "stale: after a SHORT print into the same directory, the page images are exactly its pages",
        rc in (0, 1)
        and n_short < n_long
        and len(imgs) == n_short
        and rep.get("page_count") == n_short
        and rep.get("pages") == imgs,
        f"rc={rc} pdf={n_short} images={len(imgs)} report={rep.get('page_count')}",
    )
    expect(
        "stale: files that are not the printer's page images are kept",
        all((out / k).exists() for k in keep),
        str([k for k in keep if not (out / k).exists()]),
    )
    rc, rep, imgs = show(d / "short.html", "--no-pngs")
    expect(
        "stale: a --no-pngs print leaves no page image of an earlier print",
        rc in (0, 1) and imgs == [] and rep.get("page_count") == 0,
        f"rc={rc} images={imgs}",
    )


def _print_image_pdf(path):
    """A PDF with pages and ink but no text layer (what a scan looks like to the reader)."""
    from playwright.sync_api import sync_playwright

    with sync_playwright() as pw:
        b = pw.chromium.launch()
        p = b.new_page()
        p.set_content(
            '<!doctype html><html><body><div style="width:150mm;height:80mm;background:#1f3a5f"></div></body></html>'
        )
        p.pdf(path=str(path), format="A4", print_background=True)
        b.close()


for s in (fixtures, control, report, allow, bad_input, join, tightened, pdfcheck, stale_pages):
    section(s)
print(
    f"paged selftest: {len(passes)} passed, {len(fails)} failed" + (f": {fails}" if fails else "")
)
sys.exit(1 if fails else 0)
