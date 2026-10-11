"""deck_qa_js — the page-side probes for deck_qa.py.

Every string here is evaluated inside the deck's own page.  They are kept in one
module so each detector has a named page-side probe.
"""

# ---------------------------------------------------------------------------- navigation
# Prefer a deck's optional global show() function; otherwise use its hash route.
GOTO = """([n]) => {
  if (typeof window.show === 'function') { window.show(n - 1); return 'show'; }
  location.hash = '#' + n; return 'hash';
}"""

# ---------------------------------------------------------------------------- settle
# Gates, in the order a defect would otherwise slip through:
#   fonts.ready   - metrics are wrong until the inlined @font-face is applied, so
#                   every geometric number below depends on this one.
#   img.decode()  - a lazy image inside a
#                   display:none slide is not fetched until it is shown, and
#                   .complete is true for an image that failed.  decode() is the
#                   only gate that both forces the load and reports failure.
#   video         - preload="metadata" means readyState can be 0 forever until
#                   something asks.  load() + a bounded wait for loadedmetadata
#                   OR error turns "unknown" into a verdict.
#   2x rAF        - one frame to apply the class change, one to paint it.
SETTLE = """async ([videoWaitMs]) => {
  try { await document.fonts.ready; } catch (e) {}
  const s = document.querySelector('.slide.on');
  if (!s) return {imgs: 0, img_failed: 0, videos: 0};
  const imgs = [...s.querySelectorAll('img')];
  let img_failed = 0;
  await Promise.all(imgs.map(async im => {
    try { await im.decode(); } catch (e) { img_failed++; }
  }));
  const vids = [...s.querySelectorAll('video')];
  await Promise.all(vids.map(v => new Promise(res => {
    if (v.readyState >= 1 || v.error) return res();
    let done = false;
    const fin = () => { if (!done) { done = true; res(); } };
    v.addEventListener('loadedmetadata', fin, {once: true});
    v.addEventListener('error', fin, {once: true});
    setTimeout(fin, videoWaitMs);
    try { v.load(); } catch (e) { fin(); }
  })));
  await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  return {imgs: imgs.length, img_failed, videos: vids.length};
}"""

# ---------------------------------------------------------------------------- shared page helpers
# Installed once per page; every probe below assumes window.__press exists.
HELPERS = """() => {
  const D = {};
  D.px = v => parseFloat(v) || 0;
  D.head = (t, n) => (t || '').replace(/\\s+/g, ' ').trim().slice(0, n || 70);
  D.sel = el => {
    if (!el || !el.tagName) return String(el);
    let s = el.tagName.toLowerCase();
    if (el.id) s += '#' + el.id;
    const c = (el.getAttribute && el.getAttribute('class') || '').trim();
    if (c) s += '.' + c.split(/\\s+/).slice(0, 3).join('.');
    return s;
  };
  D.rect = r => ({x: Math.round(r.left), y: Math.round(r.top),
                  w: Math.round(r.width), h: Math.round(r.height)});
  // the element a developer would actually edit: the nearest non-inline ancestor
  D.block = el => { for (let e = el; e && e.nodeType === 1; e = e.parentElement) {
      const d = getComputedStyle(e).display;
      if (d && !d.startsWith('inline')) return e; }
    return el; };
  // effective opacity: a chain of 0.5s is not 0.5
  D.opacity = el => { let o = 1;
    for (let e = el; e && e.nodeType === 1; e = e.parentElement) o *= parseFloat(getComputedStyle(e).opacity);
    return o; };
  D.hidden = el => {
    for (let e = el; e && e.nodeType === 1; e = e.parentElement) {
      const c = getComputedStyle(e);
      if (c.display === 'none' || c.visibility === 'hidden' || c.visibility === 'collapse') return true;
      if (parseFloat(c.opacity) === 0) return true;
    }
    return false;
  };
  // Every non-empty text node of `root` that actually paints, with its per-LINE
  // rects.  Range.getClientRects() bounds the GLYPHS, not the block box - the
  // whole point: a <p> box spans the column, its text may occupy a third of it,
  // and comparing block boxes is what produces the non-observable "collisions"
  // that DOM-only layout checkers are known for.
  D.textRuns = (root) => {
    const out = [];
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: t => (t.nodeValue && t.nodeValue.trim().length)
        ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT });
    let t, id = 0;
    while ((t = w.nextNode())) {
      const p = t.parentElement;
      if (!p) continue;
      const tag = p.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'TITLE') continue;
      if (D.hidden(p)) continue;
      const cs = getComputedStyle(p);
      const isSvg = p.namespaceURI === 'http://www.w3.org/2000/svg';
      let paint = cs.color;
      if (isSvg) {
        const fill = cs.fill;
        if (!fill || fill === 'none') continue;            // nothing is painted
        if (fill.indexOf('url(') === 0) continue;          // gradient/pattern: not a flat colour
        paint = (fill === 'currentcolor' || fill === 'currentColor') ? cs.color : fill;
      }
      const r = document.createRange(); r.selectNodeContents(t);
      const rects = [...r.getClientRects()].filter(b => b.width > 0.5 && b.height > 0.5);
      if (!rects.length) continue;
      out.push({id: id++, text: D.head(t.nodeValue, 90), sel: D.sel(p),
                el: p, node: t, svg: isSvg,
                color: paint, fontSize: D.px(cs.fontSize),
                fontWeight: cs.fontWeight, fontFamily: cs.fontFamily,
                opacity: D.opacity(p),
                rects: rects.map(b => ({l: b.left, t: b.top, r: b.right, b: b.bottom,
                                        w: b.width, h: b.height}))});
    }
    return out;
  };
  window.__press = D;
  return true;
}"""

