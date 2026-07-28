# VLM read-back calibration protocol

**A method for manufacturing a labelled set, ONCE, against which a deterministic
pixel-domain metric can later be validated.** A vision model is shown a render
containing a random string it cannot have seen and cannot guess, and is asked to
type it back. The reply is parsed by a fixed rule and compared to the string that
was rendered, by exact match and by edit distance. Nothing here is an opinion,
and nothing here is a gate.

The instrument is `tools/devcards/dev/readback_strings.clj`; its unit proofs are
`tools/devcards/test/devcards/readback_strings_test.clj`, armed in
`make -f renderer.mk devcards-test`; its mutation proof is
`tools/devcards/dev/readback_mutate.sh` with committed output beside it.

**NOTHING IN THIS DOCUMENT HAS BEEN RUN.** No campaign exists, no model has been
called, and no number below is a measurement of any model. What exists is the
protocol and the stimulus instrument. Read every threshold-shaped noun here as a
slot a campaign would fill, never as a result.

---

## 0. Three things this is NOT

**NOT A GATE, AND IT MUST NEVER BECOME ONE.** Every other lane in this standard
is reproducible from source; a model is not. `docs/UI-QUALITY-CONTRACTS.md` §0
forbids a verdict implying more than its measurement can see, and a verdict whose
oracle can change under you without a commit sees less than it claims. The whole
point of a calibration protocol is that it runs ONCE and a deterministic producer
reproduces its content forever. (Nothing makes this *impossible* — the
prohibition is a decision, and it is stated as one rather than dressed as a law
of nature.)

**NOT AN ADJUDICATOR.** §0 reserves the three-way (pass/fail/uncertain) shape for
a measurement whose separating gap is narrower than its own SEED-TO-SEED noise,
and requires that any adjudicator be *validated as a classifier on a held-out
labelled set before it is wired in*. This protocol manufactures that held-out
set. It is the input to a validation, never the classifier.

**NOT A LEGIBILITY MEASUREMENT.** §0 puts hardware-scoped quantities — sunlight,
darkness, a panel revision, angular character size, chromaticity under night
vision — OUT OF SCOPE for this repository: not pending, OUT. They belong to a
bench and a hardware revision. A read-back campaign imposes none of those
conditions, so no result from one may name one.

The one sanctioned sentence a positive result may carry is
`readback-strings/pass-message`:

> a machine reader recovered the string at level `<level>`

**What the instrument does and does not enforce, precisely.** It SUPPLIES
`claim-problems`, a checker over `banned-claim-tokens`, and holds its own
manifest scope to it — `-main` refuses rather than prints if the scope
over-claims. It does NOT and cannot police a campaign's report, which is written
outside this repository and routed through nothing. **A campaign must call
`claim-problems` on its own output**; the instrument supplies the vocabulary and
the function, not the guarantee. And the list is a FLOOR, not a proof: no token
list can be shown adequate against a phrase nobody thought of.

---

## 1. SUBJECT, not JUDGE — and the residue that survives the distinction

An LLM-as-judge is asked for an opinion about quality; its answer is the
measurement, and the failure modes are all in the answer — self-preference,
position bias, verbosity bias, miscalibration against a rubric it also wrote.
None of that applies here. The model is given a TASK and scored against a ground
truth it does not have. The truth is generated, not solicited; the metric is edit
distance, not agreement; and the model's confidence, reasoning and preferences
are discarded.

**But two judge-shaped hazards DO survive, and both manufacture FALSE POSITIVES
— the one error direction that breaks everything downstream.**

### 1.1 The parser is an unblinded judge

