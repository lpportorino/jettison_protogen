"""Where every DOM node of a printed page landed in the PDF Chromium wrote.

The mechanism: print with `tagged=True`. Chromium's tagged PDF writes one structure element per
accessibility-relevant DOM node, and that element's /ID is "node%08d" of Blink's DOMNodeId -- the
SAME number CDP reports as `backendNodeId`. Every text node is a structure element whose
marked-content references (page, MCID) cover exactly its glyphs. So:

    DOM node --backendNodeId--> structure element --(page, MCID)--> glyphs with boxes, font, size

No markers are injected into the document. The id == node-id link is how Chromium m129 (the
Playwright 1.47 image) works, NOT a documented contract: `join_check` measures the join on every
run and refuses a print where it no longer holds, so a Chromium upgrade that breaks it fails
loudly instead of degrading to "no findings".

One PDF reader: pdfplumber (over pdfminer.six) reads the glyphs with their MCIDs, the drawings, and
-- through pdfminer's objects -- the structure tree. pdfplumber's own structure-tree class keeps
ONE page per element and so loses the second page of a text node split across a page break; the
walk here keeps every marked-content reference's own page.

    snapshot(page)          before page.pdf: the DOM (CDP DOMSnapshot, computed styles) + @page margins
    read_pdf(path)          glyphs, drawings and the structure tree of a printed PDF
    build(snap, pdf_path)   -> the model the detectors read
    join_check(model)       -> the join's measured shares; raises NothingToPrint (bad input) when no
                               text paints, JoinBroken under the floors
"""

import collections
import re

# computed styles the detectors read; nothing geometric (node ids survive any reflow)
STYLES = [
    "display",
    "font-family",
    "font-size",
    "white-space",
    "break-inside",
    "break-after",
    "break-before",
    "position",
    "overflow-wrap",
    "word-break",
    "height",
    "overflow",
    "visibility",
]
NODE_ID = re.compile(rb"node(\d+)")

# The join's floors, one per share (join_check says what each measures). The paged selftest
# compares a tagged print with untagged and deliberately mismatched structure-id controls.
FLOOR_STRUCT_MATCHED = 0.95
FLOOR_STRUCT_TYPED = 0.95
FLOOR_GLYPHS_AGREE = 0.95


# structure type -> the DOM tag names it may stand for (types not listed: any node)
TYPED = {
    **{t: {t} for t in ("P", "TD", "TH", "TR", "LI", "H1", "H2", "H3", "H4", "H5", "H6")},
    "Table": {"TABLE"},
    "L": {"UL", "OL"},
    "Figure": {"FIGURE", "IMG", "SVG"},
    "Document": {"#DOCUMENT"},
}


class JoinBroken(Exception):
    """The tagged-PDF join no longer holds: the detectors would read nothing and report clean."""

    def __init__(self, message, stats):
        super().__init__(message)
        self.stats = stats


PAGE_RULE_JS = """() => {
  const mm = v => { const m = /^([\\d.]+)mm$/.exec((v || '').trim()); return m ? parseFloat(m[1]) : null; };
  const out = {found: false, top: 0, right: 0, bottom: 0, left: 0};
  for (const ss of document.styleSheets) {
    let rules; try { rules = ss.cssRules; } catch (e) { continue; }
    for (const r of rules) {
      if (r.type !== CSSRule.PAGE_RULE) continue;
      out.found = true;
      for (const k of ['top', 'right', 'bottom', 'left']) {
        const v = mm(r.style['margin' + k[0].toUpperCase() + k.slice(1)]); if (v !== null) out[k] = v; }
    }
  }
  return out;
}"""