# ---------------------------------------------------------------------------- D1 overflow
# The section is the scroll container in both decks (.slide{overflow-y:auto}), so
# scrollHeight-clientHeight is the honest "does not fit".  The descendant walk
# names the offender; descendants of an inner clipper are skipped (clipped by
# design) and reported separately as scrollers - in a PROJECTED deck a scroller
# still hides content from the audience, so they are reported, at lower severity.
OVERFLOW = """([n, tol]) => {
  const D = window.__press;
  const s = document.querySelectorAll('.slide')[n - 1];
  const cs = getComputedStyle(s);
  const pl = D.px(cs.paddingLeft), pr = D.px(cs.paddingRight),
        pt = D.px(cs.paddingTop), pb = D.px(cs.paddingBottom);
  const r = s.getBoundingClientRect();
  const box = {l: r.left + pl, t: r.top + pt,
               r: r.left + s.clientWidth - pr, b: r.top + s.clientHeight - pb};
  const all = [...s.querySelectorAll('*')];
  const clip = new Set(all.filter(e => {
    const c = getComputedStyle(e);
    return c.overflowX !== 'visible' || c.overflowY !== 'visible';
  }));
  const inClip = e => { for (let p = e.parentElement; p && p !== s; p = p.parentElement)
                          if (clip.has(p)) return true; return false; };
  const cand = [], scrollers = [];
  for (const e of all) {
    if (clip.has(e) && (e.scrollWidth - e.clientWidth > tol || e.scrollHeight - e.clientHeight > tol))
      scrollers.push({sel: D.sel(e), text_head: D.head(e.textContent, 50),
                      dx: Math.round(e.scrollWidth - e.clientWidth),
                      dy: Math.round(e.scrollHeight - e.clientHeight)});
    if (inClip(e) || D.hidden(e)) continue;
    const b = e.getBoundingClientRect();
    if (b.width <= 0 || b.height <= 0) continue;
    const dx = b.right - box.r, dy = b.bottom - box.b;
    if (dx > tol || dy > tol) cand.push({el: e, dx: Math.max(0, dx), dy: Math.max(0, dy)});
  }
  const inner = cand.filter(c => !cand.some(o => o.el !== c.el && c.el.contains(o.el)));
  inner.sort((a, b) => Math.max(b.dx, b.dy) - Math.max(a.dx, a.dy));
  const hd = s.querySelector('h1, h2'), kk = s.querySelector('.kicker');
  return {
    title: hd ? D.head(hd.textContent, 90) : '', kicker: kk ? D.head(kk.textContent, 70) : '',
    w: s.clientWidth, h: s.clientHeight, scroll_w: s.scrollWidth, scroll_h: s.scrollHeight,
    overflow_x_px: Math.max(0, Math.round(s.scrollWidth - s.clientWidth)),
    overflow_y_px: Math.max(0, Math.round(s.scrollHeight - s.clientHeight)),
    offenders: inner.slice(0, 12).map(c => ({sel: D.sel(c.el), text_head: D.head(c.el.textContent, 70),
                                             block_sel: D.sel(D.block(c.el)),
                                             block_text: D.head(D.block(c.el).textContent, 70),
                                             dx: Math.round(c.dx), dy: Math.round(c.dy)})),
    scrollers: scrollers.slice(0, 10),
    opts: {...s.dataset},
  };
}"""

