# FINAL REPORT — stock-arm DOM scope

## 1. Commits to lift, oldest first

1. `feat(devcards): measure reference-family DOM scope`
2. `docs(devcards): state the reference-family DOM boundary`

The first commit adds only a read-only development probe. The second records
the scope decision in the CLI contract and canonical UI-quality contract,
regenerates the standard brief, and adds this report.

## 2. Family-0-only premise

Verified. `devcards.core/run-generate` is explicit:

```clojure
(let [dark (render-one! pb {:family 0 :dark true :dump? true})
      light (render-one! pb {:family 0 :dark false})]
  [(str id)
   {:dark-hash (golden/sha256-hex (:fb dark))
    :light-hash (golden/sha256-hex (:fb light))
    :expect expect
    :tree (:tree dark)}])
```

Families 1 and 2 go through a framebuffer-only function:

```clojure
fam-hashes (fn [family dark]
             (into {}
                   (map
                    (fn [{:keys [id] ^bytes pb :bytes}]
                      [(str id)
                       (golden/sha256-hex
                        (:fb (render-one! pb {:family family :dark dark})))]))
                   built))
```

Only `f0` supplies trees to the invariant lane:

```clojure
inv (vec (mapcat (fn [[id {:keys [expect tree]}]]
                   (lanes/atomic-findings id expect tree))
                 f0))
```

The same family-0-only routing is present in the composition arm. The
vanilla/stock arms are compared by per-card framebuffer hash in dark and light
modes; they are not dumped or DOM-judged.

## 3. Missing-glyph diagnosis

The raster verdict is **absent output, not a glyph painted in the band
colour**. The DOM verdict is narrower: the dump cannot identify the selected
text run, so the DOM alone cannot distinguish a failed post-draw from invisible
text.

For `lv_roller/default/small/mid`, dark:

```text
-- family 0 (asgard) --
lv_roller        coords=[0 0 47 115]
                 flags={:overflow true}
                 text_on={:part "selected", :color "#ffffff", :bg "#0e7490"}
lv_roller_label  coords=[18 -27 28 142]
                 flags={:clipped true, :offscreen true}
                 text=nil
band=39..77; glyph ink appears at 52..63
lanes/atomic-findings: []

-- family 1 (vanilla) --
lv_roller        coords=[0 0 47 117]
                 flags={:overflow true}
                 text_on={:part "selected", :color "#ffffff", :bg "#2196f3"}
lv_roller_label  coords=[19 -26 29 143]
                 flags={:clipped true, :offscreen true}
                 text=nil
band=40..78; no glyph ink appears anywhere in the band
lanes/atomic-findings: []
```

Family 2 is byte-identical to family 1 in both framebuffer and dump tree.

The raw dump flags are real, but the existing roller-drum designed-geometry
rules correctly exclude them in every family; they describe the scrolling
label escaping its roller, not the missing selected redraw. The internal
`lv_roller_label` has no `:text` value even in the good Asgard control.
Consequently, missing `:text` cannot be read as missing glyph output, and
`text_on` describes only the selected part's declared colour pair.

The probe's threshold-free `band` arm removes the remaining pixel ambiguity.
Every vanilla/stock scanline from `y=40` through `y=78` contains only the blue
fill and the constant side-border colour. There are no white or anti-aliased
glyph colours. The same method sees the Asgard `3`, and a medium vanilla card
draws white selected text on the same blue fill.

### Root cause

The failure is the interaction between the corpus's 48 px card width and
stock's large-display insets. Vendored `lv_roller` constructs the selected
post-draw box from the main-part padding and border:

```c
label_sel_area.x1 = obj->coords.x1 + pleft + bwidth;
label_sel_area.x2 = obj->coords.x2 - pright - bwidth;
```

At the pinned 800×480 canvas and 160 DPI, stock/vanilla use 24 px left and
right padding plus 2 px borders. For coordinates `0..47`, the selected text box
is therefore `26..21`, an inverted area that contributes no pixels. Asgard
overrides the horizontal roller padding to 8 px, so its selected draw box
remains positive. A 120 px medium card also remains positive under stock.

No vendored LVGL source was changed, and the downstream glyph was deliberately
not fixed.

## 4. Scope decision

No new clause is armed.

Protogen's DOM producers continue to judge Asgard, the shipped family whose
theme and findings protogen owns. Vanilla and stock remain differential
reference controls. Their equality proves that the child theme reproduces
stock; it does not certify either reference render as usable or defect-free.
The boundary is now explicit in both required homes, and the generated standard
brief carries the canonical text.

A general atomic DOM run over the reference families produces 46 live findings
in vanilla and the identical 46 in stock, versus zero in Asgard:

| invariant | vanilla | stock |
|---|---:|---:|
| `:clipped` | 21 | 21 |
| `:overflow` | 19 | 19 |
| `:scrollable_overflow` | 6 | 6 |
| **total** | **46** | **46** |

