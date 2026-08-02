# CLAUDE.md - Protogen Module

This file provides guidance to Claude Code when working with the Protogen module.

## Branching policy (MANDATORY — trunk-only, always track head)

**This repo is TRUNK-ONLY. All work lands on `master` (head). NEVER create a
feature branch for protogen.** Protogen is the single upstream for 10+
binding/consumer repos that pin it as a git submodule and rebuild from the pin;
the `build-and-release` GitHub Actions workflow distributes generated bindings to
the language-specific repos. A **feature-branch pin fragments that fleet** — each
consumer ends up pinned to a different, non-`master` commit that can be rebased,
force-updated, or deleted out from under it (this is exactly the divergence that
forced a full cross-repo re-sync).

Rules:
- **Commit directly to `master`.** No `feat-*` / `wip-*` branches — branch
  creation is forbidden. A change lands on `master` or it doesn't land.
- **Consumers ALWAYS pin/track `master` head**, never a feature branch. When a
  consumer needs a new proto field it lands on `master` FIRST, then the consumer
  bumps its pin to the new `master` tip; the consumer's `.gitmodules` tracks the
  `master` branch so `git submodule update --remote` follows head.
- **Additive-first, POC-regenerable.** New fields append a free field number
  (additive, backward-compatible). Full regeneration incl. renumbering is allowed
  ONLY when every consumer rebuilds in lockstep (nothing ships outside rebuild
  reach) — the numbering registry is for determinism, not compatibility.
- **A proto change is ONE coordinated event:** edit → `make generate` → commit to
  `master` → push (CI fans out) → every active consumer bumps its pin + regenerates
  + gates, in lockstep. Never leave the fleet pinned across divergent commits.
- **AN IMPORTED NO-LEGACY OR FEATURE-BRANCH POSTURE IS REFUSED HERE, and this is the
  one file that can carry the refusal.** A superproject that vendors protogen may hold
  rules mandating no backward compatibility and licensing a feature branch when
  convenient. Both are incompatible with the two paragraphs above, for the reason
  already stated in them: ten consumer repos rebuild from this pin, so a renamed field
  breaks all of them and a non-`master` pin fragments the fleet. Neither posture
  travels into this repo, whatever its authority upstream.
  Why HERE rather than in a rule: `.claude/` resolves from the PROJECT ROOT and never
  from a submodule mount, so protogen's rules cannot be co-loaded with a
  superproject's — but a subdirectory CLAUDE.md IS read when files under it are read.
  This file is therefore the only protogen surface where the two rule sets can meet,
  which makes it the only place the refusal can be stated.

## Fixing protogen from a consumer (MANDATORY — fix at the source, robustly)

**A bug you hit in protogen while consuming it is fixed HERE, at the source, and
never worked around in the consumer.** A defect one consumer trips over is live
in every other consumer and has merely not surfaced yet, so a local workaround
leaves it live for everyone else, forks behaviour between stacks required to
agree byte-for-byte, and hides the signal so the next consumer rediscovers the
same bug from scratch. The negative vector in `docs/INTERFACE-CONTRACTS.md` §6 —
a former web-consumer `buildPingPayload()` hand-roll, kept only as a failing test case
— is what that costs when it happens. This is the general form of that doc's
§10.1 **"Generate, don't hand-roll"**: that rule forbids implementing the
contract locally, this one forbids repairing it locally.

The pin-bumping consumers — the app consumers and any monorepo that vendors this
submodule — are therefore protogen's proving ground: a bug found downstream (a
wire mismatch, a wrong constraint, a UI-AST node the renderer cannot express) is
an opportunity to harden the contract, not an inconvenience to route around. And
because a fix here reaches all of them at their next pin bump, it lands in front
of maintainers who did not write it and cannot cheaply audit it — so a fix
carries a **higher** bar than a local change, not a lower one.

Rules:
- **Fix the root cause in this repo**, not the symptom at the consumer's call
  site. If the consumer can only paper over it, that is the signal that the
  CONTRACT is wrong — fix the contract.
- **Regenerate and revalidate before it lands.** `make generate` rebuilds the
  bindings AND the descriptor set the docs are rendered from, so it runs first
  and the docs leg of `## Common Operations` (`make docs-docker-generate` →
  descriptions → `make docs-docker-lint`) runs after it, never instead of it —
  linting docs rendered from the previous descriptor set proves nothing. A fix
  that touches a wire surface must then still round-trip the
  `docs/INTERFACE-CONTRACTS.md` §9 golden vectors byte-for-byte — asserted in the
  consumers' own wire-parity tests, per §10.2, since nothing here runs them.