# ---------------------------------------------------------------------------- D2 text overlap
# Pairwise intersection of the per-line glyph rects of DIFFERENT text nodes.
# Two guards keep the non-observable class down: a y-band bucket makes it cheap,
# and an overlap must be a real share of the smaller rect, not a 1px kiss of two
# adjacent inline runs sharing a line box.
TEXT_OVERLAP = """([minFrac, minPx]) => {
  const D = window.__press;
  const s = document.querySelector('.slide.on');
  if (!s) return {pairs: [], runs: 0, rects: 0};
  const runs = D.textRuns(s);
  const items = [];
  runs.forEach(rn => rn.rects.forEach((b, k) => items.push({run: rn, k, b})));
  // bucket by 40px y-band so we only compare things that can possibly meet
  const BAND = 40, buckets = new Map();
  items.forEach((it, i) => {
    for (let y = Math.floor(it.b.t / BAND); y <= Math.floor(it.b.b / BAND); y++) {
      if (!buckets.has(y)) buckets.set(y, []);
      buckets.get(y).push(i);
    }
  });
  const seen = new Set(), pairs = [];
  for (const idxs of buckets.values()) {
    for (let a = 0; a < idxs.length; a++) for (let c = a + 1; c < idxs.length; c++) {
      const i = idxs[a], j = idxs[c];
      const A = items[i], B = items[j];
      if (A.run.id === B.run.id) continue;                       // same text node
      if (A.run.el === B.run.el) continue;                       // same element, split by an inline
      const key = i < j ? i + ':' + j : j + ':' + i;
      if (seen.has(key)) continue; seen.add(key);
      const ow = Math.min(A.b.r, B.b.r) - Math.max(A.b.l, B.b.l);
      const oh = Math.min(A.b.b, B.b.b) - Math.max(A.b.t, B.b.t);
      if (ow <= 0 || oh <= 0) continue;
      const area = ow * oh;
      const small = Math.min(A.b.w * A.b.h, B.b.w * B.b.h);
      // a real ink collision covers a share of the smaller run AND is more than
      // a hairline in both axes; adjacent inline runs on one line fail both.
      if (area < minPx) continue;
      if (area < small * minFrac) continue;
      if (oh < Math.min(A.b.h, B.b.h) * 0.30) continue;
      pairs.push({a: {text: A.run.text, sel: A.run.sel, box: D.rect({left: A.b.l, top: A.b.t, width: A.b.w, height: A.b.h})},
                  b: {text: B.run.text, sel: B.run.sel, box: D.rect({left: B.b.l, top: B.b.t, width: B.b.w, height: B.b.h})},
                  overlap_px: Math.round(area), overlap_w: Math.round(ow), overlap_h: Math.round(oh),
                  frac_of_smaller: +(area / small).toFixed(3)});
    }
  }
  pairs.sort((x, y) => y.overlap_px - x.overlap_px);
  return {pairs: pairs.slice(0, 20), runs: runs.length, rects: items.length};
}"""

