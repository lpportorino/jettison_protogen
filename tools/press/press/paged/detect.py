"""The paged-layout detectors: what went wrong where the browser broke a document into pages.

They read the model `model.build` makes (every DOM node joined to its printed glyphs through the
tagged PDF) and answer questions a page reviewer otherwise answers by eye, page after page:

  split-table / split-list   a table or list broken across pages (fix when short enough to have fit,
                             or a stub of one item is left behind; a long one that must flow is info)
  row-split                  one table row printed on two pages
  heading-stranded           a heading is the last thing on its page; what it heads starts on the next
  intro-stranded             a line that introduces what follows (ends in ':', a caption, .keep-next)
                             is left on the page before it; also a figure caption on another page
                             than its figure
  runt-page                  a page carrying only a few spilled lines, or mostly empty mid-section
  orphan-line / widow-line   one line of a paragraph alone at the foot / head of a page
  overprint                  glyphs of different text painted over each other, or text inside a
                             figure's ink (a synthetic italic is compared on its advance, not on
                             the box of its slanted em-box: `upright`)
  outside-printable          ink beyond the @page margins
  starved-column             a table cell so narrow its prose wraps one or two words per line
  broken-token               a code token broken mid-word where the source had no break point
  figure-text-below-floor    figure text smaller than the house floor ON PAPER
  section-not-page-start     a numbered section heading ("3. ...") that does not open its page
  text-not-printed           source text with no glyphs: Chromium drops text wholly outside the clip

Every finding: {detector, page (1-based), element (a short excerpt), measure, severity}. Severity
`blocking` fails the print (unless its detector is allowed), `fix` is reported, `info` is recorded
only -- the contract's three words, used everywhere (report, stdout, pdfcheck).
"""

import collections
import re

from fig import house

PT_PER_MM = 72 / 25.4
MONO = re.compile(r"mono|courier|consol|menlo", re.I)
BLOCKISH = {
    "block",
    "list-item",
    "table",
    "table-row",
    "table-cell",
    "flex",
    "grid",
    "flow-root",
    "table-caption",
    "table-row-group",
    "table-header-group",
    "table-footer-group",
}
FLOOR_PT = float(house.TEXT_FLOOR)
STUB_LINES = 3
# a line ending in one of these broke at a list's own item boundary ("fan · tap · hub"), not mid-prose
ITEM_SEPARATORS = set("·•|,;/")
DETECTORS = [
    "split-table",
    "split-list",
    "row-split",
    "heading-stranded",
    "intro-stranded",
    "runt-page",
    "orphan-line",
    "widow-line",
    "overprint",
    "outside-printable",
    "starved-column",
    "broken-token",
    "figure-text-below-floor",
    "section-not-page-start",
    "text-not-printed",
]
SEVERITIES = ("blocking", "fix", "info")