- **Prove it against the consumer that surfaced it.** This repo's own tests did
  not catch the bug — that is precisely what makes the report valuable — so the
  proof is a pin bump in that consumer with its battery green, never this repo's
  tests alone.
- **Get an antagonistic review before pushing.** A hostile, non-self review of
  both the diff AND the commit message is what confirms the fix is real and the
  message honest. Nothing mechanical gates a push here — the review IS the gate.
- **Say what each consumer must do.** The commit message carries a per-consumer
  CONSEQUENCES beat — regenerate, rewire, or bump-only — because that beat is the
  instruction every bump author executes verbatim. The generated binding repos
  need no such instruction: CI overwrites them wholesale on the next proto push.

## Consuming the UI standard (MANDATORY — arm the gates, never fork them)

### WHAT THIS BINDS — a SURFACE, never a repository

**This standard binds any interface rendered through the ui_ast vocabulary by
the reference interpreter — including every widget and composition a consumer
authors on top of it. It binds nothing else in the repository that contains
one.**

Scoping by repo would be wrong in both directions, and the fleet proves it:
`docs/INTERFACE-CONTRACTS.md` describes two co-equal render targets — a web
stack and a native stack — that **both EMBED the same `controls.wasm`**. Each
therefore owns an in-scope ui_ast surface AND out-of-scope chrome around it, in
one repository. A repo-level rule over-binds the shell and never explains why
the overlay is covered.

**The test is mechanical, and its subject is what PAINTS the surface: if the
reference interpreter renders it from a `ui.Screen` — equivalently, if its nodes
are ones `controls_dump_tree` EMITS — the surface is in scope.** That is a
property of the surface, never an instruction to go and run the dump.
`controls_dump_tree` is how you INSPECT an in-scope surface; it is not how you
decide scope.

A consumer's million new widgets are in scope precisely because they compose
classes the interpreter emits — which is also the condition the DOM-reading
harnesses below need (the goldens are independent of it; they hash raw
framebuffer bytes and never read a value). **That consequence only survives the
reading above**, which is the strongest reason to insist on it: a widget already
authored but not yet in a corpus emits no dump nodes today, so a literal "run it
and see" would put it out of scope on precisely the day it most needs judging.

**A dump that yields nothing is UNMEASURED, never OUT OF SCOPE.** This is the
standard's own *"an unjudged element is a FINDING, never a skip"* applied one
level up — to the SURFACE rather than the element — and it needs saying because
the failure runs in the direction that feels like diligence. A consumer whose
surface IS ui_ast but who has not stood up a harness yet runs the test in its
operational reading, gets no nodes, and honestly concludes "out of scope". What
they actually hold is an obligation NOT YET DISCHARGED, owed a gallery and a
runner. `.claude/skills/ui-standard-review/preflight.sh` returns exactly that
answer (exit 3, "NOT DISCHARGED"), rather than an empty batch that reports
clean.

A tree too big for the dump buffer does not narrow scope silently either — know
which WAY it fails: the sentinel OVERWRITES the tail of already-cut JSON,
`devcards.host/dump-tree!` checks that suffix BEFORE parsing and substitutes a
canonical root carrying `truncated`, and the invariant lane emits a HARD
`:dump-truncated` finding. The host still returns one JSON String; there is no
separate truncation flag a copied caller can forget to check.

**A host-proxy surface is NOT an exception to the test**, and it is worth saying
because it looks like one. The compositor paints it after LVGL, so its paint
ORDER cannot be read off the tree — that is what the `:proxy-rects` declaration
supplies. The SURFACE itself is an authored widget with a uid, `dump_obj` emits
`proxy_root` on it, and the layer contract resolves the declaration to that dump
node by uid and THROWS if it matches none. Every coordinate it judges comes from
the dump.

**The one real gap is named rather than hidden:** this interpreter puts every
OBJECT on the active screen, while stock LVGL also searches its system, top and
bottom layers, so a port that started using them would need this sentence
revisited before the dump could still stand for the whole surface.

### WHAT THIS DOES NOT BIND, and what is still owed anyway

A UI built in any other technology — a DOM front-end, a native toolkit — is out
of scope, and not as a courtesy: **the machinery is mechanically inapplicable.**
No `dump_tree` means no `:coords` / `:click_area` / `:clickable`, so no geometry
and no classification key; no `controls.wasm` means no framebuffer to hash; no
gallery means no input for the visual review.

**Read all three as CANNOT-EVER, never as NOT-YET** — because the same three
words also describe a ui_ast surface whose harness has not been built, and that
one is fully in scope. The DOM front-end is out because nothing in its stack can
ever produce those inputs: the interpreter is not in the picture at all. A
ui_ast surface with no runner is missing the identical three artefacts and OWES
every one of them. This sentence is about the technology; it is not a licence to
discharge the obligation by not having built the harness.