# ---------------------------------------------------------------------------- D3 truncation
# Two distinct things, deliberately separated:
#  ellipsis / clamp - the browser silently DELETED text.  Always an error.
#  nowrap+hidden    - cut with no ellipsis, so the reader cannot even tell.
# Inner scrollers are NOT reported here (OVERFLOW returns them) because they are
# a different remedy: reachable on a laptop, invisible on a projector.
TRUNCATION = """([tol]) => {
  const D = window.__press;
  const s = document.querySelector('.slide.on');
  if (!s) return [];
  const out = [];
  for (const e of s.querySelectorAll('*')) {
    if (D.hidden(e)) continue;
    const c = getComputedStyle(e);
    const dx = e.scrollWidth - e.clientWidth, dy = e.scrollHeight - e.clientHeight;
    const clipX = c.overflowX === 'hidden' || c.overflowX === 'clip';
    const clipY = c.overflowY === 'hidden' || c.overflowY === 'clip';
    const ell = (c.textOverflow || '').startsWith('ellipsis');
    const clamp = c.webkitLineClamp && c.webkitLineClamp !== 'none';
    let kind = null;
    if (ell && dx > tol) kind = 'ellipsis';
    else if (clamp && dy > tol) kind = 'line-clamp';
    else if (clipX && dx > tol && (c.whiteSpace === 'nowrap' || c.whiteSpace === 'pre')) kind = 'nowrap-clip';
    else if (clipY && dy > tol && e.textContent.trim()) kind = 'vertical-clip';
    if (!kind) continue;
    out.push({kind, sel: D.sel(e), text_head: D.head(e.textContent, 90),
              dx: Math.round(Math.max(0, dx)), dy: Math.round(Math.max(0, dy)),
              client_w: e.clientWidth, scroll_w: e.scrollWidth,
              white_space: c.whiteSpace, text_overflow: c.textOverflow});
  }
  return out.slice(0, 20);
}"""

# ---------------------------------------------------------------------------- D4 occlusion
# elementFromPoint is a HIT TEST: it asks the engine what is actually painted on
# top at a point, which is the question rect arithmetic cannot answer (rect math
# cannot see z-index, transforms, clip-path or pointer-events:none overlays).
# Probed per text run, at the glyph rect's own sample points - so the answer is
# "is this TEXT covered", not "do two boxes intersect".
OCCLUSION = """([samples]) => {
  const D = window.__press;
  const s = document.querySelector('.slide.on');
  if (!s) return [];
  const runs = D.textRuns(s);
  const out = [];
  for (const rn of runs) {
    for (const b of rn.rects) {
      if (b.w < 6 || b.h < 6) continue;
      const pts = [];
      for (let i = 1; i <= samples; i++) for (let j = 1; j <= 2; j++)
        pts.push([b.l + b.w * i / (samples + 1), b.t + b.h * j / 3]);
      let coveredBy = null, hits = 0;
      for (const [x, y] of pts) {
        if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) continue;
        const hit = document.elementFromPoint(x, y);
        if (!hit) continue;
        if (hit === rn.el || rn.el.contains(hit) || hit.contains(rn.el)) continue;
        hits++; coveredBy = hit;
      }
      if (coveredBy && hits >= Math.max(2, pts.length * 0.25)) {
        const cb = getComputedStyle(coveredBy);
        out.push({text: rn.text, sel: rn.sel, box: D.rect({left: b.l, top: b.t, width: b.w, height: b.h}),
                  covered_by: D.sel(coveredBy), covered_by_text: D.head(coveredBy.textContent, 40),
                  covered_by_position: cb.position, covered_by_z: cb.zIndex,
                  points_covered: hits, points_tested: pts.length});
        break;
      }
    }
  }
  return out.slice(0, 20);
}"""

# ---------------------------------------------------------------------------- D5 small text / D12 offscreen
SMALL_TEXT = """([minPx]) => {
  const D = window.__press;
  const s = document.querySelector('.slide.on');
  if (!s) return {small: [], min_font_px: null, n: 0};
  const runs = D.textRuns(s);
  const small = runs.filter(r => r.fontSize < minPx)
    .map(r => ({text: r.text, sel: r.sel, font_px: +r.fontSize.toFixed(2)}));
  const sizes = runs.map(r => r.fontSize);
  return {small: small.slice(0, 20), n: runs.length,
          min_font_px: sizes.length ? +Math.min(...sizes).toFixed(2) : null,
          median_font_px: sizes.length ? +sizes.sort((a, b) => a - b)[sizes.length >> 1].toFixed(2) : null};
}"""