All 46 in either family are novel relative to family 0. None is on the
motivating roller. Arming those lanes would therefore add a reference-control
finding population without observing the selected-text post-draw failure.

The proposed narrow `text_on.color != text_on.bg` clause is also not armed. It
would pass this broken card because the dump already declares white on blue,
and the internal label provides no text/run identity. Such a check proves only
that declared colours differ; it cannot prove that glyph pixels were drawn.
A future glyph-presence clause needs a positive pixel-level instrument and its
own mutation canary.

Deliberately not armed:

- DOM invariant lanes over vanilla or stock;
- a declared-colour-pair legibility clause;
- any exemption or suppression.

A consumer that ships a non-Asgard family as a product surface does not inherit
this reference-control boundary; it must judge every family it ships.

## 5. Re-runnable measurement

The committed probe is `tools/devcards/dev/stockarm_scope_probe.clj`. It uses a
temporary command-line alias so `deps.edn` remains unchanged.

```bash
# Atomic-family census
tools/uber.sh "cd tools/devcards && clojure \
  -Sdeps '{:aliases {:p {:extra-paths [\"dev\"]}}}' \
  -M:bindings:p -m stockarm-scope-probe"

# DOM plus thresholded ink profile
tools/uber.sh "cd tools/devcards && clojure \
  -Sdeps '{:aliases {:p {:extra-paths [\"dev\"]}}}' \
  -M:bindings:p -m stockarm-scope-probe \
  dom lv_roller/default/small/mid"

# Unthresholded band scanlines
tools/uber.sh "cd tools/devcards && clojure \
  -Sdeps '{:aliases {:p {:extra-paths [\"dev\"]}}}' \
  -M:bindings:p -m stockarm-scope-probe \
  band lv_roller/default/small/mid"
```

Observed census: 236 atomic cards × 3 families, 708 renders, 21.2 seconds.

## 6. Canary mutation proofs

Not applicable: no producer, clause, threshold, or exemption was added. A
mutation canary for an unarmed rule would not prove any production path.

The new probe is read-only and non-gating. If a glyph-presence producer is
added later, its landing requirements remain: assert that a mutation landed,
make that producer's canary fail, and prove no unrelated producer caused the
failure.

## 7. Exact shared forms touched

`tools/devcards/src/devcards/core.clj`:

- the docstring of the existing `(ns devcards.core ...)` form, specifically
  the family-0 and families-1/2 bullets.

`tools/devcards/src/devcards/lanes.clj`:

- none.

No executable Clojure form, producer vector, threshold, exemption, renderer
source, corpus card, golden, or gallery artifact changed.

`.claude/skills/ui-standard-review/STANDARD.md` was regenerated by
`devcards.standard-brief` from the canonical UI-quality document during the
required renderer battery; it was not independently authored.

## 8. Verification

- `tools/uber.sh 'make -f renderer.mk bindings'` — green, 321 classes.
- Probe diagnosis — vanilla/stock framebuffer equality and dump equality both
  true; live findings for the named card are empty in all three families.
- Probe census — Asgard 0, vanilla 46, stock 46.
- `tools/uber.sh 'make -f renderer.mk check-renderer'` — green:
  265 Clojure tests / 836 assertions, 11 renderer-generator tests /
  26 assertions, 1,416 atomic renders and 60 composition renders with zero
  findings, all renderer harness suites, 92/92 coverage-matrix cases, and
  3/3 demo-parity tabs.
- `make -f lint.mk lint` — green; cljfmt clean, clj-kondo 0 errors /
  0 warnings, shell and C-format checks green.
- `git diff --check` — green.

## 9. Per-consumer consequences

- UI-AST consumers: bump-only. Read the clarified reference-control boundary;
  no gate, threshold, exemption, renderer behaviour, or corpus contract
  changed.
- Wire consumers: bump-only. No protobuf, manifest, descriptor, or generated
  binding changed.
- Generated binding repositories: no regeneration is required.

## 10. What the SENDOFF got wrong

The valuable correction is that **family routing is not why the roller defect
escapes the DOM oracle**. Running the existing DOM lanes over vanilla and stock
still returns no finding for this card. The selected text is painted by a
roller post-draw operation with no separately dumped text-run node, while the
raw roller-label flags describe intentional drum geometry and occur in the good
Asgard render too.

The suggested binary “present-but-invisible or absent, as reported by the DOM”
also overstates the dump. The raster output is absent, and the declared colour
pair rules out a same-colour declaration, but the DOM does not expose enough
information to decide whether the post-draw was clipped, skipped, transparent,
or otherwise contributed no pixels.

Finally, the symptom is not merely “SMALL.” It is a width/inset inequality.
The selected draw area has inclusive width
`w - pleft - pright - 2*bwidth`; stock's current values require `w >= 53` for
even one drawable pixel. The 48 px corpus card crosses that boundary, while the
120 px medium card does not.
