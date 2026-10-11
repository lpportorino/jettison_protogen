#!/usr/bin/env python3
"""build_fixtures - two decks that differ ONLY in whether each defect is present.

fixture_bad.html  plants exactly one class of defect per slide.
fixture_good.html is the same deck with each defect removed.

A detector that does not go RED on the bad deck is not a detector; a detector
that does not go GREEN on the good deck is a noise generator.  Both halves are
needed, which is why the two files are generated from one source of truth here.

  python3 build_fixtures.py [out-dir]   # writes fixture_bad.html + fixture_good.html
                                       # beside itself, or into out-dir
"""

import os
import sys
import base64
import mimetypes

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")


def uri(name):
    p = os.path.join(MEDIA, name)
    mime = mimetypes.guess_type(p)[0] or "application/octet-stream"
    with open(p, "rb") as f:
        return "data:{};base64,{}".format(mime, base64.b64encode(f.read()).decode("ascii"))


CSS = """
  %(font_face)s
  *{box-sizing:border-box;margin:0;padding:0}
  html,body{height:100%%}
  body{background:#0F1116;color:#E8EDF4;overflow:hidden;
       font-family:"%(font)s","DejaVu Sans",Arial,sans-serif}
  .deck{height:100vh;width:100vw;position:relative;overflow:hidden}
  .slide{position:absolute;inset:0;display:none;flex-direction:column;gap:14px;
         padding:36px 60px 70px;max-width:1560px;margin:0 auto;overflow-y:auto}
  .slide.on{display:flex}
  h2{font-size:38px;font-weight:760;letter-spacing:-.02em}
  p,li{font-size:17px;line-height:1.55;color:#A8B4C4}
  .kicker{font-size:15px;color:#7A8698}
  .card{background:#171B22;border:1px solid #262C37;border-radius:12px;padding:18px}
  .appendix p{line-height:32px}
  .nav{position:fixed;bottom:10px;left:50%%;transform:translateX(-50%%);display:flex;gap:7px;
       z-index:9;background:rgba(15,17,22,.9);padding:8px 12px;border-radius:999px;
       border:1px solid #262C37}
  .dot{width:8px;height:8px;border-radius:50%%;background:#262C37;border:0;padding:0}
  .dot.on{background:#E0A23A}
  .pageno{position:fixed;top:11px;right:16px;color:#7A8698;font-size:13px;z-index:9}
  /* --- defect scaffolding, shared so good/bad differ only in the values --- */
  .stack{position:relative;height:150px}
  .lay{position:absolute;width:520px;font-size:20px;color:#E8EDF4;background:none}
  .clipbox{width:300px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
           border:1px solid #262C37;border-radius:8px;padding:10px 12px;font-size:17px}
  .coverzone{position:relative;height:120px}
  .covertext{position:absolute;left:0;top:34px;width:640px;font-size:20px;color:#E8EDF4}
  .cover{position:absolute;background:#1E5AA8;border-radius:8px;width:420px;height:52px}
  .photo{background-image:url(%(bright)s);background-size:cover;background-color:#0F1116;
         border-radius:10px;padding:26px 22px;width:760px;height:150px}
  .photo p{font-size:22px;font-weight:700}
  .far{position:absolute;font-size:18px;color:#E8EDF4}
  figure{margin:0;display:flex;flex-direction:column;gap:8px}
  figcaption{font-size:15px;color:#9AA6B6}
  video,img.f{border:1px solid #262C37;border-radius:8px;background:#0A0C11;display:block}
"""

JS = """
  const slides=[...document.querySelectorAll('.slide')];
  const nav=document.getElementById('nav'), pageno=document.getElementById('pageno');
  let i=0;
  slides.forEach((_,k)=>{const d=document.createElement('button');
    d.className='dot';d.setAttribute('aria-label','Go to slide '+(k+1));
    d.onclick=()=>show(k);nav.appendChild(d);});
  const dots=[...nav.children];
  function show(k){
    i=(k+slides.length)%slides.length;
    slides.forEach((s,j)=>s.classList.toggle('on',j===i));
    dots.forEach((d,j)=>d.classList.toggle('on',j===i));
    pageno.textContent=(i+1)+' / '+slides.length;
    slides[i].scrollTop=0;
    slides.forEach((s,j)=>s.querySelectorAll('video').forEach(v=>{
      if(j===i){const p=v.play();if(p&&p.catch)p.catch(()=>{});}else{v.pause();}}));
    if(location.hash!=='#'+(i+1))location.hash='#'+(i+1);
  }
  function go(d){show(i+d)}
  addEventListener('keydown',e=>{if(e.key==='ArrowRight')go(1);if(e.key==='ArrowLeft')go(-1);});
  const h=parseInt((location.hash||'').slice(1));
  show(Number.isFinite(h)&&h>=1?h-1:0);
  addEventListener('hashchange',()=>{const k=parseInt((location.hash||'').slice(1));
    if(Number.isFinite(k)&&k>=1&&k<=slides.length&&k-1!==i)show(k-1);});
"""