class Doc:
    def __init__(self, m):
        self.m = m
        self.nodes = {int(k): v for k, v in m["nodes"].items()}
        self.pages = m["pages"]
        pr = m.get("page_rule") or {}
        self.margin = {k: pr.get(k, 0) * PT_PER_MM for k in ("top", "right", "bottom", "left")}
        # (page, mcid) -> owning DOM node; struct-only nodes (pseudo-elements) fold into
        # their nearest struct ancestor that IS a DOM node
        sparent = {int(k): v for k, v in m.get("sparent", {}).items()}

        def dom_owner(bid):
            seen = 0
            while bid not in self.nodes and bid in sparent and seen < 50:
                bid = sparent[bid]
                seen += 1
            return bid if bid in self.nodes else None

        self.owner = {}
        for bid, fl in m["frag"].items():
            o = dom_owner(int(bid))
            for pg, mc in fl:
                self.owner[(pg, mc)] = o
        # glyph records: [page, x0, top, x1, bottom, size, font, text, owner, shear, origin_x]
        # (shear and origin_x: model.read_pdf; `upright` uses them)
        self.own_glyphs = collections.defaultdict(list)
        self.unowned = collections.Counter()
        self.all_glyphs = []
        for pi, p in enumerate(self.pages):
            for c in p["chars"]:
                o = self.owner.get((pi, c[7]))
                g = (pi, c[0], c[1], c[2], c[3], c[4], c[5], c[6], o, c[9], c[10])
                self.all_glyphs.append(g)
                if o is None:
                    self.unowned[pi] += 1
                else:
                    self.own_glyphs[o].append(g)
        self.root = next((b for b, n in self.nodes.items() if n["parent"] is None), None)
        self._sub = {}
        self.order = []  # document order of DOM nodes
        stack = [self.root] if self.root is not None else []
        while stack:
            b = stack.pop()
            self.order.append(b)
            stack.extend(reversed(self.nodes[b]["kids"]))
        self.pos = {b: i for i, b in enumerate(self.order)}
        for b in reversed(self.order):
            gl = list(self.own_glyphs.get(b, []))
            for k in self.nodes[b]["kids"]:
                gl.extend(self._sub[k])
            self._sub[b] = gl
        self.pr_top = self.margin["top"]
        self.pr_left = self.margin["left"]
        self.pr_bottom = self.pages[0]["h"] - self.margin["bottom"] if self.pages else 0
        self.pr_right = self.pages[0]["w"] - self.margin["right"] if self.pages else 0

    # --- node helpers
    def glyphs(self, b):
        return self._sub.get(b, [])

    def name(self, b):
        return self.nodes[b]["name"].upper()

    def style(self, b, k, d=""):
        st = self.nodes[b]["style"]
        return st.get(k, d) if st else d

    def cls(self, b):
        return self.nodes[b]["attrs"].get("class", "")

    def text(self, b):
        """The node's text content, memoised: a label is asked for again and again, and a parent of
        800 text nodes 64 KiB each (one 50 MB run the parser split) would be re-joined every time."""
        memo = self.__dict__.setdefault("_text", {})
        if b not in memo:
            n = self.nodes[b]
            memo[b] = n["text"] if n["type"] == 3 else "".join(self.text(k) for k in n["kids"])
        return memo[b]

    def label(self, b):
        n = self.nodes[b]
        t = re.sub(r"\s+", " ", self.text(b)).strip()
        tag = n["name"].lower()
        i = n["attrs"].get("id")
        c = n["attrs"].get("class")
        return f'{tag}{"#" + i if i else ""}{"." + c.split()[0] if c else ""} "{t[:48]}"'

    def ancestors(self, b):
        p = self.nodes[b]["parent"]
        while p is not None and p in self.nodes:
            yield p
            p = self.nodes[p]["parent"]

    def in_svg(self, b):
        return any(self.name(a) == "SVG" for a in self.ancestors(b))

    def pages_of(self, b):
        return sorted({g[0] for g in self.glyphs(b)})

    def is_block(self, b):
        n = self.nodes[b]
        return n["type"] == 1 and n["style"] and n["style"].get("display") in BLOCKISH

    def leaf_blocks(self):
        """block-level elements with glyphs and no block-level descendant that has glyphs,
        outside SVG, in document order"""
        out = []
        for b in self.order:
            if (
                not self.is_block(b)
                or not self.glyphs(b)
                or self.name(b) in ("SVG",)
                or self.in_svg(b)
            ):
                continue
            if any(self.is_block(k) and self.glyphs(k) for k in self.desc_elems(b)):
                continue
            out.append(b)
        return out

    def desc_elems(self, b):
        st = list(self.nodes[b]["kids"])
        while st:
            k = st.pop()
            if self.nodes[k]["type"] == 1:
                yield k
                if self.name(k) != "SVG":
                    st.extend(self.nodes[k]["kids"])


def lines(glyphs):
    """cluster glyphs (same page) into lines by vertical-centre overlap; returns
    list of [top, bottom, [glyphs sorted by x]] sorted top-down"""
    gs = sorted(glyphs, key=lambda g: (g[2] + g[4]) / 2)
    out = []
    for g in gs:
        if g[7].isspace():
            continue
        cy = (g[2] + g[4]) / 2
        if out and out[-1][0] - 0.5 <= cy <= out[-1][1] + 0.5:
            L = out[-1]
            L[0] = min(L[0], g[2])
            L[1] = max(L[1], g[4])
            L[2].append(g)
        else:
            out.append([g[2], g[4], [g]])
    for L in out:
        L[2].sort(key=lambda g: g[1])
    return out