OFFSCREEN = """([tol]) => {
  const D = window.__press;
  const s = document.querySelector('.slide.on');
  if (!s) return [];
  const vw = innerWidth, vh = innerHeight, out = [];
  for (const e of s.querySelectorAll('*')) {
    if (D.hidden(e)) continue;
    if (!e.textContent.trim() && !['IMG', 'VIDEO', 'SVG', 'CANVAS'].includes(e.tagName)) continue;
    const b = e.getBoundingClientRect();
    if (b.width <= 0 || b.height <= 0) continue;
    let why = null;
    if (b.right <= tol) why = 'left-of-viewport';
    else if (b.left >= vw - tol) why = 'right-of-viewport';
    else if (b.bottom <= tol) why = 'above-viewport';
    else if (b.top >= vh - tol) why = 'below-viewport';
    if (!why) continue;
    // the innermost one only: a wrapper off-screen drags its children along
    if (out.some(o => o.el.contains(e))) { out.splice(out.findIndex(o => o.el.contains(e)), 1); }
    out.push({el: e, why, sel: D.sel(e), text_head: D.head(e.textContent, 60), box: D.rect(b)});
  }
  return out.map(({el, ...r}) => r).slice(0, 20);
}"""

# ---------------------------------------------------------------------------- D6 font fallback
# Three independent witnesses, because each alone lies:
#  fonts.check()  - true for a font installed on the MACHINE, not necessarily
#                   the one the deck inlined; and true before layout uses it.
#  FontFaceSet    - names what the document actually declared and loaded.
#  width probe    - the only one that proves the glyphs on screen came from the
#                   wanted family: same string, family+fallback vs fallback
#                   alone, against TWO structurally different fallbacks so an
#                   accidental metric match cannot fool it.
FONTS = """([families]) => {
  const out = {declared: [], checks: {}, probes: []};
  try {
    document.fonts.forEach(f => out.declared.push(
      {family: f.family, weight: f.weight, style: f.style, status: f.status}));
  } catch (e) { out.declared_error = String(e); }
  out.fonts_status = document.fonts.status;
  const cv = document.createElement('canvas').getContext('2d');
  const S = 'Hamburgefonstiv 0123456789 MMMWWWiii';
  const measure = spec => { cv.font = spec; return +cv.measureText(S).width.toFixed(3); };
  for (const fam of families) {
    for (const w of [400, 700]) {
      try { out.checks[w + ' ' + fam] = document.fonts.check(`${w} 16px "${fam}"`); } catch (e) {}
    }
    const probe = {family: fam, applied: null, evidence: []};
    let applied = false;
    for (const fb of ['monospace', 'serif']) {
      const withFam = measure(`400 64px "${fam}", ${fb}`);
      const bare = measure(`400 64px ${fb}`);
      probe.evidence.push({fallback: fb, w_with: withFam, w_bare: bare,
                           differs: Math.abs(withFam - bare) > 0.5});
      if (Math.abs(withFam - bare) > 0.5) applied = true;
    }
    probe.applied = applied;
    out.probes.push(probe);
  }
  // what the deck's own body text actually resolved to, per the first run
  const s = document.querySelector('.slide.on');
  if (s) {
    const p = s.querySelector('p, li, h1, h2');
    if (p) {
      const cs = getComputedStyle(p);
      out.body_font_family = cs.fontFamily;
      out.body_font_size = cs.fontSize;
    }
  }
  return out;
}"""

# ---------------------------------------------------------------------------- D7 images
IMAGES = """(_) => {
  const D = window.__press;
  const s = document.querySelector('.slide.on');
  if (!s) return [];
  return [...s.querySelectorAll('img')].map(im => {
    const b = im.getBoundingClientRect();
    const src = im.currentSrc || im.src || '';
    const m = /^data:([^;,]+)[;,]/.exec(src);
    return {
      sel: D.sel(im), alt: D.head(im.alt, 60),
      scheme: src.slice(0, 5) === 'data:' ? 'data' : (src.split(':')[0] || '?'),
      mime: m ? m[1] : null,
      bytes: src.slice(0, 5) === 'data:' ? Math.round(src.length * 0.75) : null,
      complete: im.complete, natural_w: im.naturalWidth, natural_h: im.naturalHeight,
      render_w: Math.round(b.width), render_h: Math.round(b.height),
      loading: im.getAttribute('loading'),
      broken: im.complete && im.naturalWidth === 0,
      upscale: (im.naturalWidth > 0 && b.width > 0) ? +(b.width / im.naturalWidth).toFixed(2) : null,
      src_head: src.slice(0, 64),
    };
  });
}"""