def slides(bad, M):
    """The 12 fixture slides.  `bad` picks the defective value at every fork."""
    S = []
    # ---- 1  D1 overflow -----------------------------------------------------
    rows = 64 if bad else 6
    S.append(
        (
            "D1 overflow",
            "Content taller than the viewport",
            '<div class="card">{}</div>'.format(
                "".join(
                    f"<p>Row {k} - filler line that stacks vertically until the "
                    "section can no longer contain it.</p>"
                    for k in range(rows)
                )
            ),
            "",
        )
    )
    # ---- 2  D2 text overlap -------------------------------------------------
    top2 = 30 if bad else 96
    S.append(
        (
            "D2 text overlap",
            "Two text runs whose glyph boxes intersect",
            '<div class="stack">'
            '<p class="lay" style="top:26px;left:0">FIRST RUN - the sentence underneath.</p>'
            f'<p class="lay" style="top:{int(top2)}px;left:24px">SECOND RUN - the sentence on top.</p>'
            "</div>",
            "",
        )
    )
    # ---- 3  D3 truncation ---------------------------------------------------
    ws3 = "nowrap" if bad else "normal"
    ov3 = "hidden" if bad else "visible"
    S.append(
        (
            "D3 truncation",
            "text-overflow:ellipsis silently deleting text",
            f'<div class="clipbox" style="white-space:{ws3};overflow:{ov3}">'
            "This sentence is far longer than the three hundred pixel box that holds it, so the "
            "browser throws the tail away.</div>",
            "",
        )
    )
    # ---- 4  D4 occlusion ----------------------------------------------------
    covtop = 30 if bad else 96
    S.append(
        (
            "D4 occlusion",
            "An opaque panel painted on top of body text",
            '<div class="coverzone">'
            '<p class="covertext">This sentence is supposed to be readable by the audience.</p>'
            f'<div class="cover" style="left:10px;top:{int(covtop)}px;z-index:5"></div>'
            "</div>",
            "",
        )
    )
    # ---- 5  D5 small text ---------------------------------------------------
    fs5 = "8px" if bad else "18px"
    S.append(
        (
            "D5 small text",
            "Text below the legibility floor at 1920x1080",
            f'<p style="font-size:{fs5}">Footnote rendered at {fs5} - at presentation distance this '
            "is a grey smear.</p>",
            "",
        )
    )
    # ---- 6  D9 contrast, flat colour ---------------------------------------
    col6 = "#2B313B" if bad else "#E8EDF4"
    S.append(
        (
            "D9 contrast (flat)",
            "Foreground against a solid panel",
            f'<div class="card"><p style="color:{col6};font-size:17px">Body copy at {col6} on the '
            "panel colour #171B22.</p></div>",
            "",
        )
    )
    # ---- 7  D9 contrast over an IMAGE --------------------------------------
    # The case axe-core returns as `incomplete`: the CSS walk finds a
    # background-image and declines.  Only the pixel plate can judge it.
    col7 = "#FFFFFF" if bad else "#101418"
    S.append(
        (
            "D9 contrast (over image)",
            "White-on-white over a bright photo",
            f'<div class="photo"><p style="color:{col7}">Caption burned onto the photograph.</p></div>',
            "",
        )
    )
    # ---- 8  D7 images -------------------------------------------------------
    # The good deck shows the alt image at its native width (no upscale) and
    # inlines nothing twice; the bad deck upscales the 64x36 image 14x and
    # inlines the slide-7 photograph a SECOND time as a separate data: URI
    # (D7 duplicate bytes is measured on the FILE, so the same payload in a
    # CSS background and an <img> counts, and only the bad deck carries it).
    img8 = M["broken"] if bad else M["native"]
    up8, w8 = (M["small"], 900) if bad else (M["alt"], 240)
    dup8 = (
        (
            '<figure><img class="f" src="{}" alt="duplicate probe" width="320">'
            "<figcaption>the slide-7 photograph, inlined again</figcaption></figure>".format(
                M["bright"]
            )
        )
        if bad
        else ""
    )
    S.append(
        (
            "D7 images",
            "A decode failure, a heavy upscale and a duplicated payload",
            f'<figure><img class="f" src="{img8}" alt="decode probe" width="320">'
            "<figcaption>left: should decode</figcaption></figure>"
            f'<figure><img class="f" src="{up8}" alt="upscale probe" style="width:{int(w8)}px">'
            f"<figcaption>right: rendered at {int(w8)}px</figcaption></figure>{dup8}",
            "",
        )
    )
    # ---- 9  D8 video --------------------------------------------------------
    if bad:
        vids = [
            ("h264.mp4", "H.264 / avc1 - real, valid, undecodable HERE"),
            ("av1_truncated.mp4", "AV1 moov intact, sample data removed"),
            ("av1_corrupt.mp4", "AV1 moov intact, sample data scrambled"),
            ("garbage.mp4", "random bytes labelled video/mp4 - unsniffable"),
        ]
    else:
        vids = [("av1.mp4", "AV1 / av01 - decodes here"), ("vp9.mp4", "VP9 / vp09 - decodes here")]
    S.append(
        (
            "D8 video",
            "Codec-unsupported-HERE vs genuinely broken",
            "".join(
                f'<figure><video src="{M[v]}" autoplay muted loop playsinline '
                f'preload="metadata" width="300"></video><figcaption>{c}</figcaption></figure>'
                for v, c in vids
            ),
            "",
        )
    )
    # ---- 10 D10 external ----------------------------------------------------
    if bad:
        body10 = (
            "<p>The image below is fetched from the network:</p>"
            '<img class="f" src="https://upload.wikimedia.org/deck_qa_probe.png" '
            'alt="external" width="240">'
            '<img class="f" src="' + M["small"] + '" alt="a second copy of the '
            'slide-8 asset - same bytes, inlined twice" width="200">'
            '<link rel="stylesheet" href="https://cdn.example.com/theme.css">'
        )
    else:
        body10 = (
            "<p>The image below is inlined:</p>"
            '<img class="f" src="' + M["alt"] + '" alt="inlined" width="240">'
        )
    S.append(("D10 self-containment", "An asset that leaves the file", body10, ""))
    # ---- 11 D12 offscreen ---------------------------------------------------
    left11 = -3200 if bad else 40
    S.append(
        (
            "D12 offscreen",
            "An element parked outside the viewport",
            f'<div class="coverzone"><p class="far" style="left:{int(left11)}px;top:40px">'
            f"This caption is {'off the left edge' if bad else 'on screen'}.</p></div>",
            "",
        )
    )
    # ---- 12 opt-out ---------------------------------------------------------
    # A deliberately tall slide that DECLARES itself scrollable: proves the
    # opt-out demotes D1 to info instead of the detector simply being wrong.
    S.append(
        (
            "Opt-out",
            "A tall appendix that opts in to scrolling",
            '<div class="card appendix">{}</div>'.format(
                "".join(f"<p>Appendix row {k}.</p>" for k in range(60))
            ),
            " data-qa-scroll",
        )
    )
    return S