def line_words(L, gap_frac=0.22):
    """words on one line: split where the horizontal gap exceeds gap_frac*size,
    or at an explicit space glyph (space glyphs were dropped by lines())"""
    words, cur, prev = [], "", None
    for g in L[2]:
        if prev is not None and g[1] - prev[3] > gap_frac * g[5]:
            words.append(cur)
            cur = ""
        cur += g[7]
        prev = g
    if cur:
        words.append(cur)
    return [w for w in words if w.strip()]


def per_page(glyphs):
    d = collections.defaultdict(list)
    for g in glyphs:
        d[g[0]].append(g)
    return d


def finding(detector, page, element, measure, severity):
    """One finding; `page` is 0-based here and 1-based in the finding."""
    return {
        "detector": detector,
        "page": page + 1,
        "element": element,
        "measure": measure,
        "severity": severity,
    }


# ------------------------------------------------------------------ detectors
def split_container(D, names, det):
    """A table or list printed on more than one page. fix when the whole block is short enough
    (under 35 % of the printable height) to have moved to the next page intact, or when a fragment
    is a STUB -- fewer than 2 of its rows/items start there AND it holds at most 3 lines -- in a
    block of at most 6 items; a long block that must flow is info. The line count keeps a list of
    long items honest: one long item continuing onto the next page is normal flow, not a stub.
    The split_list_stub fixture covers this distinction."""
    out = []
    ph = D.pr_bottom - D.pr_top
    for b in D.order:
        if D.nodes[b]["type"] != 1 or D.name(b) not in names or D.in_svg(b):
            continue
        pgs = D.pages_of(b)
        if len(pgs) < 2:
            continue
        item = "TR" if D.name(b) == "TABLE" else ("LI" if D.name(b) in ("UL", "OL") else "DT")
        items = [k for k in D.desc_elems(b) if D.name(k) == item and D.glyphs(k)]
        if item == "TR":
            items = [k for k in items if not any(D.name(a) == "THEAD" for a in D.ancestors(k))]
        frag_items = collections.Counter(min(g[0] for g in D.glyphs(k)) for k in items)
        pp = per_page(D.glyphs(b))
        height = sum(max(g[4] for g in v) - min(g[2] for g in v) for v in pp.values())
        stub = [p for p in pgs if frag_items.get(p, 0) < 2 and len(lines(pp[p])) <= STUB_LINES]
        avoidable = height < 0.35 * ph
        sev = "fix" if (avoidable or (stub and len(items) <= 6)) else "info"
        out.append(
            finding(
                det,
                pgs[0],
                D.label(b),
                {
                    "pages": [p + 1 for p in pgs],
                    "items_per_page": [frag_items.get(p, 0) for p in pgs],
                    "height_pt": round(height),
                    "short_block": avoidable,
                    "stub_fragment_pages": [p + 1 for p in stub],
                },
                sev,
            )
        )
    return out


def row_split(D):
    out = []
    for b in D.order:
        if (
            D.nodes[b]["type"] == 1
            and D.name(b) == "TR"
            and not any(D.name(a) == "THEAD" for a in D.ancestors(b))
        ):
            pgs = D.pages_of(b)  # a THEAD row is REPEATED on every page by design
            if len(pgs) > 1:
                out.append(
                    finding(
                        "row-split", pgs[0], D.label(b), {"pages": [p + 1 for p in pgs]}, "blocking"
                    )
                )
    return out


def next_block(D, leaves, i):
    return leaves[i + 1] if i + 1 < len(leaves) else None