def snapshot(page):
    """The DOM of `page` as it is about to print: {nodes, page_rule}. nodes: {backendNodeId:
    {name, type, parent, kids, attrs, text, style, boxes}} (style None for a node with no layout
    object; boxes = the inline text boxes laid out for a text node)."""
    cdp = page.context.new_cdp_session(page)
    try:
        cdp.send("DOM.getDocument", {"depth": -1})
        snap = cdp.send("DOMSnapshot.captureSnapshot", {"computedStyles": STYLES})
    finally:
        cdp.detach()
    S = snap["strings"]
    d = snap["documents"][0]
    N = d["nodes"]
    lay = dict(zip(d["layout"]["nodeIndex"], d["layout"]["styles"], strict=False))
    # inline text boxes per node: a text node PAINTS only when its layout object has at least one
    # (a closed <details>, a collapsed or skipped subtree keeps a computed style and even a layout
    # object, but lays out no text box)
    boxes = {}
    for li in d.get("textBoxes", {}).get("layoutIndex", []):
        ni = d["layout"]["nodeIndex"][li]
        boxes[ni] = boxes.get(ni, 0) + 1
    bids = N["backendNodeId"]
    nodes = {}
    for i, bid in enumerate(bids):
        a = N["attributes"][i]
        pv = N["parentIndex"][i]
        st = lay.get(i)
        nv = N["nodeValue"][i]
        nodes[bid] = {
            "name": S[N["nodeName"][i]],
            "type": N["nodeType"][i],
            "parent": bids[pv] if pv >= 0 else None,
            "kids": [],
            "attrs": {S[a[j]]: S[a[j + 1]] for j in range(0, len(a), 2)},
            "text": S[nv] if nv >= 0 else "",
            "style": {k: S[v] for k, v in zip(STYLES, st, strict=False)} if st else None,
            "boxes": boxes.get(i, 0),
        }
    for bid, nd in nodes.items():
        if nd["parent"] is not None and nd["parent"] in nodes:
            nodes[nd["parent"]]["kids"].append(bid)
    return {"nodes": nodes, "page_rule": page.evaluate(PAGE_RULE_JS)}


def _patch_pdfplumber():
    """pdfplumber 0.11.4 resets the MCID to None on ANY nested marked content, and Skia wraps
    glyphs that lack a unicode mapping (tabular digits, list markers) in an inner /Span with
    /ActualText and no MCID -- so those glyphs lose their owner. Keep a stack and inherit the
    enclosing MCID instead. The patch is
    written against that version's aggregator; anything else is refused, never half-patched."""
    import pdfplumber
    import pdfplumber.page as P
    from pdfplumber.utils import decode_text

    if pdfplumber.__version__ != "0.11.4":
        raise RuntimeError(
            f"press pins pdfplumber 0.11.4 (its marked-content patch is written for that "
            f"version); found {pdfplumber.__version__}"
        )
    A = P.PDFPageAggregatorWithMarkedContent
    if getattr(A, "_dq_stacked", False):
        return

    def begin_tag(self, tag, props=None):
        self.__dict__.setdefault("_dq_mstack", []).append((self.cur_mcid, self.cur_tag))
        if isinstance(props, dict) and "MCID" in props:
            self.cur_mcid = props["MCID"]
        self.cur_tag = decode_text(tag.name)

    def end_tag(self):
        st = self.__dict__.setdefault("_dq_mstack", [])
        self.cur_mcid, self.cur_tag = st.pop() if st else (None, None)

    A.begin_tag, A.end_tag, A._dq_stacked = begin_tag, end_tag, True


def _num_color(c):
    """A fill colour as a tuple of floats, or None (a pattern, a name, no fill)."""
    if isinstance(c, (int, float)):
        return (float(c),)
    if isinstance(c, (tuple, list)) and c and all(isinstance(v, (int, float)) for v in c):
        return tuple(float(v) for v in c)
    return None


def _struct(doc, page_index):
    """Walk the structure tree over pdfminer's objects.
    -> frag {struct id: [[page, mcid], ...]}, tags {struct id: type}, sparent {struct id: parent id}.
    A struct id is the DOM backendNodeId for an element whose /ID reads node<N>; any other element
    (a generated list marker, the document root) gets a negative id of its own."""
    from pdfminer.pdftypes import PDFObjRef, resolve1
    from pdfminer.psparser import PSLiteral

    def name(v):
        v = resolve1(v)
        return v.name if isinstance(v, PSLiteral) else v

    frag, tags, sparent = {}, {}, {}
    root = resolve1(doc.catalog.get("StructTreeRoot"))
    if not isinstance(root, dict):
        return frag, tags, sparent
    synthetic = [0]

    def page_of(e, inherited):
        pg = e.get("Pg")
        if isinstance(pg, PDFObjRef):
            return page_index.get(pg.objid, inherited)
        return inherited

    stack = [(root.get("K"), 0, None)]  # (kid, owning struct id, inherited page)
    while stack:
        kid, owner, pg = stack.pop()
        kid = resolve1(kid)
        if kid is None:
            continue
        if isinstance(kid, list):
            stack.extend((k, owner, pg) for k in reversed(kid))
            continue
        if isinstance(kid, int):
            if pg is not None:
                frag.setdefault(owner, []).append([pg, kid])
            continue
        if not isinstance(kid, dict):
            continue
        if name(kid.get("Type")) == "MCR":
            p = page_of(kid, pg)
            if p is not None and "MCID" in kid:
                frag.setdefault(owner, []).append([p, int(resolve1(kid["MCID"]))])
            continue
        if "S" not in kid:
            continue  # an OBJR (a link annotation): no glyphs of its own
        raw = resolve1(kid.get("ID"))
        m = NODE_ID.fullmatch(raw) if isinstance(raw, bytes) else None
        if m:
            sid = int(m.group(1))
        else:
            synthetic[0] -= 1
            sid = synthetic[0]
        tags[sid] = name(kid["S"])
        sparent[sid] = owner
        stack.append((kid.get("K"), sid, page_of(kid, pg)))
    return frag, tags, sparent