def build(bad, path, font):
    M = {
        k: uri(v)
        for k, v in {
            "h264": "h264.mp4",
            "av1": "av1.mp4",
            "vp9": "vp9.mp4",
            "av1_truncated": "av1_truncated.mp4",
            "av1_corrupt": "av1_corrupt.mp4",
            "garbage": "garbage.mp4",
            "small": "small.png",
            "native": "native.png",
            "broken": "broken.png",
            "alt": "alt.png",
            "bright": "bright.png",
        }.items()
    }
    M["h264.mp4"], M["av1.mp4"], M["vp9.mp4"] = M["h264"], M["av1"], M["vp9"]
    M["av1_truncated.mp4"], M["garbage.mp4"] = M["av1_truncated"], M["garbage"]
    M["av1_corrupt.mp4"] = M["av1_corrupt"]
    font_face = ""
    if not bad:
        font_path = os.path.join(HERE, "..", "resources", "press", "deck", "Inter.var.woff2")
        with open(font_path, "rb") as font_file:
            payload = base64.b64encode(font_file.read()).decode("ascii")
        font_face = (
            '@font-face{font-family:"Inter";font-style:normal;font-weight:100 900;'
            f'src:url(data:font/woff2;base64,{payload}) format("woff2");font-display:block}}'
        )
    secs = []
    for k, (kick, title, body, attrs) in enumerate(slides(bad, M)):
        secs.append(
            '<section class="slide{}"{}>'
            '<p class="kicker">{}</p><h2>{}</h2><div class="body">{}</div></section>'.format(
                " on" if k == 0 else "", attrs, kick, title, body
            )
        )
    crash = (
        "\n  (function(){ throw new Error('deck_qa fixture: deliberate uncaught exception'); })();"
        if bad
        else ""
    )
    html = (
        '<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="UTF-8">\n'
        "<title>deck_qa fixture - {}</title>\n<style>{}</style>\n</head>\n<body>\n"
        '<div class="deck">\n{}\n</div>\n'
        '<div class="nav" id="nav"></div><div class="pageno" id="pageno"></div>\n'
        "<script>{}{}</script>\n</body>\n</html>\n".format(
            "defects present" if bad else "defects removed",
            CSS % {"font": font, "font_face": font_face, "bright": M["bright"]},
            "\n".join(secs),
            JS,
            crash,
        )
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    return len(html)


PAGED_CSS = (
    '@page{size:A4;margin:18mm} body{font:11pt "DejaVu Sans",sans-serif}'
    " .pagebreak{break-before:page;page-break-before:always}"
    " .noprint{} @media print{.noprint{display:none}}"
)


def build_paged(bad, path):
    """A two-page paged document for deck_pdf's link audit, in both directions:
    GOOD links a mark on page 1 to an anchor that prints on page 2; BAD links
    the same mark to an anchor hidden under print media AND one to an id that
    does not exist -- the two ways a printed link comes out dead -- and carries
    an element wider than the printable width."""
    target_cls = ' class="noprint"' if bad else ""
    ghost = '<sup><a href="#nowhere">2</a></sup>' if bad else ""
    # and one element wider than the printable width: an unbreakable run the
    # printer must shrink every page to fit (a wide report table)
    ghost += (
        '<div class="toowide" style="white-space:nowrap">' + "UNBREAKABLE " * 40 + "</div>"
        if bad
        else ""
    )
    # BOTH pages carry an inline svg whose one text line is far longer than the
    # printable width in viewBox units (about 1400 of 1600 units, against a
    # 657 px printable width) yet fits on the page once the viewBox is scaled
    # -- the shape every figure-bearing document has.  It must never be
    # reported: an SVG text's scrollWidth is in user units.  On the GOOD page it
    # is the control that the check ignores svg content; on the BAD page it
    # proves the exemption hides no real offender.  The line is sized to PRINT
    # whole and at 7.4 pt on paper (24 units x 493 pt / 1600 units), so the
    # layout pass (figure text under the floor, text the svg root clips) finds
    # nothing in it either.
    ghost += (
        '<svg viewBox="0 0 1600 40" style="width:100%%" xmlns="http://www.w3.org/2000/svg">'
        '<text x="8" y="30" font-size="24">%s</text></svg>' % ("svg-line " * 12)
    )
    html = (
        '<!DOCTYPE html>\n<html lang="en"><head><meta charset="UTF-8">'
        "<title>deck_pdf fixture - {}</title><style>{}</style></head><body>"
        '<h1>A paged page</h1><p>A number, 41.2 \u00b0C<sup><a href="#src-S1">1</a></sup>,'
        " with its source mark.{}</p>"
        '<div class="pagebreak"><h2>Sources</h2>'
        '<div{} id="src-S1">S1 the record the number came from</div></div>'
        "</body></html>\n".format(
            "dead links" if bad else "links resolve", PAGED_CSS, ghost, target_cls
        )
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    return len(html)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else HERE
    os.makedirs(out, exist_ok=True)
    for bad, name in ((True, "fixture_bad.html"), (False, "fixture_good.html")):
        p = os.path.join(out, name)
        n = build(bad, p, "PressMissingFont" if bad else "Inter")
        print(f"{str(name):<22} {n / 1024:8.1f} KB")
    for bad, name in ((True, "paged_bad.html"), (False, "paged_good.html")):
        p = os.path.join(out, name)
        n = build_paged(bad, p)
        print(f"{str(name):<22} {n / 1024:8.1f} KB")