def heading_stranded(D, leaves):
    out = []
    for i, b in enumerate(leaves):
        if not re.fullmatch(r"H[1-6]", D.name(b)):
            continue
        nb = next_block(D, leaves, i)
        if nb is None:
            continue
        hp = max(g[0] for g in D.glyphs(b))
        np_ = min(g[0] for g in D.glyphs(nb))
        if np_ > hp:
            bottom = max(g[4] for g in D.glyphs(b) if g[0] == hp)
            out.append(
                finding(
                    "heading-stranded",
                    hp,
                    D.label(b),
                    {
                        "next_on_page": np_ + 1,
                        "space_below_mm": round((D.pr_bottom - bottom) / PT_PER_MM),
                        "next": D.label(nb),
                    },
                    "blocking",
                )
            )
    return out


def is_intro(D, b):
    t = re.sub(r"\s+", " ", D.text(b)).strip()
    c = D.cls(b).split()
    return t.endswith(":") or D.name(b) in ("CAPTION",) or "keep-next" in c


def intro_stranded(D, leaves):
    out = []
    for i, b in enumerate(leaves):
        if re.fullmatch(r"H[1-6]", D.name(b)) or not is_intro(D, b):
            continue
        nb = next_block(D, leaves, i)
        if nb is None:
            continue
        bp = max(g[0] for g in D.glyphs(b))
        np_ = min(g[0] for g in D.glyphs(nb))
        if np_ > bp:
            out.append(
                finding(
                    "intro-stranded",
                    bp,
                    D.label(b),
                    {"introduces": D.label(nb), "introduced_on_page": np_ + 1},
                    "blocking",
                )
            )
    # figcaption vs its figure's drawing (svg glyphs)
    for b in D.order:
        if D.nodes[b]["type"] == 1 and D.name(b) == "FIGCAPTION":
            fig = D.nodes[b]["parent"]
            svg = [k for k in D.desc_elems(fig) if D.name(k) == "SVG"] if fig in D.nodes else []
            if not svg or not D.glyphs(svg[0]) or not D.glyphs(b):
                continue
            sp = set(D.pages_of(svg[0]))
            cp = set(D.pages_of(b))
            if not (sp & cp):
                out.append(
                    finding(
                        "intro-stranded",
                        min(cp),
                        D.label(b),
                        {
                            "caption_pages": sorted(p + 1 for p in cp),
                            "figure_pages": sorted(p + 1 for p in sp),
                        },
                        "blocking",
                    )
                )
    return out


def forced_starts(D):
    """pages at which a forced page break starts content (break-before: page on an
    element whose first glyph lands there)"""
    starts = {0}
    for b in D.order:
        if (
            D.nodes[b]["type"] == 1
            and D.style(b, "break-before") in ("page", "always", "left", "right")
            and D.glyphs(b)
        ):
            starts.add(min(g[0] for g in D.glyphs(b)))
    return starts


def runt_pages(D):
    out = []
    starts = forced_starts(D)
    ph = D.pr_bottom - D.pr_top
    n = len(D.pages)
    for pi in range(n):
        gl = [g for g in D.all_glyphs if g[0] == pi]
        ls = lines(gl)
        if not ls:
            continue
        min(L[0] for L in ls)
        bot = max(L[1] for L in ls)
        # drawings (figures, rules) count as content height too
        for d in D.pages[pi]["draws"]:
            if d[1] >= D.pr_top - 2 and d[3] <= D.pr_bottom + 2 and not (len(d) > 6 and d[6]):
                bot = max(bot, d[3])
        fill = (bot - D.pr_top) / ph  # how far down the printable area the content reaches
        section_end = (pi + 1 in starts) or pi == n - 1
        spill = len(ls) <= 3 and pi not in starts  # a few lines carried over from the page before
        short = fill < 0.2 and not section_end  # a mostly empty page in the middle of a section
        if spill or short:
            out.append(
                finding(
                    "runt-page",
                    pi,
                    f"page {pi + 1}",
                    {"lines": len(ls), "fill": round(fill, 2), "section_end": section_end},
                    "blocking",
                )
            )
    return out