def _shear(mx):
    """A glyph's horizontal lean per unit of height from its matrix (a, b, c, d, e, f): c/d for
    unrotated text (b == 0), else 0 -- rotated text is not slanted text."""
    a, b, c, d = mx[:4]
    return round(c / d, 3) if abs(b) < 1e-6 and d else 0.0


def read_pdf(pdf_path):
    """-> {pages: [{w, h, chars, draws}], frag, tags, sparent}.
    chars: [x0, top, x1, bottom, size, font, text, mcid, colour, shear, origin_x]; draws: [x0, top,
    x1, bottom, filled, stroked, paper] for every rect, line and curve (paper = a near-white fill
    with no stroke: a background, not ink). `shear` is the glyph's horizontal lean per unit of
    height (c/d of its text matrix; 0 for upright text, about 0.25 for a SYNTHETIC italic, which
    Chromium draws when the font has no italic face) and `origin_x` its pen position: the box
    pdfminer reports for a leaning glyph is the bounding box of the whole slanted em-box, wider
    than its advance by shear x height, so a detector that compares LAYOUT reads both."""
    import pdfplumber

    _patch_pdfplumber()
    pages = []
    with pdfplumber.open(pdf_path) as pdf:
        page_index = {p.page_obj.pageid: i for i, p in enumerate(pdf.pages)}
        frag, tags, sparent = _struct(pdf.doc, page_index)
        for p in pdf.pages:
            chars = [
                [
                    round(c["x0"], 2),
                    round(c["top"], 2),
                    round(c["x1"], 2),
                    round(c["bottom"], 2),
                    round(c["size"], 2),
                    c["fontname"],
                    c["text"],
                    c.get("mcid"),
                    str(c.get("non_stroking_color")),
                    _shear(c["matrix"]),
                    round(c["matrix"][4], 2),
                ]
                for c in p.chars
            ]
            draws = []
            for o in p.rects + p.lines + p.curves:
                fill = bool(o.get("fill"))
                stroke = bool(o.get("stroke"))
                col = _num_color(o.get("non_stroking_color"))
                paper = fill and not stroke and col is not None and min(col) > 0.98
                draws.append(
                    [
                        round(o["x0"], 2),
                        round(o["top"], 2),
                        round(o["x1"], 2),
                        round(o["bottom"], 2),
                        fill,
                        stroke,
                        paper,
                    ]
                )
            pages.append(
                {"w": float(p.width), "h": float(p.height), "chars": chars, "draws": draws}
            )
    return {"pages": pages, "frag": frag, "tags": tags, "sparent": sparent}


def build(snap, pdf_path):
    """The model the detectors read: the DOM snapshot joined to the printed PDF."""
    r = read_pdf(pdf_path)
    return {"nodes": snap["nodes"], "page_rule": snap["page_rule"], **r}


def rendered_text_nodes(nodes):
    """Text nodes that PAINT: non-blank, laid out in at least one inline text box (from the DOM
    snapshot's layout tree, not from computed style), and not visibility-hidden."""
    for b, n in nodes.items():
        if n["type"] != 3 or not n["text"].strip() or not n.get("boxes"):
            continue
        par = nodes.get(n["parent"])
        if par and par["style"] and par["style"].get("visibility") != "visible":
            continue
        yield b


def _ancestors(nodes, b):
    p = nodes[b]["parent"]
    while p is not None and p in nodes:
        yield p
        p = nodes[p]["parent"]


class NothingToPrint(Exception):
    """The page has no text that paints (an empty body, only images, an empty file): there is
    nothing for the layout pass to judge -- bad input, not a broken join."""