Stronger still, the overlap rule's verdict would not MEAN the same thing. Its
correctness argument is not "two boxes overlap" — it is that
`lv_indev_search_obj` walks children in REVERSE and returns the FIRST hit, so
the later sibling silently wins and the one underneath is dead. Two premises
under that do not transfer: the ORDERING is reverse child index, where a DOM
resolves by stacking context and z-index; and the reachable box is
`lv_obj_get_click_area` grown by `ext_click_pad` and intersected with each
ancestor's descent gate, which has no DOM analogue — nor does `pointer-events:
none`, which removes a box from hit-testing with no LVGL counterpart.

**And one of those goes SILENTLY wrong, which is worse than a wrong verdict.**
The descent gate is exactly `:coords` only while nothing sets
`LV_OBJ_FLAG_OVERFLOW_VISIBLE` — a child outside its parent's box cannot then be
reached, so the rule records `:unreachable` as a POSITIVE determination and drops
the node with no finding. **In CSS, `overflow: visible` is the initial value**:
the default is inverted, that child IS hit-testable, and a ported rule would drop
genuinely reachable elements while emitting output byte-identical to a clean run.
A gate going green on elements it never judged is the one failure class this
standard refuses everywhere else.

**That does not remain a hazard on the ui_ast path.** The flag is settable
straight from the wire — `obj_flags` is direct-cast onto `lv_obj_add_flag` — so
`dump_obj` emits the already-resolved `descend_gate` box when
OVERFLOW_VISIBLE makes it differ from coords. The overlap producer reads that
box and falls back to coords only when the two are exactly equal. A consumer
must therefore rebuild the wasm AND consume `descend_gate`; copying only the
old coords-based producer recreates the silent under-report. CSS still has no
equivalent dump declaration, and its default remains inverted.

(What does NOT rescue it is event bubbling: the rule already excludes
ancestor/descendant pairs — `invariants/related?` — because containment is how
composition works, and bubbling propagates along exactly that chain. LVGL
bubbles too, opt-in via `LV_OBJ_FLAG_EVENT_BUBBLE`. For the independent
siblings the rule DOES judge, a DOM hit-test yields one target and the sibling
underneath gets nothing — the same hazard, not a phantom one.)

**But out of scope for THESE GATES is not out of scope for the obligation.**
Readability numbers are properties of a PANEL and an OPERATOR — angular
character size, touch geometry, chromaticity under night vision — not of a
widget toolkit, and the bench obligation for hardware-scoped claims (sunlight,
darkness, a panel revision) never mentioned a toolkit either. A surface with no
ui_ast owes those the same; what it does not owe is protogen's lanes, because
they cannot see it. Do not read "not ui_ast" as "nothing owed" — that inference
is the one this section exists to prevent.

**Those numbers are OUT OF SCOPE for this repository — not pending here.** They
are governed upstream, this repo has never held them, and it will not: nothing
here can measure a panel or an operator, so a threshold copied in would be an
unsourced constant no gate could check. The obligation is real and is discharged
at a BENCH against a hardware revision. What this repo owes is the negative —
never to print a pass implying a condition it did not impose
(`docs/UI-QUALITY-CONTRACTS.md` §0).

Read that as a boundary, not as a dismissal, and do not restore the older
"pending" framing: a permanently-empty slot on every reader's list is what
invites a future contributor to fill it with a plausible number, which is the
exact defect this section exists to prevent.

### THE TWO DOCUMENTS, and they are separable

`docs/INTERFACE-CONTRACTS.md` is what a consumer owes for consuming the WIRE.
`docs/UI-QUALITY-CONTRACTS.md` is what a surface owes for RENDERING ui_ast. **A
consumer can owe the first entirely and the second's GATES not at all** — a
client that speaks the protocol and draws its own interface is exactly that
case, and what it still owes is the section above, which is an obligation rather
than a gate.

### WHY IT MAY NOT BE FORKED

Within that scope: a consumer owns WHAT its screens contain; it does not own the
standard, and it does not get to decide locally what "reachable" means. A
consumer that hand-rolls its own geometry check forks the standard silently —
two stacks required to agree then disagree about whether the same screen is
defective, and the defect one of them catches stays live in every other. This is
the §"Fixing protogen from a consumer" rule applied to the gates themselves: **a
quality rule that lives only in your repo is a bug in this one.**

**Geometry is exact integer arithmetic on inclusive rects** (`devcards.geometry`)
with no noise floor, so it is strictly pass/fail. Importing an "uncertain"
verdict here manufactures doubt the arithmetic does not have. A measurement
whose separating gap is narrower than its own noise is three-way instead, and
the uncertain band is the only place an adjudicator belongs — one validated as a
classifier on a held-out labelled set before it is wired in. **Never import one
verdict SHAPE into the other's measurement**, and never let a gate imply in a
pass message something it cannot see.

Rules:
- **Integrate with the harnesses; do not re-implement them.** Add rules through
  the finding-producer registry (`devcards.findings`) and drive your screens
  through `devcards.corpus/render-corpus`. Neither needs patching to run against
  a private corpus: the registry validates producers against a closed key set,
  and the corpus driver knows nothing about hosts or themes — **it takes your
  render fn, so the wasm lives on YOUR side of the call.** Widget classification
  merges your table OVER the shipped one (`lvgl-classes/merge-consumer`), so you
  can add rows and correct ours.
  **BUT KNOW WHICH HALF THIS REPO EXERCISES — unlike the overlap lane below,
  this IS advice protogen partly exempts itself from.** `render-corpus` has NO
  production caller here: `devcards.core` drives the shipped corpus with its
  own `into`/`map` and reaches the per-card seam directly, so the function you
  are told to build on is held up by its unit tests alone. It is a supported
  API and not a stub — but it is a SECOND path, and this repo's gates cannot
  notice the two diverging. Two consequences that bite in opposite directions:
  a consumer threading NEW context through `render-corpus` finds no seam on the
  shipped side to match, and a change to `core`'s driver can leave
  `render-corpus` behind without reddening anything. Report drift here rather
  than routing around it locally.
- **ARM THE OVERLAP LANE — protogen runs it on its own corpus, so this is not
  advice this repo exempts itself from.** `devcards.lanes` passes
  `overlap/producer` at `:overlap/gap-px 0` (strict overlap: a shared pixel
  fires, touching does not — at 1 the tabview cards' own layout abutment floods
  it, and NOT the carousel's snapped pages, which never enter the pairing at any
  threshold). What it
  catches is invisible to every other oracle you run: two pointer-taking
  elements in one place look identical in the framebuffer whether the one
  underneath was reachable or dead. Expect your first run to be red on DESIGNED
  stacks; resolve those by construction or by the interpreter's own declaration,
  not by per-card exemptions, which is the ratchet these rules refuse.
  The LAYER lane is a different story: its declaration is uid-keyed and
  renderer-built affordances are permanently uid-free, so it can judge your
  AUTHORED nodes and never those. Do not read a clean layers run as coverage of
  a proxy's internals.
- **Classification and thresholds are DATA you supply, not code you fork.**
  Widget classification is `devcards.classify`; each producer declares its own
  thresholds and the registry namespaces them by producer id. An unknown
  threshold key throws rather than falling back — a typo must never quietly
  relax the gate it names.
- **Occlusion thresholds are PER-ROLE.** The split has to be what the element
  IS, not a number: a covering ratio that condemns a damaged label also
  condemns a benign container, because the observed ratios interleave across
  the good/bad boundary rather than separating. The ratios behind that claim
  were measured in a CONSUMER, not here — `docs/UI-QUALITY-CONTRACTS.md` §4
  records them and says so; treat them as the consumer's evidence for the
  design, not as a protogen measurement. Note this constrains the PER-ROLE
  occlusion lane, and protogen ships no occlusion lane in ANY sense — the armed
  `:zero-visible-area` check is not the global case of it but a different
  quantity, an ancestor-CLIP walk that cannot see a covering sibling
  (`docs/UI-QUALITY-CONTRACTS.md` §0). The overlap rule's `gap-px` is likewise a
  single geometric threshold and correctly has no role axis.
- **An unjudged element is a FINDING, never a skip.** A rule that passes over
  what it could not classify reports "clean" and "I could not look" as the same
  empty vector. Every lane owes the third answer out loud. Two escapes are
  sanctioned and both leave a record: a proof-carrying exemption, and a
  `:default` in your classification table — which `devcards.classify` documents
  as *your explicit, visible decision to stop enforcing totality*. Declaring
  either is a choice on the record; omitting the third answer is not.
- **Every gate carries a canary that fails for ITS OWN reason.** A red run
  proves nothing if the finding came from a different clause than the one under
  test — a gate that goes red for parent/child nesting instead of the hazard is
  a false gate that happens to be the right colour. Prove it by mutation: break
  the clause, watch its canary and only its canary fail.
- **A producer reads the interpreter's dump key vocabulary (`dump_obj` in the
  renderer C source is its one home), and absence is NOT NEUTRAL — check which
  WAY each key fails before you trust a green.** Several keys are emitted only
  when they carry information, so an absent one can mean "nothing to report" or
  "the default", and those are not interchangeable — `clickable` absent means
  CLICKABLE, `disabled` absent means enabled, and a rule that reads absence as
  "no information" supplies neither. `text` is sharper: only an exact `lv_label`
  emits it, so the `lv_roller_label` subclass draws glyphs while both `text` and
  `text_clipped` stay absent.
  **`text_wrapped` is the trap in the OTHER direction — a rule that reads the two
  older text flags and stops has a blind spot no absence convention warns it
  about.** A WRAP-long-mode label reflowed onto more lines than its own text asks
  for GROWS rather than clipping, so `text_clipped` (CLIP mode only) and
  `text_truncated` (dot_begin) are both correctly absent while the reader gets a
  mid-word break — and it fires on EVERY theme family, because growing needs no
  padding to go wrong. `scroll_dirs` is the companion on the scroll side, and its
  absence is the ordinary conditional kind: emitted only BESIDE
  `scrollable_overflow`, so absent means that flag did not fire, never "no axis".
  The RELATED trap is a key you never consult at all,
  and it sits inside the very lane this section tells you to arm:
  `click_area` is emitted only when it DIFFERS from coords, so a rule that
  measures coords and never reads it UNDER-reports reach — while
  `overlap/hit-box`'s read-then-fall-back-to-coords is exact, because absent
  THERE does mean the two are equal. The ancestor side has the same precise
  convention: `descend_gate` is emitted only when OVERFLOW_VISIBLE grows it,
  and `overlap/descend-gate` falls back to coords because absence means equality,
  not because the flag was assumed clear. These are different boxes with
  different jobs; do not merge them. The `overlap` and `invariants` docstrings
  carry which way each key fails, and
  `:caps` is how a capability-gated key declares itself; the registry's
  `:requires` check guards the top-level context keys a caller supplied, NOT the
  per-node keys inside the tree, so it cannot catch this for you.
- **Declare intent; never derive it from what renders.** Layer z, roles, and
  proxy rects are DECLARATIONS. A checker that reads stacking off the current
  paint order asserts that the system does what it does, and blesses the bug it
  was built to catch.
- **Your corpus stays yours; the runner stays ours.** Device-specific screens
  never land here (the corpus secret-scan is gate-enforced). Private consumers
  run THIS runner against their own private corpora via their protogen pin.
- **The VLM UI review is MANDATORY before any push that changes what a ui_ast
  SURFACE renders** — a widget, a composition, the theme, the interpreter —
  here AND wherever such a surface is authored, each over its own renders. It
  is owed for the surface, not for the repository: a repo that also ships a DOM
  front-end owes this for its ui_ast overlay and nothing for the rest, because
  the review's inputs are the gallery renders and the `dump_tree` behind them.
  RUN IT by launching the `ui-standard-review` AGENT (`.claude/agents/`), which
  pins the model tier and loads the skill of the same name as its first act.
  The agent is the launcher and the skill is the standard: one batched agent per
  push, never one per check. **At a consumer that agent DOES NOT EXIST until you
  install it**: agents and skills are discovered from the PROJECT ROOT's
  `.claude/` and never from a submodule mount, so `subagent_type:
  ui-standard-review` resolves to nothing at a mount point and the mandate's one
  named entry point has no first step. Symlink the pin's agent file and skill
  directory into your own `.claude/` — the two `ln -s` lines are in the skill's
  §"AT A CONSUMER", and a relative symlink is verified to register — then
  re-check at every pin bump. **Resolve the batch with
  `.claude/skills/ui-standard-review/preflight.sh` from your own repo root and
  review exactly what it prints, never by globbing**, which is how this review
  ends up judging nothing at all, or judging protogen's own shipped renders
  under the mount. The operational how is `.claude/rules/devcards.md`
  — **read it explicitly from your pin; it is path-scoped to `tools/devcards/**`
  anchored at THIS repo's root, so it does not auto-load at a consumer's mount
  point.**
- **Its findings are DISPOSITIONED before the push — fixed, or exempted** with
  the same proof-carrying entry every other exemption owes — `:rationale`,
  `:retires-when`, `:owner` and `:expires`, each a non-blank string
  (`devcards.invariants/validate-exemptions!`, where an exemption matching
  no finding is itself a finding). **An exemption here is a WAIVER, not a
  disabled rule:** `:owner` names who to ask, and `:expires` is an ISO-8601
  date at most 90 days out — expiry and horizon are separate hard failures so
  neither masks the other, and `:retires-when` still carries the EVENT that
  retires the entry, which no date can express. They emit the producer shape
  (`{:card :invariant :node :detail}`), so they ride the existing verdict and
  exemption machinery rather than a parallel path — and a consumer extends the
  review the way it extends every other lane: through the registry.
- **A VLM finding is NOT a deterministic gate verdict.** Every other lane in
  this standard is reproducible and a VLM is not, and
  `docs/UI-QUALITY-CONTRACTS.md` §0 forbids a verdict implying more than its
  measurement can see. Mandatory to RUN and mandatory to DISPOSITION is the
  whole obligation; a finding earns pass/fail only by being reimplemented as a
  deterministic producer.
- **AUDIT YOUR SCREENS FOR THE `DISABLED` DEAD ZONE.** `LV_STATE_DISABLED` does
  NOT remove a widget from the pointer path — a disabled control painted over
  an enabled one absorbs the press and drops it. Neither PIXEL oracle nor EVENT
  log can see it: the framebuffer is identical either way, and no event fires.
  **IT TAKES TWO LANES, and arming only the first is the trap.**
  `devcards.overlap` declines to exclude a disabled node and names that
  participant in its `:detail`, but it is ORDER-FREE: it tells you the two
  share a pixel and one is disabled, never that the disabled one WINS. That is
  the necessary
  condition, not the verdict. `devcards.deadzone` supplies the ordered verdict
  — it walks children in the same REVERSE order `lv_indev_search_obj` does and
  names the winner — and protogen arms both on both lanes, so this is not
  advice this repo exempts itself from. **Neither substitutes for the other in
  the direction you might assume:** overlap excludes any pair the interpreter
  declared a proxy stack, and deadzone does not exclude that class at all, so
  a disabled-over-enabled pair INSIDE one `host_proxy` is reported by deadzone
  and by no oracle whatsoever under overlap alone. Any design that treats
  disabled controls as safe to stack has a live dead-zone class, and
  `docs/UI-QUALITY-CONTRACTS.md` §2.2 has the LVGL sources and what to do
  about it.

## The reference interpreter + the devcards proof

protogen owns the ui_ast protocol end-to-end: the `.proto` vocabulary
(generated in-place from the vendored LVGL tree + the numbering registry),
every language binding, AND the ONE reference interpreter — the renderer C
source under `renderer/`, its theme, its `wasm.mk` build producing
`controls.wasm`, and the proof battery that gates it (the pixel oracles —
the wasmtime harness's visual-regression suite, dual-oracle morph parity,
the coverage matrix, and demo parity — plus the devcard corpus; the
cross-engine MIRROR, `composition_cross_engine_fb` in
`renderer/wasm_harness/tests/composition_interaction.rs`, re-proves wasmtime ≡
GraalWasm on every build by asserting each card's raw framebuffer
byte-identical to the GraalWasm dump of the same `.pb`, over every card
discovered from the card directory so a new one joins by itself. That
directory holds the composition lane's cards PLUS a small named set of
SVG-sourced atomic ones (`cross-engine-atomic-ids` in
`tools/devcards/src/devcards/core.clj`), and that split is load-bearing
rather than incidental: every composition card is an integer blit of rects,
borders and glyph bitmaps, so the vector cards are what put floating-point
path filling under the mirror at all — two engines agreeing bit-for-bit on
blits implies nothing about their agreeing on a curve. It is NOT the whole
atomic corpus, which would cost two full-canvas raw framebuffers per card, so
read a green here as covering those two classes and never as a corpus-wide
equivalence proof).
NAME THE FILE THAT DOES THE WORK, never the phrase a file chose for itself:
`tools/devcards/dev/spike_r22.clj` is a dev spike, unwired and correctly
labelled one, and its self-description reads exactly like a gate's.
The battery entry is `make -f renderer.mk check-renderer` from the
repo root, inside the toolchain container built from `Dockerfile.base`. No
workflow invokes that entry: `.github/workflows/renderer.yml` and
`devcards.yml` DECOMPOSE it into individual `renderer.mk` targets, so what CI
covers is a SUBSET of the local battery and the two are kept in step by hand.
The vocabulary/fixture generators live in `tools/renderer-gen/`.

The interpreter is the same artifact class as the bindings: a generated
projection of this repo's own sources. Render-time assets under `renderer/assets/` are generic and secret-free: the OFL font and
the `images/demo/` twins of the vendored LVGL demo (source pinned via
`renderer/lvgl/demos/.ported-from.edn`) carry provenance; the icons and `test_*`
fixtures are self-authored placeholders. Fleet cost, stated
plainly: each renderer release adds a regenerated JPEG gallery to the history
every consumer clones. The JPEG-only rule is the GALLERY's — generated contact
images under `tools/devcards/docs/` are JPEG and raw dumps stay gitignored — not
the repo's: PNG is also the committed format for render fixtures and for the
wasmtime harness's reference snapshots. controls.wasm is a rebuildable build artifact, NOT committed
(`renderer/.gitignore`) — CI and consumers build it from source with the
WASI-SDK.

The devcard gate (`tools/devcards/`) proves the schema and the renderer
agree — schema-validate + framebuffer-hash + DOM invariants; a red devcard
gate after a SCHEMA change is the waist catching producer/interpreter drift
— fix the contract, not the gate. Fixtures are secret-free, gate-enforced by the corpus
secret-scan (`corpus-secret-findings` in `tools/devcards/src/devcards/gates.clj`,
which fails the run on a hit): generic widgets and compositions only; proprietary
device meta nodes stay in consumer repos, which reuse this runner via their
protogen pin. Device-specific screen AUTHORING stays in the private
consumers; this repo defines how interfaces work, not what any product's
screens contain.
## What protogen is

A Docker-based protocol-buffer code generator producing per-language bindings —
plain, and validated (buf.validate) where the language supports it — plus the
ui_ast reference interpreter above.

### Where things live

- `proto/` — input `.proto` files, subdirectories included. Generation walks it
  recursively and excludes `test/`.
- `output/` — generated bindings, one directory per language, plus
  `output/manifests/` (renderer/token manifests). **`output/manifests/` is
  tracked and is NOT a binding output.** The per-language list has exactly one
  home: the single `mkdir -p "$OUTPUT_BASE_DIR"/{…}` line in
  `generate-protos.sh`. Do not re-enumerate it anywhere — a copied listing is
  what kept getting this wrong.
- `renderer/` — the ui_ast reference interpreter: C source, theme, `wasm.mk`,
  the vendored LVGL tree, the wasmtime harness and the dual-oracle drivers.
  `renderer.mk` at the repo root is the battery entry.
- `tools/devcards/` — the devcard corpus runner (GraalWasm): fixtures, golden
  manifests, invariants, the JPEG gallery and per-widget docs.
- `tools/renderer-gen/` — the renderer vocabulary/fixture codegen seam.
- `docs/` — the Obsidian vault of generated per-message and per-enum markdown.
  Implementation lives in `docs/.protodoc/`; user-written descriptions survive
  regeneration by roundtrip extraction.
- `Dockerfile.base` — **the version pin for every toolchain.** Change a version
  here and nowhere else.

## Regenerating — order matters, and splitting it reddens CI

A generated artifact belongs in the SAME commit as the source change that moved
it. Split them and CI's runner-side freshness diff reddens a commit that is
otherwise correct, while a consumer pinning the intermediate sha vendors a
binding that does not match the proto it came from.

After changing any `.proto`:

1. `make generate` — rebuilds bindings AND the descriptor set the docs render
   from, so it runs FIRST.
2. `make docs-docker-generate` — never instead of step 1; linting docs rendered
   from the previous descriptor set proves nothing.
3. Write descriptions for new messages and fields in the generated markdown, then
   re-run step 2 — extraction is what folds that prose into `proto-db.edn`, and
   the lint below reads the db rather than the pages.
4. `make docs-docker-lint`.
5. `make docs-docker-manifests` — regenerates `output/manifests/` from the FINAL
   `proto-db.edn`, so it runs after step 3's prose has been extracted. **Do not
   skip it because the steps above went green.** `make generate` does not write
   these (they are tracked and are NOT a binding output), no local gate reads
   them, and the drift surfaces only as `renderer.yml`'s manifest-freshness step
   — i.e. after the push has already fanned out.
6. Commit everything together.

If the change touches a cross-language WIRE surface — stream framing, the
codec/transport headers, the `cmd.*`/state/enrichment encoding, or the
`controls.tar` / `controls.wasm` ABI — also update
`docs/INTERFACE-CONTRACTS.md` and bump the pin in both consumer repos in
lockstep. Their wire-parity tests assert that document's golden vectors and fail
loudly on drift.

`make help` lists the targets; `make versions` reports the pinned tool versions.

## Before implementing anything involving a proto message

Read its page under `docs/proto/` first — `cmd.<Package>.<Message>.md` for
commands, `ser.<Package>.<Message>.md` for state and data, enums alongside them.
Each carries purpose, field constraints, semantic notes, and interaction
metadata (UI pattern, semantic type, feedback shape, related commands and
state). The authoritative enums for that metadata are `UIPattern`,
`SemanticType` and `FeedbackType` in
`docs/.protodoc/tools/src/protodoc/schema.clj` — read them there, never from a
prose copy, which can only lag.

## Distribution

CI generates every language in one job, then pushes each to its own repository,
then commits generated output back here.

**WHICH LANGUAGE LANDS IN WHICH REPOSITORY has exactly one home:
`.github/workflows/build-and-release.yml`**, whose ten `Push to <repo>` steps name it
directly. A table here would be a second copy of a list that workflow already
advertises — the drift-prone enumeration `.claude/rules/claude-md-policy.md` bans — and
the repo names are self-describing (`jettison_proto_go` is the Go binding).

ONE mapping is NOT self-describing, so it is stated rather than pointed at:
`jettison_protovalidate_es` is the VALIDATED TypeScript output, not a separate language.

Each push needs a deploy key in repository secrets; `build-and-release.yml` is
the one home of which secret feeds which step.

**There is no BSR secret, and no leg reads one.** The Go leg used to name two
REMOTE plugins in its `buf.gen.yaml`, which made `buf generate` a metered Buf
Schema Registry codegen request — 10/hour unauthenticated, 960 with a
`BUF_TOKEN` the workflow's own comment recorded as never created. It now runs
the same two plugins as LOCAL binaries pinned in `Dockerfile.base`
(`PROTOC_GEN_GO_VERSION`/`PROTOC_GEN_GO_GRPC_VERSION`), so the leg makes no
registry request, needs no credential, and behaves identically in a repo build
and a fork build. The switch was proved byte-neutral: a local run reproduced
all 48 committed files exactly.

**Because the pin decides the bytes, bumping it is a REGENERATION.**
`protoc-gen-go` stamps its own version into every file header, so a plugin bump
rewrites all 48 `.pb.go` files with no proto change behind it. Land the bump and
the regenerated output together.

**The Go leg is now fully offline; the whole target is not.**
`TYPESCRIPT_SCRIPT` runs `npm install ts-proto` and `RUST_SCRIPT` runs
`cargo build` against caret-ranged `prost`/`prost-build`, both of which fetch at
generation time and neither of which is version-pinned. So `make generate` still
needs a network — what it no longer needs is a CREDENTIAL, and no leg can now
fail on somebody else's rate limit.

**No retry, no backoff, and no `Retry-After` handling exists anywhere**, and
that is a decision rather than an oversight: a blind retry around a generation
step would silently re-run the class of bug that has ACTUALLY reddened this
workflow (a bad `bash -c` payload — see `lint.mk`'s `lint-sh` apostrophe check)
while looking like throttle tolerance. A red is loud and fails closed: the go leg
checks for `*.pb.go`, `run_generation()` propagates into `FAILED_LANGS`, and
every "Push to …" step is then skipped — nothing partial has ever reached a
consumer.

## Generation constraints that bite

- **All proto files compile together.** Cross-file references do not resolve
  otherwise.
- **nanopb (C) cannot take extensions**, so `scripts/proto_cleanup.awk` strips
  every buf.validate annotation before the C leg runs. The same strip feeds any
  other target that cannot parse them.
- **Validated outputs keep the annotations** as field options, and get
  `import "buf/validate/validate.proto"` added automatically. They are
  DECLARATIONS only — runtime enforcement needs the language's protovalidate
  library, which is neither in the image nor in the generated output.
- **Kotlin uses local `protoc --kotlin_out`, not `buf`**, so its proto package
  is respected without a prefix.
- **Go uses `buf generate` with LOCAL plugins** — `protoc-gen-go` and
  `protoc-gen-go-grpc` off the image PATH, pinned in `Dockerfile.base`. It is the
  only leg whose plugin versions are stamped into the generated files, so those
  two pins are wire-visible in a way a compiler pin is not.
  `make go-leg-repro` ([`tools/go_leg_repro.sh`](./tools/go_leg_repro.sh))
  re-runs just that leg into a temp directory, offline, and fails if the result
  is not byte-identical to `output/go`; `make go-leg-repro-canary` proves it can
  fail. Both are HOST-ONLY — they drive docker, which the toolchain image does
  not carry.
  **It is in no CI workflow on purpose, and the reason is not "it needs
  docker".** After `make generate` the comparison is vacuous (generate just wrote
  those bytes with that image); before it, it is wrong (regenerating is the
  workflow's whole job, so a proto change would red it). What it catches is a
  developer's WARM image built from older pins — a condition CI, which builds
  cold, never has.
- **JSON descriptors prefer `buf build`** because it preserves buf.validate
  annotations with their CEL expressions; the protoc+Python fallback may not.
  It announces WHICH tool it took ("buf not found, using protoc…") but says
  nothing about the annotations, so a reader of the log learns the path taken
  and not the caveat — check the descriptor if that distinction matters.

`PROTO_SOURCE_DIR`, `OUTPUT_BASE_DIR` and `REBUILD_IMAGE` override the input
directory, output directory and forced image rebuild respectively.

## References

- [`README.md`](./README.md) — user-facing documentation
- [`docs/.protodoc/tools/README.md`](./docs/.protodoc/tools/README.md) — the
  proto-docs tooling
- [`scripts/proto_cleanup.awk`](./scripts/proto_cleanup.awk) — annotation removal