# ---------------------------------------------------------------------------- D8 video + codec sniff
# THE point of this probe.  MediaError.code 4 (SRC_NOT_SUPPORTED) is returned
# both for "this build of Chromium was compiled without that decoder" and for
# "these bytes are not a video".  A checker that cannot tell them apart either
# cries wolf on every deck (this Chromium has no H.264) or goes blind to real
# corruption.  So we read the codec out of the CONTAINER - the ISO-BMFF sample
# entry fourcc, which is a fact about the bytes, not about the browser - and
# only then ask canPlayType.  Streaming: stop as soon as moov is parsed, so a
# 30 MB data: URI is not materialised twice.
VIDEOS = """async ([sniffBytes]) => {
  const D = window.__press;
  const s = document.querySelector('.slide.on');
  if (!s) return [];
  const boxWalk = (buf) => {
    // returns the first sample-entry fourcc found under moov/trak/.../stsd
    const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    const str = (o, n) => String.fromCharCode(...new Uint8Array(buf.buffer, buf.byteOffset + o, n));
    let found = null, ftyp = null, sawMoov = false;
    const walk = (start, end, depth) => {
      let o = start;
      while (o + 8 <= end && !found) {
        let size = dv.getUint32(o);
        const type = str(o + 4, 4);
        let hdr = 8;
        if (size === 1) { if (o + 16 > end) return; size = Number(dv.getBigUint64(o + 8)); hdr = 16; }
        else if (size === 0) size = end - o;
        if (size < hdr) return;
        if (type === 'ftyp' && !ftyp && o + 12 <= end) ftyp = str(o + 8, 4);
        if (type === 'moov') sawMoov = true;
        if (['moov', 'trak', 'mdia', 'minf', 'stbl'].includes(type))
          walk(o + hdr, Math.min(end, o + size), depth + 1);
        else if (type === 'stsd' && o + hdr + 16 <= end) {
          // stsd: 4 version/flags + 4 entry_count, then entries (4 size + 4 fourcc)
          found = str(o + hdr + 12, 4);
        }
        o += size;
      }
    };
    walk(0, buf.byteLength, 0);
    return {fourcc: found, ftyp, saw_moov: sawMoov};
  };
  const CODEC_STR = {avc1: 'video/mp4; codecs="avc1.42E01E"', avc3: 'video/mp4; codecs="avc1.42E01E"',
                     hev1: 'video/mp4; codecs="hvc1.1.6.L93.B0"', hvc1: 'video/mp4; codecs="hvc1.1.6.L93.B0"',
                     av01: 'video/mp4; codecs="av01.0.04M.08"', vp09: 'video/mp4; codecs="vp09.00.10.08"',
                     vp08: 'video/webm; codecs="vp8"'};
  const probe = document.createElement('video');
  const out = [];
  for (const v of s.querySelectorAll('video')) {
    const src = v.currentSrc || v.src || '';
    const mm = /^data:([^;,]+)[;,]/.exec(src);
    const rec = {
      sel: D.sel(v), scheme: src.slice(0, 5) === 'data:' ? 'data' : (src.split(':')[0] || '?'),
      mime: mm ? mm[1] : null,
      bytes: src.slice(0, 5) === 'data:' ? Math.round(src.length * 0.75) : null,
      ready_state: v.readyState, network_state: v.networkState,
      error_code: v.error ? v.error.code : null,
      error_message: v.error ? D.head(v.error.message, 120) : null,
      video_w: v.videoWidth, video_h: v.videoHeight,
      duration: Number.isFinite(v.duration) ? +v.duration.toFixed(2) : null,
      paused: v.paused, autoplay: v.autoplay, muted: v.muted, loop: v.loop,
      preload: v.getAttribute('preload'),
      can_play_declared_mime: mm ? probe.canPlayType(mm[1]) : null,
      decoded_frames: (() => { try { const q = v.getVideoPlaybackQuality();
        return q ? q.totalVideoFrames : null; } catch (e) { return null; } })(),
    };
    // sniff the container
    try {
      const res = await fetch(src);
      const reader = res.body.getReader();
      let chunks = [], total = 0;
      while (total < sniffBytes) {
        const {done, value} = await reader.read();
        if (done) break;
        chunks.push(value); total += value.length;
        // cheap early exit: a faststart file has moov within the first chunk(s)
      }
      try { await reader.cancel(); } catch (e) {}
      const buf = new Uint8Array(total);
      let o = 0; for (const c of chunks) { buf.set(c, o); o += c.length; }
      const w = boxWalk(buf);
      rec.container_ftyp = w.ftyp;
      rec.codec_fourcc = w.fourcc;
      rec.sniffed_bytes = total;
      rec.moov_within_sniff = w.saw_moov;
      if (w.fourcc && CODEC_STR[w.fourcc]) {
        rec.codec_query = CODEC_STR[w.fourcc];
        rec.can_play_actual_codec = probe.canPlayType(CODEC_STR[w.fourcc]);
      }
    } catch (e) { rec.sniff_error = String(e).slice(0, 120); }
    out.push(rec);
  }
  return out;
}"""