def _chars(text):
    return collections.Counter(
        ch for ch in text if not ch.isspace() and not 0xE000 <= ord(ch) <= 0xF8FF
    )


def join_check(model):
    """Measure the join; raise NothingToPrint when no text paints, JoinBroken when a share is under
    its floor. Three shares:
      struct_matched  of the structure elements whose /ID names a node, the share in the DOM snapshot
      struct_typed    of those whose structure type names an HTML tag, the share whose node IS that
                      tag (only when the page has such elements)
      glyphs_agree    of the printed glyphs inside a node-id'd structure element, the share whose owner
                      is a DOM TEXT node whose own characters account for them (the owner's glyph
                      characters, private-use tabular digits aside, are a sub-multiset of its text
                      taken once per page it prints on -- a repeated table header owns its glyphs on
                      every page)
    The last one is measured from the GLYPHS, so text that legitimately did not print (a run pushed
    off the page, a 50 MB unbreakable line the parser split into 800 text nodes) does not read as a
    broken join -- that is `text-not-printed`'s finding. An untagged print has no node-id'd elements
    and scores 0. `text_nodes` / `text_mapped` are reported, not floored."""
    nodes, frag, tags = model["nodes"], model["frag"], model["tags"]
    texts = list(rendered_text_nodes(nodes))
    if not texts:
        raise NothingToPrint(
            "the page has no text that paints (an empty body, only images, or an empty file): "
            "nothing for the layout pass to judge"
        )
    real = [s for s in tags if s >= 0]
    matched = sum(1 for s in real if s in nodes)
    typed = [s for s in real if tags[s] in TYPED]
    typed_ok = sum(1 for s in typed if s in nodes and nodes[s]["name"].upper() in TYPED[tags[s]])
    mapped = sum(1 for b in texts if frag.get(b))
    owner = {(pg, mc): sid for sid, fl in frag.items() if sid >= 0 for pg, mc in fl}
    per = collections.defaultdict(str)
    pages_of = collections.defaultdict(set)
    for pi, p in enumerate(model["pages"]):
        for c in p["chars"]:
            sid = owner.get((pi, c[7]))
            if sid is not None and not c[6].isspace():
                per[sid] += c[6]
                pages_of[sid].add(pi)
    owned = agree = 0
    for sid, chars in per.items():
        got = _chars(chars)
        n = sum(1 for ch in chars if not ch.isspace())
        owned += n
        node = nodes.get(sid)
        # a table header row repeats on every page it spans: its text node owns its glyphs once per page
        k = len(pages_of[sid])
        if (
            node
            and node["type"] == 3
            and not (
                got - collections.Counter({ch: v * k for ch, v in _chars(node["text"]).items()})
            )
        ):
            agree += n
    shares = {
        "struct_matched": matched / len(real) if real else 0.0,
        "glyphs_agree": agree / owned if owned else 0.0,
    }
    if typed:
        shares["struct_typed"] = typed_ok / len(typed)
    floors = {
        "struct_matched": FLOOR_STRUCT_MATCHED,
        "glyphs_agree": FLOOR_GLYPHS_AGREE,
        "struct_typed": FLOOR_STRUCT_TYPED,
    }
    stats = {
        "struct_elements": len(tags),
        "struct_with_node_id": len(real),
        "struct_matched": matched,
        "struct_typed": len(typed),
        "struct_typed_consistent": typed_ok,
        "text_nodes": len(texts),
        "text_mapped": mapped,
        "glyphs_owned": owned,
        "glyphs_agree": agree,
        "shares": {k: round(v, 4) for k, v in shares.items()},
        "floors": {k: floors[k] for k in shares},
    }
    low = [k for k, v in shares.items() if v < floors[k]]
    if low:
        why = ", ".join(f"{k} {shares[k]:.1%} < {floors[k]:.0%}" for k in low)
        raise JoinBroken(
            f"the tagged-PDF join no longer holds ({why}; {matched}/{len(real)} structure elements matched a DOM "
            f"node, {typed_ok}/{len(typed)} of the typed ones matched a node of their own tag, {agree}/{owned} owned "
            "glyphs belong to a text node whose characters account for them). The layout detectors would read "
            "nothing and report clean. The likely cause is a Chromium upgrade that changed how a tagged PDF names "
            'its structure elements (/ID "node<N>" == CDP backendNodeId), or a print that was not tagged.',
            stats,
        )
    return stats