def orphan_widow(D, leaves):
    out = []
    for b in leaves:
        if D.name(b) not in ("P", "LI", "DD", "DT", "FIGCAPTION", "DIV", "BLOCKQUOTE", "PRE"):
            continue
        pp = per_page(D.glyphs(b))
        if len(pp) < 2:
            continue
        pgs = sorted(pp)
        first = len(lines(pp[pgs[0]]))
        last = len(lines(pp[pgs[-1]]))
        if first == 1:
            out.append(
                finding(
                    "orphan-line",
                    pgs[0],
                    D.label(b),
                    {"lines_here": 1, "continues_on": pgs[1] + 1},
                    "fix",
                )
            )
        if last == 1:
            out.append(
                finding(
                    "widow-line",
                    pgs[-1],
                    D.label(b),
                    {"lines_here": 1, "starts_on": pgs[0] + 1},
                    "fix",
                )
            )
    return out


def _core(g, f=0.2):
    h = g[4] - g[2]
    return (g[1] + 0.05 * (g[3] - g[1]), g[2] + f * h, g[3] - 0.05 * (g[3] - g[1]), g[4] - f * h)


def upright(g):
    """A glyph record with its box narrowed to the ADVANCE it was laid out on.

    A synthetic italic (the house font, Inter, ships no italic face, so Chromium slants the upright
    glyph) is drawn with a sheared text matrix; pdfminer's box for it is the bounding box of the
    whole slanted em-box, wider than the advance by shear x height. The lean is drawn above
    the baseline, where a full stop, comma or colon has no ink. Layout places text on advances at
    the baseline, so two glyphs overprint when their advances overlap; the lean itself is not
    measured. An upright glyph (shear 0) is returned unchanged."""
    s = g[9] if len(g) > 9 else 0.0
    if not s:
        return g
    w = (g[3] - g[1]) - abs(s) * (g[4] - g[2])
    return (g[0], g[10], g[2], g[10] + w) + tuple(g[4:])