The model replies with text; something must turn that text into the string that
gets scored. A reply that offers an answer and then hedges it (`aB12...` "though
it could be" `aB1Z`) hands the scorer a choice, and any rule for making that
choice AFTER the replies are in is a judgement made by someone who already knows
the answer.

Closed by construction. `readback-strings/read-back-prompt` demands the string
and nothing else, and `readback-strings/parse-reply` IS the fixed rule, shipped so
it can be pre-registered by citation rather than described:

- trim leading and trailing whitespace;
- empty → `:unparseable`;
- the sentinel → `:no-recovery`;
- contains any interior whitespace → `:unparseable`;
- anything else → `:recovered`, **verbatim**.

**Verbatim is the load-bearing word.** No case folding, no quote stripping, no
restriction to the alphabet: a reply of `o` where `0` was rendered must reach the
scorer as `o`, or the confusion it represents is silently repaired into a correct
answer and no downstream number can recover it.

**STRICTNESS IS NOT FREE.** A hedged-but-correct reply rejected here suppresses a
POSITIVE — the only label a model can produce (§7) — so the cost is statistical
power and a threshold estimate biased toward severity. What it buys is that no
scoring decision is ever made by a human holding the answer key. That trade is
declared, not free, and a campaign should report its unparseable rate.

**The give-up sentinel is `NORECOVERY`, and the spelling is load-bearing.** An
earlier draft used `UNREADABLE`, which asks the model for a verdict about the
IMAGE and hands the campaign a token that reads like a negative label — §2.1's
first inversion site, invited by the instrument itself. `NORECOVERY` states only
what happened to this reader. `readback-strings/grade` maps it, and every
unparseable reply, to a MISS **with no similarity score at all**: an edit distance
between a 12-character string and the literal token `NORECOVERY` is a number with
no meaning, and a threshold search that pooled it would be fitting to the
sentinel's spelling.

**A cell of nothing but sentinels is not reproducible, it is empty.** §6's
"number of distinct responses" would report 1 for such a cell. Count outcomes
before counting spread.

### 1.2 Context contamination across cells

One cell per request, one image per request, no conversation history, no batching
of cells into a single context. A model that has already seen five strings from
the same generator has a better prior on the sixth, and a contact sheet showing
neighbouring cells hands it format, length and alignment cues at once. The corpus
is addressed per cell for exactly this reason.

Two more the distinction really does dispose of, and they should not be
re-litigated: **memorisation** (the strings do not exist until the corpus is
generated, and the master seed never leaves the harness) and **self-preference**
(there is nothing to prefer — the score does not depend on who produced the
render).

---

## 2. THE CLAIM IS ONE-DIRECTIONAL, and it is weakest exactly where the number is read

The motivating claim is that a model recovering a string is CONSERVATIVE
evidence: vision models are measurably worse than people at low-level visual
tasks, and their encoders downsample to patches, so small dense glyphs should be
where they are worst. Model-readable would then imply human-readable, and never
the converse.

**Treat that as a PRIOR, not as a bound, and do not let the protocol rest on
it.** Two reasons, and the second is the serious one.

- The published weakness is an aggregate over low-level vision probes — counting,
  intersection, adjacency, geometric relations. Reading rendered text is not one
  of those; it is the single most heavily represented capability in a vision
  model's training distribution. A general result about low-level vision does not
  transfer to the one sub-task the training data is saturated with.
- **The implication is least safe at the threshold, which is the only place a
  campaign reads a number.** The threshold is by definition the most degraded
  level at which the model still succeeds — precisely where a learned denoising
  prior does the most work and where an untrained eye has the least to go on. The
  argument is strongest in the easy regime, where nobody needs it, and weakest at
  the crossing.

**The resolution is to delete the dependency rather than to defend it.** This
protocol measures MACHINE RECOVERABILITY from a rendered framebuffer. That is an
objective quantity in its own right, it is the quantity a pixel-domain
deterministic metric is actually about, and calibrating one against the other
needs no human bridge at all. The human conclusion is PDL-HW, which §0 has
already placed out of scope. Nothing downstream of this protocol should require
the bridge; where a campaign genuinely needs one, it owes a human anchor arm this
repository cannot run (§10).

### 2.1 Where the direction quietly inverts

Five sites. The third is the dangerous one because it looks like ordinary
calibration.

1. **Failure read as unreadability.** "The model missed at level 7, so level 7 is
   unreadable" is the converse. Model failure yields UNKNOWN (§7), never a
   negative label. §4.3 is where this rule is easiest to break by accident.
2. **Threshold read as a floor.** "The threshold is level *k*" becoming "level
   *k* is enough" converts a positive at *k* into a negative below *k*.
3. **Using the labels to LOOSEN a deterministic check.** A metric that flags a
   cell the model read is over-strict *for machine reading*. Relaxing a
   human-facing floor on that evidence is the converse laundered through
   arithmetic. In particular: `docs/PROVEN-PAIRS.md` scores declared (ink, fill)
   pairs against 4.5:1 (WCAG AA body text) and this repository's governing
   MIL-STD-1472H 5.2.2.7 floor of 6.0:1. **No read-back result may move either
   number.** They are human-factors thresholds for an operator under conditions
   no campaign here imposes.
4. **A "readable" column in a report.** Emit `pass-message`; never a rephrasing.
   `claim-problems` exists so this is checkable rather than noticed.
5. **The confusable statistic.** "The model rarely confused 0/O, so the font's
   disambiguation works for people" is the converse, and it additionally rests on
   a metric that under-counts confusion by construction (§4.4).

---

## 3. The stimulus

### 3.1 Strings

`readback-strings/gen-string` draws from a 30-character alphabet with EXACTLY `k`
characters from the cockpit confusable set at uniformly chosen positions, every
other position from a singleton pool. The construction is uniform over that
constrained set, which makes `entropy-bits` a closed form rather than an
estimate.

- Confusable set (emitted): `0 O 1 l I 5 S 8 B 2 Z`.
- Singleton pool: `4 9 A C E F H J K M N P R T U V W X Y`.
- The default configuration — 12 characters, 4 of them confusable — yields a
  space of 123 084 891 509 224 095 strings, about 56.8 bits.

**ONE RULE PRODUCED THE SINGLETON POOL: no singleton may crowd a member of a
declared class.** That removes `3` (crowds `8`/`B`), `6` (crowds `b`), `7`
(crowds `1`), `L` (crowds `l`/`I`/`1`), `D` and `Q` (crowd `0`/`O`), `G` (crowds
`6` and `O`), and every lower/upper pair differing only in SIZE (`c`/`C`, `v`/`V`,
`x`/`X`). `l` is the only lowercase character in the alphabet and it is there on
purpose, as a member of the 1/l/I class.

**THE RULE IS A JUDGEMENT AND ITS COVERAGE IS PARTIAL, both stated rather than
hidden.** It says nothing about SINGLETON-TO-SINGLETON confusion — `C` against
`U`, `U` against `V`, `M` against `N`, `E` against `F` are all still in the pool
— and its application is a reading of glyph shapes, not a measurement. §4.4 names
the instrument that would replace the judgement with arithmetic, and **it has not
been run**. Every undeclared confusion scores as an ordinary substitution, so
confusion is UNDER-reported: the direction that flatters the font.

Every character lies inside `0x20-0x7E`. That is the shared ASCII BASE range, not
the whole range: `tools/gen_fonts.sh` gives each family its own `--range` (B612
Mono adds U+00B1 and U+2192; Orbitron, lacking those glyphs, does not), but
`0x20-0x7E` is what both carry at every compiled size. A character outside it has
no glyph, LVGL draws nothing, and the cell is voided without the run noticing.
This is a test clause, not a convention.

`gen-corpus` REFUSES a configuration whose entropy falls below `min-entropy-bits`
(40.0). Below roughly that point the a-priori chance of naming a string blind
stops being negligible against a campaign's cell count, and the no-image control
arm stops being a formality and becomes the measurement.

### 3.2 The stimulus is addressed separately from the cell, and that is the point

`stimulus-id` is `(content, draw)`. `cell-id` is `(content, level, draw)`. **The
string is seeded from the STIMULUS, so every rung of one cell's degradation
ladder renders the SAME string.**

This is not bookkeeping. If each rung drew a fresh string, a level-to-level
difference would carry string-difficulty variance — and the replicate axis (§6)
holds the string fixed by definition, so nothing in the protocol could estimate
it. A non-monotonic ladder would then look like a render defect while being a
property of the draw. Three variance axes, cleanly separated:

| axis | varies | estimates |
|---|---|---|
| **LEVEL** | the degradation, within one stimulus | the response curve — the thing being measured |
| **DRAW** | the string, across the whole ladder | string-difficulty variance |
| **RUN** | nothing | model run-to-run variance (§6) |

`:draws` defaults to **1**, which is enough to render a ladder and **not enough
to tell a hard string from a hard level**. A campaign needs several; the
instrument cannot tell the difference, so it does not refuse.

### 3.3 The render

Not delivered here (§10). What a harness owes:

- **One string per image, one image per request.** No contact sheets.
- **The cell id may be captioned or logged.** Both ids are functions of the grid
  coordinates only and are proven independent of the string.
- **The answer key never enters a model's context.** The corpus file IS the
  answer key; the CLI prints that warning on every run.
- **Hold every non-swept variable fixed and record it**: font family and size,
  widget class, string length, character grouping, background, padding, and the
  level of every other axis.

### 3.4 The image pipeline must be INFORMATION-NON-INCREASING

Whatever transform sits between the framebuffer and the model must not add
information that was not in the render.

- Prefer a lossless encoding of the raw framebuffer. A lossy encoding only
  removes information, so a positive under lossy remains a positive — but the
  level then labels the encode, not the render, which is why `:image-encoding` is
  a required provenance key.
- Integer upscaling with nearest-neighbour is permitted; it adds nothing.
- **Forbidden: any learned or text-tuned processing.** Super-resolution,
  sharpening, denoising, contrast stretching, binarisation, deskewing.
- **Forbidden: giving the model tools.** No OCR engine, no image editing, no code
  execution, no zoom-and-enhance loop. An OCR engine is a specialised detector,
  and measuring it measures the detector rather than the render — it also
  destroys even the prior in §2, since a purpose-built glyph detector is not
  plausibly worse than a person at glyph detection.

---

## 4. The sweep

### 4.1 The ladder is CONSTRUCTED, not harvested

A single degradation level yields a binary per cell and calibrates nothing. Sweep
a ladder and report the whole response curve; the threshold is a summary OF the
curve, not a substitute for it.

**The recommended level axis is token contrast ratio** — WCAG 2.x
relative-luminance ratio between the ink colour and the fill colour. It is exact
arithmetic on values this repository holds, it is the same arithmetic
`tools/devcards/dev/proven_pairs.clj` and
`tools/devcards/dev/disabled_pair_probe.clj` already use (so a declared ratio and
a measured one compare digit for digit), and it needs no hardware constant.

**THE LADDER MUST BE AUTHORED. Do not build it from the declared theme pairs.**
`docs/PROVEN-PAIRS.md` reports the declared pairs — it is GENERATED, so read the
count off its own header rather than from here, and those
rows span 1.76:1 to 16.18:1 — but that span is across DIFFERENT hue families,
while a cell holds the hue family fixed. Hold it fixed and the declared table
offers roughly three rungs (`fg-0` on the dark surfaces: 16.18, 15.22, 13.46;
`fg-1` on dark surface-and-pressed: 6.58, 5.82, 4.97) and **nothing below 4.97:1
inside any one family**. A ladder harvested from that table cannot reach the
region a threshold lives in. Author ink/fill values that sweep the ratio
continuously; the declared table is context for where the shipped theme sits, not
a source of rungs.

**A declared ratio is not always the drawn ratio.** `docs/PROVEN-PAIRS.md`'s
`as drawn` column exists for this: where a context fades the FILL (the `opa-`
class prefix resolves to `bg-opa`, glyphs untouched) the rendered pair is a token
ink over a blend, and the whole-widget `layer-opa` re-composites both ends.
Either author the ladder on un-faded widgets, or take the level from the dump
rather than from the declaration — and record which.

Font size is a legitimate second axis (B612 Mono Bold is compiled at 12, 14, 16,
18 and 20). String length is a third.

**A CELL is the tuple of everything held fixed** — font family, size, widget
class, content class, hue-pair family — and the level varies within it. The
threshold is a property of the cell.

**Thresholds do not transfer to unmeasured content.** §0 records that at a fixed
`montserrat_14` font, anti-aliased ink fraction varied 0.47–0.83 across tested
content; a score sensitive to the ink/AA mix therefore moves with content. A
threshold measured on one content class is evidence about that class. (That
measurement's face is not one `tools/gen_fonts.sh` compiles — it establishes the
sensitivity, not a number for this theme.)

### 4.2 The ladder needs a constructive floor, and it is free

**Include a rung where the ink colour equals the fill colour.** Contrast ratio is
exactly 1.00, nothing is drawn, and no reader of any kind recovers anything. Two
things fall out:

- It is the campaign's cheapest leak detector. A "recovery" at ratio 1.00 means
  the answer reached the model by some path other than the pixels, and the whole
  campaign is void until that path is found.
- It is the only whole-cell NEGATIVE the protocol can honestly produce (§7), and
  it comes from arithmetic rather than from a model.

### 4.3 Do NOT bisect — and read a non-monotonic cell carefully

Bisection assumes the response is monotone in the level. That assumption is
untested, and the failure is silent: on a non-monotonic response, bisection
returns a level that depends on the probe order while reporting the same number
shape as a valid threshold. Sweep the entire ladder at every cell.

**A non-monotonic cell is a FINDING TO INVESTIGATE, and the investigation is owed
before any conclusion.** §3.2's stimulus keying removes one explanation — the
string is constant across the ladder, so the wobble is not string-difficulty
variance — but two remain, and neither is a render defect:

- **model run-to-run variance** (§6), which is why the per-cell spread must be
  published beside any curve;
- **string-by-level interaction**, visible only by comparing the same cell across
  DRAWS: a wobble that moves with the draw is a property of the strings.

Only when the wobble survives both — reproducible across runs, consistent across
draws — is a render explanation on the table. Even then, §2.1 site 1 governs what
a model FAILURE may support, which is nothing about readability. What it can
support is "the render at level *k* differs from the render at level *k+1* in a
way a reader responds to", which is a prompt to go and look at the pixels.

### 4.4 The confusable statistic, and what it cannot settle

Each string carries exactly `k` confusable characters out of `L`, so under a
position-independent error model the expected share of substitutions landing on
confusable positions is exactly `k/L`. Observed share against `k/L` is a clean
within-font statistic, and `readback-strings/align` exposes the ops a campaign
needs to compute it. `score` additionally reports how many substitutions were
WITHIN a declared class.

**This cannot answer whether B612 Mono's cockpit disambiguation earns its keep.**
That question is comparative and needs a control face at the SAME size, which the
compiled set does not offer: B612 Mono Bold is compiled at 12/14/16/18/20 and
Orbitron Bold at 22/28/32, sharing no size. The renderer's font resolution falls
back to `P:fonts/<family>.ttf` through TinyTTF
(`tools/renderer-gen/src/lvgl_codegen/font_metrics.clj`), so a matched size may
be reachable at runtime — that is UNVERIFIED here.

**And the cheaper half of that question needs no model at all.** Rasterise each
within-class glyph pair at each size, offline from the font file, and compare the
bitmaps. Two glyphs that rasterise identically at a size make every string
CONTAINING one of them unrecoverable — see §7 for why that is a per-string, not a
per-cell, verdict. **Run that before any campaign**, because it partitions the
question into a part that is free and a part that is expensive, and because
§3.1's singleton rule is currently a judgement this would turn into arithmetic.

**Why that is not the closed question.** `docs/UI-QUALITY-CONTRACTS.md` §0
permanently closes a RENDERER-SIDE per-glyph ink mask on a freedom-to-operate
question, and says the boundary "bounds the mask and nothing adjacent to it". The
check proposed here is offline against a TTF, not inside the renderer; it emits no
per-glyph coverage into any render path, computes no legibility metric, produces
no shaded output, and gates nothing — it answers "are these two glyph bitmaps
equal". **That distinction is ASSERTED here, not derived.** If a reader judges
otherwise, §0's standing instruction to drop what cannot be told for sure
governs, and this check is dropped; nothing else in the protocol depends on it,
and §3.1's rule simply stays a declared judgement.

Finally, the metric's known bias direction: an observed character in no declared
class scores as an ordinary substitution, so `D`-for-`0` is not counted as
confusion. Confusion is UNDER-reported, which flatters the font. A low
confusable-substitution count is not evidence that disambiguation is working.

---

## 5. Adversarial check A — the strings are unguessable WITHOUT the image

Two halves. The first is deterministic and is delivered; the second is a campaign
arm and is not.

**Deterministic (proven in the test suite).** Entropy at or above the declared
floor; the prompt template is CONSTANT across every cell, so it carries zero bits
about any answer; both ids are functions of grid coordinates only, so captioning
or logging them leaks nothing; per-stimulus seeds come from SHA-256 over
`master NUL stimulus-id`, so a leaked answer does not surrender the master seed
and the remaining stimuli stay unguessable; and every stimulus of a grid is
checked to hold a distinct string.

**Empirical (a campaign owes BOTH arms).**

- **NO-IMAGE ARM.** The identical prompt with no image, or with a blank canvas of
  the same size and fill. Same run count. Criterion: **zero exact matches**, and
  a mean similarity indistinguishable from the chance baseline — which is
  computable, by scoring each cell's answer against OTHER cells' strings from the
  same configuration. A single exact match here voids the campaign, not the cell.
- **WRONG-IMAGE ARM.** Cell A's image scored against cell B's answer key. This
  catches something the no-image arm cannot: a leak in the HARNESS rather than in
  the model's prior — an answer in the filename, in request metadata, in an
  alt-text field, in an EXIF comment. The two arms fail for different reasons and
  neither substitutes for the other.

**Both arms are preconditions for every POSITIVE label in the campaign** (§7), not
only for the cells they were run on. A campaign that runs one arm has no
positives.

---

## 6. Adversarial check B — temperature 0 is not determinism

**Run every cell R times on byte-identical input and PUBLISH THE SPREAD.** Not
the mean alone; the per-cell count of exact matches out of R, the count of each
`parse-reply` outcome, and the number of distinct responses.

A temperature of 0 selects the argmax; it does not make the logits reproducible.
Batch composition changes floating-point reduction order on a GPU; expert routing
in a mixture model depends on what else is in the batch; a served model version
can change under a stable name; and an API may sample regardless of the
parameter. None of that is visible from the caller.

The reporting rule cuts both ways. If any cell in the campaign produces more than
one distinct response, run-to-run variance is nonzero and every aggregate must
carry it. If every cell produces exactly one, publish THAT — as a measured
property of that campaign on that day, never as an assumption inherited by the
next one. **And check the outcomes before the spread**: a cell that answered
`NORECOVERY` R times has one distinct response and read nothing.

### 6.1 The positive label takes MAX over runs, and that has a trap

For the POSITIVE label, one exact match out of R is sufficient and R does not need
to be large. An exact match on a string of ~57 bits is not luck: the a-priori
probability is below 2⁻⁵⁶ per attempt, and §5's two control arms are what rule out
the non-luck alternatives — the model's prior, and a harness leak. Information
was recovered from those pixels on that occasion, and averaging that away with
four failures would discard a proof.

**The trap is that max-over-R is monotone in R.** A cell can be promoted to
POSITIVE by simply being retried more often, which turns run count into a free
parameter that moves labels. Close it the only way it closes: **fix and declare R
before the campaign**, record it (`:runs-per-cell` is a required provenance key),
and never retry a cell outside the declared budget.

Variance is not thereby irrelevant — it is the whole story for the CONTINUOUS
threshold estimate, which is what a deterministic metric gets regressed against.
It is only the binary positive label that takes the max.

---

## 7. What the labelled set actually contains

Three labels, and the asymmetry between them is the most important structural
fact in this document.

| label | earned by | may be used for |
|---|---|---|
| **POSITIVE** | an exact match in any of the R declared runs, in a campaign whose no-image AND wrong-image arms are clean | proving a deterministic metric is over-strict **for machine reading** at that point |
| **NEGATIVE-BY-CONSTRUCTION** | arithmetic, never a model: zero drawn contrast (ink colour = fill colour) | proving a deterministic metric fails to flag a point where nothing was drawn |
| **UNKNOWN** | everything else, including every model failure, every `NORECOVERY`, every unparseable reply | nothing |

**Model failure is UNKNOWN, never NEGATIVE.** This is §2.1's first inversion site
written into the data model so it cannot be forgotten by a later analyst who never
read §2. The permission column carries §2.1 site 3's qualifier for the same
reason: the unqualified sentence is the converse laundered through arithmetic.

**A collapsed glyph pair is a PER-STRING negative, never a per-cell one**, and the
arithmetic says so plainly. If §4.4's rasterisation check finds that (say) `0` and
`O` are identical at some size, a string is affected only when one of its `k`
confusable positions drew a member of that class. At the shipped default (`k = 4`,
11 emitted confusables) the chance a string contains NO member of one collapsed
pair is `(9/11)⁴ ≈ 0.45` — nearly half the cell is still exactly recoverable.
Labelling the whole cell NEGATIVE would manufacture false negatives and mark a
correct metric defective. Label the affected STRINGS, and only those.

The consequence of all this is that the set is **positive-only from the model**,
and its negatives come from construction. A classifier validated on it can
estimate a false-alarm rate against the positives and a hit rate against the
constructed negatives. **It cannot estimate a miss rate**, because the region
where misses live is exactly the UNKNOWN band. Any reported "accuracy" that pools
the three label classes is meaningless, and a plot that shades UNKNOWN the same
colour as NEGATIVE is the same error drawn.

### 7.1 The held-out split is declared BEFORE any fitting

§0 requires an adjudicator to be validated on a held-out labelled set. Fitting a
threshold on all the data and then reporting its accuracy on the same data is not
validation.

**Split by CELL, not by row.** Rows within a cell share content, font, widget and
ladder, so a row-wise split puts near-duplicates on both sides and reports an
optimistic number. Record the split in the campaign manifest, with its own seed,
before the first fit.

### 7.2 What this document does NOT specify, and a campaign must

Stated rather than left as a silence, because every one of them is a free
parameter that can move a result:

- **A statistical criterion for the no-image arm.** §5 says "indistinguishable
  from the chance baseline" and does not say by what test, at what level, with
  what power. Pre-register one.
- **A definition of "non-monotonic".** §4.3 names the finding and not the
  threshold for declaring it.
- **A campaign SIZE.** The cross product of font, size, widget class, content
  class, hue family, ladder rungs, draws and runs reaches tens of thousands of
  model calls. Nothing here estimates it, and an operator authorising spend needs
  that number before anything else.
- **R, and the draw count.** §6.1 requires R fixed in advance; §3.2 notes the
  instrument's default of one draw is not a recommendation.

---

## 8. Provenance, and when a labelled set goes stale

A labelled set is valid only for the (instrument × renderer build × font set ×
model) that produced it. `readback-strings/protocol-version` is the instrument
half and is bumped whenever the alphabet, the classes, the draw order, the seeding
or the scorer changes. The other three are the campaign's, and
`provenance-problems` treats a missing or blank one as a finding:

```
:renderer-commit :controls-wasm-sha256 :font-set :model-id :harness-version
:image-encoding :runs-per-cell :campaign-date
```

**A renderer change that moves a pixel invalidates every label it moved.** This
repository already has the tripwire: the golden manifests hash raw framebuffer
bytes, so a re-mint is exactly the signal that a labelled set needs re-running. A
campaign that cannot say which renderer commit it was measured against cannot be
retired when that commit moves, and an unretirable stale label is worse than no
label at all.

**Do not run `claim-problems` over a provenance VALUE.** Its ban list is for
MESSAGES and over-fires by design — `vision` matches `revision`, and matches most
vision-model identifiers. A `:model-id` is data, not a claim.

---

## 9. What this must never be used for

- **A gate.** §0, and the whole of §0's argument about verdicts that imply more
  than their measurement can see.
- **Moving the 6.0:1 governing floor, or the 4.5:1 one.** §2.1, site 3.
- **Any statement about sunlight, darkness, a panel, an operator, a person, or a
  conformance badge.** §0 and `banned-claim-tokens`.
- **Reopening the renderer-side per-glyph ink mask.** §0 closes it on a
  freedom-to-operate question over third-party patents, and states explicitly
  that the recovery METHOD was never the obstacle. A better calibration is a
  better recovery method; it moves nothing about the reason. Reopening costs a
  patent-attorney opinion, which is an operator spend. (§4.4 explains why the
  offline TTF bitmap comparison is a different instrument, and what to do if you
  disagree.)
- **Carrying a threshold to unmeasured content, an unmeasured font size, or a
  different model.** §4.1, §8.

---

## 10. What is delivered here, and what is not

**Delivered and executed in this tree:**

- this protocol;
- `tools/devcards/dev/readback_strings.clj` — the string generator, the exact
  entropy, the pre-registered reply parser (`parse-reply` / `grade`), the scorer
  with confusion attribution, the sanctioned pass message and its banned
  vocabulary, and the provenance requirement;
- `tools/devcards/test/devcards/readback_strings_test.clj` — armed in
  `make -f renderer.mk devcards-test`, cross-checking the closed-form space size
  against brute-force enumeration and the alignment-derived distance against an
  independent forward DP;
- `tools/devcards/dev/readback_mutate.sh` and its committed output
  `readback_mutation_evidence.txt` — every mutation asserted landed before its
  run, each with a named canary carrying its own failure message and a named
  control that must stay green.

**NOT DELIVERED — and none of it is OWED by this repository unless a campaign is
authorised.** This is a list of what a campaign would have to build, not a backlog
this repo carries; `docs/UI-QUALITY-CONTRACTS.md` §0 refuses permanently-empty
slots on a reader's list, and this section must not become one.

- **The render harness** that authors the degraded ladder and drives the model.
- **A campaign, and any number about any model.** No model was called.
- **The statistical criteria of §7.2**, which a campaign pre-registers.
- **A human anchor arm.** §2 explains why nothing downstream should need one;
  where a campaign genuinely does, it is a human-subject measurement this
  repository cannot run, and its absence must be stated rather than bridged by
  the §2 prior.
- **A same-size font comparison**, and **the offline glyph-bitmap check** (both
  §4.4), neither of which has been run.