# ---------------------------------------------------------------------------- D9 contrast (geometry half)
# Emits the per-line glyph rects plus the resolved foreground colour and the
# CSS-walked background (what axe-core would use).  The PIXEL background - the
# half axe-core declines to compute when there is a background image - is
# measured host-side from the ink mask; see deck_qa.py:contrast_from_plates.
CONTRAST_GEOM = """(_) => {
  const D = window.__press;
  const s = document.querySelector('.slide.on');
  if (!s) return [];
  const runs = D.textRuns(s);
  const cssBg = el => {
    for (let e = el; e && e.nodeType === 1; e = e.parentElement) {
      const c = getComputedStyle(e);
      if (c.backgroundImage && c.backgroundImage !== 'none') return {color: null, image: true, from: D.sel(e)};
      const m = /rgba?\\(([^)]+)\\)/.exec(c.backgroundColor);
      if (!m) continue;
      const p = m[1].split(',').map(x => parseFloat(x));
      if (p.length < 4 || p[3] > 0.99) return {color: [p[0], p[1], p[2]], image: false, from: D.sel(e)};
    }
    const b = getComputedStyle(document.body);
    const m = /rgba?\\(([^)]+)\\)/.exec(b.backgroundColor);
    const p = m ? m[1].split(',').map(x => parseFloat(x)) : [255, 255, 255];
    return {color: [p[0], p[1], p[2]], image: false, from: 'body'};
  };
  return runs.map(r => ({
    text: r.text, sel: r.sel, color: r.color, svg: r.svg,
    font_px: r.fontSize, font_weight: r.fontWeight,
    opacity: +r.opacity.toFixed(3),
    css_bg: cssBg(r.el),
    rects: r.rects.map(b => D.rect({left: b.l, top: b.t, width: b.w, height: b.h})),
  }));
}"""

# Injected before the contrast "plate" screenshot: removes GLYPH INK only.  Not
# visibility:hidden (that would also remove backgrounds, borders and images of
# the same element) and not opacity (that composites the whole subtree).  Layout
# is untouched, so plate and shot are pixel-aligned by construction.
INK_OFF_CSS = """*, *::before, *::after {
  color: transparent !important;
  -webkit-text-fill-color: transparent !important;
  -webkit-text-stroke-color: transparent !important;
  text-shadow: none !important;
  caret-color: transparent !important;
  text-decoration-color: transparent !important;
}
svg text, svg tspan { fill: transparent !important; stroke: none !important; }"""

# ---------------------------------------------------------------------------- deck shape
DECK_INFO = """(_) => {
  const slides = [...document.querySelectorAll('.slide')];
  return {
    slides: slides.length,
    has_show: typeof window.show === 'function',
    title: document.title,
    nav_dots: document.querySelectorAll('.dot').length,
    on_count: document.querySelectorAll('.slide.on').length,
    slide_opts: slides.map(s => ({...s.dataset})),
  };
}"""