def overprint(D):
    out = []
    seen = set()
    for pi in range(len(D.pages)):
        gl = [upright(g) for g in D.all_glyphs if g[0] == pi and not g[7].isspace()]
        grid = collections.defaultdict(list)
        for i, g in enumerate(gl):
            c = _core(g)
            for gx in range(int(c[0] // 20), int(c[2] // 20) + 1):
                for gy in range(int(c[1] // 20), int(c[3] // 20) + 1):
                    grid[(gx, gy)].append(i)
        pairs = collections.Counter()
        ex = {}
        for cell in grid.values():
            for a in range(len(cell)):
                for bb in range(a + 1, len(cell)):
                    i, j = cell[a], cell[bb]
                    g, h = gl[i], gl[j]
                    # same line, same run: neighbours touching is normal
                    cy_g = (g[2] + g[4]) / 2
                    cy_h = (h[2] + h[4]) / 2
                    if (
                        abs(cy_g - cy_h) < 0.25 * min(g[5], h[5])
                        and g[8] == h[8]
                        and g[8] is not None
                    ):
                        continue
                    if abs(cy_g - cy_h) < 0.25 * min(g[5], h[5]) and (
                        min(g[3], h[3]) - max(g[1], h[1])
                    ) < 0.3 * min(g[3] - g[1], h[3] - h[1]):
                        continue  # adjacent runs on one line touching (kerning across a node edge)
                    if g[7] == h[7] and abs(g[1] - h[1]) < 0.5 and abs(g[2] - h[2]) < 0.5:
                        continue  # the same glyph painted twice (an SVG label's stroke halo under its fill)
                    c1, c2 = _core(g), _core(h)
                    ix = min(c1[2], c2[2]) - max(c1[0], c2[0])
                    iy = min(c1[3], c2[3]) - max(c1[1], c2[1])
                    if ix <= 0 or iy <= 0:
                        continue
                    area = ix * iy
                    small = (
                        min((c1[2] - c1[0]) * (c1[3] - c1[1]), (c2[2] - c2[0]) * (c2[3] - c2[1]))
                        or 1
                    )
                    if area / small < 0.15:
                        continue
                    if (
                        g[8] is None
                        and abs(cy_g - cy_h) < 0.25 * min(g[5], h[5])
                        and not (g[7].isalnum() and h[7].isalnum() and area / small >= 0.4)
                    ):
                        continue  # owner-less (PDF-only) same-line pair: kerned punctuation touches
                    key = (min(g[8] or -1, h[8] or -1), max(g[8] or -1, h[8] or -1))
                    pairs[key] += 1
                    ex[key] = (g, h)
        for key, cnt in pairs.items():
            if (pi, key) in seen:
                continue
            seen.add((pi, key))
            g, h = ex[key]
            la = D.label(g[8]) if g[8] in D.nodes else repr(g[7])
            lb = D.label(h[8]) if h[8] in D.nodes else repr(h[7])
            out.append(
                finding(
                    "overprint",
                    pi,
                    f"{la} OVER {lb}",
                    {
                        "glyph_pairs": cnt,
                        "at_mm": [round(g[1] / PT_PER_MM), round(g[2] / PT_PER_MM)],
                    },
                    "blocking",
                )
            )
    # glyphs of other content inside a figure's ink zone
    for b in D.order:
        if D.nodes[b]["type"] != 1 or D.name(b) != "SVG" or not D.glyphs(b):
            continue
        set(id(g) for g in D.glyphs(b))
        for pi, gs in per_page(D.glyphs(b)).items():
            x0 = min(g[1] for g in gs)
            y0 = min(g[2] for g in gs)
            x1 = max(g[3] for g in gs)
            y1 = max(g[4] for g in gs)
            ph = D.pr_bottom - D.pr_top
            draws = [
                d
                for d in D.pages[pi]["draws"]
                if (d[3] - d[1]) < 0.9 * ph and not (len(d) > 6 and d[6])
            ]
            grew = True
            while grew:
                grew = False
                for d in draws:
                    if d[0] <= x1 and d[2] >= x0 and d[1] <= y1 and d[3] >= y0:
                        nx0, ny0, nx1, ny1 = (
                            min(x0, d[0]),
                            min(y0, d[1]),
                            max(x1, d[2]),
                            max(y1, d[3]),
                        )
                        if (nx0, ny0, nx1, ny1) != (x0, y0, x1, y1) and (ny1 - ny0) < 0.9 * ph:
                            x0, y0, x1, y1 = nx0, ny0, nx1, ny1
                            grew = True
            fig = next((a for a in D.ancestors(b) if D.name(a) == "FIGURE"), b)
            figset = set(id(g) for g in D.glyphs(fig))
            intr = [
                g
                for g in D.all_glyphs
                if g[0] == pi
                and id(g) not in figset
                and not g[7].isspace()
                and g[1] < x1 - 1
                and g[3] > x0 + 1
                and g[2] < y1 - 1
                and g[4] > y0 + 1
            ]
            # only count intruders whose CORE is inside: a caption line touching the edge is not ink sharing
            intr = [g for g in intr if _core(g)[1] < y1 - 1 and _core(g)[3] > y0 + 1]
            if intr:
                owners = collections.Counter(g[8] for g in intr)
                for o, cnt in owners.most_common(3):
                    out.append(
                        finding(
                            "overprint",
                            pi,
                            f"{D.label(o) if o in D.nodes else '?'} OVER figure {D.label(fig)}",
                            {
                                "glyphs_inside_figure_ink": cnt,
                                "figure_box_mm": [round(v / PT_PER_MM) for v in (x0, y0, x1, y1)],
                            },
                            "blocking",
                        )
                    )
    return out


def outside_printable(D, tol=1.0):
    out = []
    for pi, p in enumerate(D.pages):
        bad = collections.defaultdict(lambda: [0, 0.0])
        for g in D.all_glyphs:
            if g[0] != pi or g[7].isspace():
                continue
            over = max(D.pr_left - g[1], g[3] - D.pr_right, D.pr_top - g[2], g[4] - D.pr_bottom)
            if over > tol:
                k = g[8]
                bad[k][0] += 1
                bad[k][1] = max(bad[k][1], over)
        for k, (cnt, over) in bad.items():
            out.append(
                finding(
                    "outside-printable",
                    pi,
                    D.label(k) if k in D.nodes else "unowned glyphs",
                    {"glyphs": cnt, "max_over_mm": round(over / PT_PER_MM, 1)},
                    "blocking",
                )
            )
        dr = [
            d
            for d in p["draws"]
            if not (len(d) > 6 and d[6])
            and max(D.pr_left - d[0], d[2] - D.pr_right, D.pr_top - d[1], d[3] - D.pr_bottom) > tol
            and not (d[0] <= 0.5 and d[1] <= 0.5 and d[2] >= p["w"] - 0.5 and d[3] >= p["h"] - 0.5)
        ]
        if dr:
            over = max(
                max(D.pr_left - d[0], d[2] - D.pr_right, D.pr_top - d[1], d[3] - D.pr_bottom)
                for d in dr
            )
            out.append(
                finding(
                    "outside-printable",
                    pi,
                    f"{len(dr)} drawing(s)",
                    {"max_over_mm": round(over / PT_PER_MM, 1)},
                    "fix",
                )
            )
    return out


def starved_column(D):
    """A table cell whose prose wraps one or two words per line (at least 4 lines, three quarters of
    them that short). Not a starved column: a cell of code (mostly monospace), and a LIST that wraps
    at its own separators -- "poetry · maps · stories" one name per line reads as a column of
    names; a cell counts as such a list when at least
    three quarters of its line breaks fall right after a separator glyph."""
    out = []
    for b in D.order:
        if D.nodes[b]["type"] != 1 or D.name(b) not in ("TD", "TH"):
            continue
        gl = D.glyphs(b)
        if not gl:
            continue
        mono = sum(1 for g in gl if MONO.search(g[6]))
        if mono > 0.5 * len(gl):
            continue
        for pi, gs in per_page(gl).items():
            ls = lines(gs)
            wc = [len([w for w in line_words(L) if any(ch.isalnum() for ch in w)]) for L in ls]
            at_sep = sum(1 for L in ls[:-1] if L[2][-1][7] in ITEM_SEPARATORS)
            if len(ls) > 1 and at_sep >= 0.75 * (len(ls) - 1):
                continue
            if len(ls) >= 4 and sum(1 for w in wc if w <= 2) >= max(4, 0.75 * len(ls)):
                width = max(g[3] for g in gs) - min(g[1] for g in gs)
                out.append(
                    finding(
                        "starved-column",
                        pi,
                        D.label(b),
                        {
                            "lines": len(ls),
                            "words_per_line": wc[:12],
                            "text_width_mm": round(width / PT_PER_MM),
                        },
                        "blocking",
                    )
                )
    return out


def broken_token(D):
    """a line break inside ONE source text node between two glyphs that had no
    whitespace between them in the source.  A <wbr> splits the source into two
    text nodes, so a designed break point is never inside one node."""
    out = []
    for b, gs in D.own_glyphs.items():
        n = D.nodes.get(b)
        if not n or n["type"] != 3:
            continue
        if not any(MONO.search(g[6]) for g in gs):
            continue
        seq = []
        for pi, pg in sorted(per_page(gs).items()):
            for li, L in enumerate(lines(pg)):
                seq += [(g, (pi, li)) for g in L[2]]
        src = [(i, ch) for i, ch in enumerate(n["text"]) if not ch.isspace()]
        if len(src) != len(seq):
            continue  # cannot align glyphs to source (ligature, PUA digits); skip, counted below
        for k in range(1, len(seq)):
            (ga, la), (gc, lc) = seq[k - 1], seq[k]
            if la == lc:
                continue
            ia, ic = src[k - 1][0], src[k][0]
            if ic > ia + 1:
                continue  # whitespace between them in the source: a normal wrap
            txt = n["text"]
            s0 = ia
            while s0 > 0 and not txt[s0 - 1].isspace():
                s0 -= 1
            e0 = ic
            while e0 < len(txt) and not txt[e0].isspace():
                e0 += 1
            sev = "blocking" if (ga[7].isalnum() and gc[7].isalnum()) else "info"
            par = n["parent"]
            out.append(
                finding(
                    "broken-token",
                    ga[0],
                    D.label(par) if par in D.nodes else repr(txt[:30]),
                    {"break": f"{ga[7]}|{gc[7]}", "token": txt[s0 : ia + 1] + "|" + txt[ic:e0]},
                    sev,
                )
            )
    return out


def figure_text_floor(D):
    out = []
    for b in D.order:
        if D.nodes[b]["type"] != 1 or D.name(b) != "SVG":
            continue
        gs = [g for g in D.glyphs(b) if not g[7].isspace()]
        small = [g for g in gs if g[5] < FLOOR_PT - 0.05]
        if small:
            fig = next((a for a in D.ancestors(b) if D.name(a) == "FIGURE"), b)
            out.append(
                finding(
                    "figure-text-below-floor",
                    small[0][0],
                    D.label(fig),
                    {
                        "min_pt_on_paper": round(min(g[5] for g in small), 1),
                        "glyphs_below": len(small),
                        "glyphs": len(gs),
                        "floor_pt": FLOOR_PT,
                    },
                    "blocking",
                )
            )
    return out


SECTION_RE = re.compile(r"^\s*(\d+)\.\s")


def section_not_page_start(D):
    """a numbered top-level heading ("3. ...") must open its page: either it sits inside
    (or is) an element with a forced break-before that starts on the heading's page,
    or nothing is printed above it on its page"""
    out = []
    for b in D.order:
        if D.nodes[b]["type"] != 1 or not re.fullmatch(r"H[1-3]", D.name(b)) or not D.glyphs(b):
            continue
        if not SECTION_RE.match(D.text(b)):
            continue
        hp = min(g[0] for g in D.glyphs(b))
        htop = min(g[2] for g in D.glyphs(b) if g[0] == hp)
        cont = next(
            (
                a
                for a in [b] + list(D.ancestors(b))
                if D.nodes[a]["style"]
                and D.style(a, "break-before") in ("page", "always", "left", "right")
            ),
            None,
        )
        if cont is not None and min(g[0] for g in D.glyphs(cont)) == hp:
            continue
        above = [g for g in D.all_glyphs if g[0] == hp and g[4] <= htop and not g[7].isspace()]
        if not above:
            continue
        out.append(
            finding(
                "section-not-page-start",
                hp,
                D.label(b),
                {"lines_above_on_page": len(lines(above)), "has_forced_break": cont is not None},
                "blocking",
            )
        )
    return out


def text_not_printed(D):
    """a rendered text node whose glyphs are missing from the PDF: Chromium drops
    glyphs that fall wholly outside the page's clip, so content pushed off the
    printable area leaves no ink at all -- the readback cannot see it, only the
    shortfall between the source characters and the glyphs printed for them"""
    from .model import rendered_text_nodes

    out = []
    for b in rendered_text_nodes(D.nodes):  # laid out in a text box: a closed <details> is not
        n = D.nodes[b]
        want = sum(1 for ch in n["text"] if not ch.isspace())
        got = sum(1 for g in D.own_glyphs.get(b, []) if not g[7].isspace())
        if got < want:
            pg = (
                D.own_glyphs[b][0][0]
                if D.own_glyphs.get(b)
                else (D.pages_of(n["parent"])[0] if D.glyphs(n["parent"]) else 0)
            )
            out.append(
                finding(
                    "text-not-printed",
                    pg,
                    D.label(n["parent"]),
                    {"source_chars": want, "glyphs": got, "text": n["text"].strip()[:40]},
                    "blocking",
                )
            )
    return out


def run_all(m):
    D = Doc(m)
    leaves = D.leaf_blocks()
    F = []
    F += split_container(D, ("TABLE",), "split-table")
    F += split_container(D, ("UL", "OL", "DL"), "split-list")
    F += row_split(D)
    F += heading_stranded(D, leaves)
    F += intro_stranded(D, leaves)
    F += runt_pages(D)
    F += orphan_widow(D, leaves)
    F += overprint(D)
    F += outside_printable(D)
    F += starved_column(D)
    F += broken_token(D)
    F += figure_text_floor(D)
    F += section_not_page_start(D)
    F += text_not_printed(D)
    stats = {
        "pages": len(D.pages),
        "dom_nodes": len(D.nodes),
        "glyphs": len(D.all_glyphs),
        "unowned_glyphs": sum(D.unowned.values()),
        "leaf_blocks": len(leaves),
    }
    return F, stats
