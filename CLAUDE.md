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

**The test is mechanical: if the nodes come out of `controls_dump_tree`, the
surface is in scope.** A consumer's million new widgets are in scope precisely
because they compose classes the interpreter emits — which is also the condition
the DOM-reading harnesses below need (the goldens are independent of it; they
hash raw framebuffer bytes and never read a value). A tree too big for the dump
buffer does not narrow scope silently either — know which WAY it fails: the
sentinel OVERWRITES the tail of already-cut JSON, `devcards.host/dump-tree!`
checks that suffix BEFORE parsing and substitutes a canonical root carrying
`truncated`, and the invariant lane emits a HARD `:dump-truncated` finding.
The host still returns one JSON String; there is no separate truncation flag a
copied caller can forget to check.

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

**Be warned that this repo does not SPECIFY those numbers.** They are governed
upstream and there is no in-tree document to point you at yet, so this paragraph
establishes only that having no ui_ast fails to discharge them — it does not tell
you their values. That caveat belongs here rather than in a commit message: an
obligation nobody can scope is the exact defect this section was written to fix,
and introducing a second one while fixing the first would be no improvement.

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
  "no information" supplies neither. The RELATED trap is a key you never consult
  at all, and it sits inside the very lane this section tells you to arm:
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
  push, never one per check. The operational how is `.claude/rules/devcards.md`
  — **read it explicitly from your pin; it is path-scoped to `tools/devcards/**`
  anchored at THIS repo's root, so it does not auto-load at a consumer's mount
  point.**
- **Its findings are DISPOSITIONED before the push — fixed, or exempted** with
  the same proof-carrying `:rationale` + `:retires-when` every other exemption
  owes (`devcards.invariants/validate-exemptions!`, where an exemption matching
  no finding is itself a finding). They emit the producer shape
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
cross-engine determinism probe re-proves wasmtime ≡ GraalWasm on a new
build). The battery entry is `make -f renderer.mk check-renderer` from the
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

## Module Overview

Protogen is a Docker-based protocol buffer code generator that supports multiple programming languages with consistent tooling and versions. It provides both standard bindings and validated bindings (for Go, Kotlin, and Java) using buf.validate annotations.

## Module Structure

### Core Files
- `Makefile` - Build automation with targets for image building and proto generation
- `generate-protos.sh` - Main generation script that orchestrates Docker container execution
- `Dockerfile` - Main Docker image that uses the base image
- `Dockerfile.base` - Base image with all necessary tools and dependencies
- `scripts/proto_cleanup.awk` - AWK script to remove buf.validate annotations for incompatible languages
- `.github/workflows/build-and-release.yml` - GitHub Actions workflow for automated distribution
- `.gitattributes` - Line-ending policy (`* text=auto eol=lf`; `renderer/lvgl/**`
  is `-text` — byte-exact vendored upstream — plus explicit binary markers)

### Directories
- `proto/` - Input directory containing .proto files to process (contains jon_shared_*.proto files)
  - Supports subdirectories (e.g., `proto/opaque/` for opaque payload types)
  - Generation scripts recursively find all `.proto` files, excluding `test/` directory
- `output/` - All generated bindings organized by language (created at runtime)
  - Preserves subdirectory structure (e.g., `output/typescript/opaque/`)
- `scripts/` - Contains helper scripts like proto_cleanup.awk and add-validate-import.sh
- `renderer/` - The ui_ast reference interpreter: C source + theme, `wasm.mk`
  (builds `controls.wasm` / `reference.wasm`), the vendored LVGL tree, the
  wasmtime proof harness, and the dual-oracle drivers (`renderer.mk` at the
  repo root is the battery entry)
- `tools/devcards/` - The devcard corpus runner (GraalWasm): fixtures,
  golden manifests, invariants, the JPEG gallery + per-widget docs
- `tools/renderer-gen/` - The renderer vocabulary/fixture codegen seam
  (enum extraction, ui_ast assembly, token/caps manifest emitters)

### Generated Output Structure
```
output/
├── <one dir per language>   # the binding outputs
└── manifests/               # renderer/token manifests — NOT a binding output
```

The per-language directories are exactly the ones `generate-protos.sh` creates —
its single `mkdir -p "$OUTPUT_BASE_DIR"/{…}` line is the one home of that list,
and the distribution table below names each repo they are pushed to. Do not
re-enumerate them here; `output/manifests/` is tracked and is NOT one of them,
which is precisely what a copied listing kept getting wrong.

## Key Patterns

### Docker Container Usage
- Container builds automatically on first run if image doesn't exist
- Base image built locally on first use or restored from GitHub Actions cache
- All generation runs inside Docker for consistency
- Uses volume mounts to access input/output directories
- Runs bash scripts passed via `-c` flag

### Parallel Processing
- C generation uses `xargs -P 8` for parallel protoc invocations
- Each language generator runs sequentially to avoid conflicts
- Error handling aggregates failures and reports at end

### Annotation Handling
- AWK script (`proto_cleanup.awk`) removes buf.validate annotations
- Required for nanopb (C) compatibility
- Applied before generation for non-validation outputs
- Preserves all other proto syntax

### Import Management
- Validation-enabled outputs automatically add `import "buf/validate/validate.proto"`
- All proto files compiled together to resolve cross-file dependencies
- validate.proto copied from protovalidate repository

## Output Distribution

Generated bindings are automatically distributed to dedicated repositories:

| Language | Repository |
|----------|------------|
| C (nanopb) | [jettison_proto_c](https://github.com/lpportorino/jettison_proto_c) |
| C++ | [jettison_proto_cpp](https://github.com/lpportorino/jettison_proto_cpp) |
| Go | [jettison_proto_go](https://github.com/lpportorino/jettison_proto_go) |
| Kotlin | [jettison_proto_kotlin](https://github.com/lpportorino/jettison_proto_kotlin) |
| Python | [jettison_proto_python](https://github.com/lpportorino/jettison_proto_python) |
| TypeScript | [jettison_proto_typescript](https://github.com/lpportorino/jettison_proto_typescript) |
| TypeScript (validated) | [jettison_protovalidate_es](https://github.com/lpportorino/jettison_protovalidate_es) |
| Rust | [jettison_proto_rust](https://github.com/lpportorino/jettison_proto_rust) |
| Java | [jettison_proto_java](https://github.com/lpportorino/jettison_proto_java) |
| JSON Descriptors | [jettison_proto_json-descriptors](https://github.com/lpportorino/jettison_proto_json-descriptors) |

### GitHub Secrets Required

For automated distribution, these deploy keys must be configured as repository secrets:

- `C_PUSH` - Deploy key for jettison_proto_c
- `CPP_PUSH` - Deploy key for jettison_proto_cpp
- `GO_PUSH` - Deploy key for jettison_proto_go
- `KOTLIN_PUSH` - Deploy key for jettison_proto_kotlin
- `PYTHON_PUSH` - Deploy key for jettison_proto_python
- `TYPESCRIPT_PUSH` - Deploy key for jettison_proto_typescript
- `PUSH_TO_PROTOVALIDATE_ES` - Deploy key for jettison_protovalidate_es
- `RUST_PUSH` - Deploy key for jettison_proto_rust
- `JAVA_PUSH` - Deploy key for jettison_proto_java
- `JSON_DESCRIPTORS_PUSH` - Deploy key for jettison_proto_json-descriptors
- `SELF_PUSH` - Deploy key for pushing back to jettison_protogen repository

## Common Operations

### After Adding New Proto Messages

When new messages or fields are added to proto files, you MUST regenerate the documentation:

1. **Regenerate bindings** (creates updated JSON descriptors):
   ```bash
   make generate
   ```

2. **Regenerate documentation**:
   ```bash
   make docs-docker-generate
   ```

3. **Add descriptions** to new messages/fields in the generated markdown files in `docs/`

4. **Run lint** to verify no errors introduced:
   ```bash
   make docs-docker-lint
   ```

5. **Commit all changes** including the updated docs

6. **If the change touches a cross-language WIRE surface** (stream framing, the
   codec/transport headers, the `cmd.*`/state/enrichment encoding, or the
   `controls.tar` / `controls.wasm` ABI), also update
   **`docs/INTERFACE-CONTRACTS.md`** — the canonical cross-language wire contract
   the ARM web + native clients consume — and bump the `jettison_protogen` pin in
   both consumer repos in lockstep. Their wire-parity tests assert this doc's §9
   golden vectors and fail loudly on drift (see that doc's § "Evolving this
   contract").

### Understanding Message Context

**Before implementing features involving proto messages, read the documentation in `docs/`.**

The documentation provides:
- Message purpose and description
- Field constraints (validation rules like `gte`, `lte`, `required`)
- Field notes explaining semantic meaning
- Interaction metadata (UI patterns, semantic types, related commands)
- Related state messages and commands

**Quick ways to find message documentation:**
- Use `/proto-search <query>` to find messages by name or field
- Read `docs/proto/cmd.<Package>.<Message>.md` for command messages
- Read `docs/proto/ser.<Package>.<Message>.md` for state/data messages
- Enum pages live under `docs/proto/` too (e.g. `docs/proto/ser.<EnumName>.md`)

### CI/CD Architecture

The repository uses a sequential workflow in GitHub Actions:

1. **Build Base Stage**: Builds and caches the Docker base image
2. **Sequential Generation**: All languages generated in a single job
3. **Push to Language Repos**: Sequentially push to each dedicated repository
4. **Update Main Repo**: Commit generated outputs back to jettison_protogen

This architecture provides:
- Simple execution flow for easier debugging
- Independent language repositories for consumers
- Automatic distribution without manual intervention
- Efficient Docker layer caching via GitHub Actions cache

### Adding a New Language
1. Add toolchain installation to Dockerfile
2. Create generation script in `generate-protos.sh`
3. Add output directory creation
4. Update documentation

### Debugging Generation Issues
```bash
# Check Docker logs for specific language
docker run --rm -it jettison-proto-generator:latest /bin/bash

# Test individual commands inside container
protoc --version
which protoc-gen-go
```

### Updating Dependencies
```bash
# Edit the version variables in Dockerfile.base — that file is the pin

# Force rebuild using Make
make rebuild-base

# Or using script directly
REBUILD_IMAGE=true ./generate-protos.sh
```

### Using Make Commands
```bash
# Show help
make help

# Build Docker image only
make build

# Generate all proto bindings
make generate

# Clean and rebuild everything
make rebuild

# Open shell in container for debugging
make shell

# Show tool versions
make versions
```

## Technical Details

### Language-Specific Configurations

**C (nanopb)**
- Uses nanopb plugin for embedded-friendly code
- Removes all validation annotations via AWK preprocessing
- Generates fixed-size structs suitable for microcontrollers

**C++**
- Standard protoc generation with buf.validate annotations preserved
- Annotations embedded as field options/extensions in generated code
- Includes `buf/validate/validate.pb.h` header references
- Runtime validation requires protovalidate-cc and CEL-C++ libraries (not included in generated output)
- Applications must link against protovalidate-cc for runtime validation

**Go**
- Uses `buf generate` with remote BSR plugins (buf.build/protocolbuffers/go, buf.build/grpc/go)
- buf.validate annotations preserved for runtime validation with protovalidate-go
- Package paths preserved from proto files
- **Note:** Subject to BSR rate limits (see rate limits section below)

**Kotlin**
- Uses local `protoc --kotlin_out` (not buf — so the proto package is respected without a prefix)
- buf.validate annotations preserved for runtime validation
- Generates Kotlin-specific protobuf classes with DSL builders
- Runtime validation requires protovalidate Kotlin library

**Java**
- Standard protoc generation with buf.validate annotations preserved
- Runtime validation requires protovalidate Java library
- Package structure follows proto package declarations

**TypeScript (Standard)**
- Uses ts-proto for idiomatic TypeScript without validation
- Configured options: esModuleInterop, forceLong=long
- Generates index files for easier imports
- Output directory: `output/typescript/`

**TypeScript (Validated)**
- Uses @bufbuild/protoc-gen-es with @bufbuild/protovalidate
- Includes buf.validate annotations for runtime validation
- Generates TypeScript with validation support
- Published as @lpportorino/jettison-protovalidate-es
- Output directory: `output/typescript-validated/`

**Rust**
- Uses prost-build in a temporary Cargo project
- Handles all proto files in single compilation
- Creates module structure automatically

**Zig**
- Uses protoc-gen-zig from Arwalk/zig-protobuf for Zig code generation
- Removes all validation annotations via AWK preprocessing (no buf.validate support)
- Proto3 only
- Generates `.zig` files

**Python**
- Generates both .py implementation and .pyi type stubs
- Uses standard protoc Python plugin
- Compatible with mypy type checking

### Validation Support

Proto files use buf.validate annotations for validation constraints. The validated outputs include these annotations in the generated code:
- **C++**: Standard protobuf generation with buf.validate annotations preserved as field options/extensions
- **Go**: Standard protobuf generation with buf.validate annotations preserved
- **Kotlin**: Standard protobuf generation with buf.validate annotations preserved
- **Java**: Standard protobuf generation with buf.validate annotations preserved

Runtime validation requires the protovalidate libraries:
- **C++**: https://github.com/bufbuild/protovalidate-cc (requires CEL-C++ 0.11.0+)
- **Go**: github.com/bufbuild/protovalidate-go
- **Kotlin**: build.buf:protovalidate-kotlin
- **Java**: build.buf.protovalidate

**Important Notes**:
- C++ generated code includes buf.validate header references, but applications must build and link against protovalidate-cc separately
- The protovalidate-cc library is not included in the Docker image or generated output (it's only needed at runtime by applications)
- Validation uses buf.validate (protovalidate), not protoc-gen-validate (PGV)

### JSON Descriptor Generation

The JSON descriptor generation script has been enhanced to use buf CLI when available, which properly preserves buf.validate annotations and CEL expressions:

1. **Primary method (buf CLI)**:
   - Detects if buf is installed in the Docker container
   - Uses `buf build` with `--exclude-source-info` flag for cleaner output
   - Generates both complete descriptor set and individual file descriptors
   - **Preserves all buf.validate annotations with CEL expressions**

2. **Fallback method (protoc + Python)**:
   - Used when buf CLI is not available
   - Attempts to preserve extensions using enhanced JSON serialization options
   - May not fully preserve custom extensions like buf.validate
   - Includes warning about potential limitation

**CEL Expression Preservation**:
- Validation rules are stored in field options under `[buf.validate.predefined]`
- Each rule includes:
  - `id`: Rule identifier (e.g., "float.gte", "int32.lt")
  - `expression`: Complete CEL expression for validation
  - Error message templates with formatting

**Example preserved annotation**:
```json
"options": {
  "[buf.validate.predefined]": {
    "cel": [{
      "id": "float.gte",
      "expression": "!has(rules.lt) && !has(rules.lte) && (this.isNan() || this < rules.gte)? 'value must be greater than or equal to %s'.format([rules.gte]) : ''"
    }]
  }
}
```

## Environment Variables

- `PROTO_SOURCE_DIR`: Input directory (default: `./proto`)
- `OUTPUT_BASE_DIR`: Output directory (default: `./output`)
- `REBUILD_IMAGE`: Force Docker rebuild (default: `false`)

## Known Limitations

1. C++ validation annotations are preserved in generated code, but runtime validation requires applications to build and link protovalidate-cc separately
2. nanopb (C) requires annotation removal (doesn't support extensions)
3. All proto files must be compiled together for cross-references
4. Docker required for consistent environment
5. GitHub Actions required for automated distribution
6. Buf Schema Registry (BSR) rate limits apply to Go generation (see below)
7. Zig (zig-protobuf) does not support buf.validate annotations or proto2

## Buf Schema Registry (BSR) Rate Limits

Go generation uses `buf generate` with remote plugins, which connects to the Buf Schema Registry. Rate limits apply:

### Limits
| Service | Unauthenticated | Authenticated |
|---------|-----------------|---------------|
| Code Generation | 10 req/hour (10 burst) | 960 req/hour (120 burst) |
| General API | 30 req/sec (60 burst) | 30 req/sec (60 burst) |
| FileDescriptorSetService | 1 req/sec (2 burst) | 1 req/sec (2 burst) |

**Note:** Each `buf generate` command counts as one request (max 20 plugins per request).

### Detecting Rate Limits
- HTTP 429 response indicates rate limit exceeded
- Response headers: `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`

### Avoiding Rate Limits
1. **Authenticate requests**: Run `buf registry login` to increase code generation limit from 10/hour to 960/hour
2. **Batch generation**: Run `make generate` once rather than regenerating frequently
3. **Local plugins**: Consider using local plugins instead of remote BSR plugins for high-frequency development

### Troubleshooting
If Go generation fails with rate limit errors:
```bash
# Check if authenticated
buf registry whoami

# Login to BSR (increases limits significantly)
buf registry login
```

## Proto Documentation System

The `docs/` directory IS the Obsidian vault, containing generated markdown with roundtrip support (user documentation survives regeneration). Implementation files are in `.protodoc/`.

### Key Components

```
docs/                      # Obsidian vault (output)
├── .protodoc/             # Implementation files (hidden)
│   ├── proto-db.edn      # EDN database (git committed)
│   ├── scripts/          # Babashka scripts for Claude
│   │   ├── proto-search.clj
│   │   ├── proto-coverage.clj
│   │   ├── doc-next.clj
│   │   ├── proto-lint.clj
│   │   └── patch-lint.clj
│   └── tools/            # Clojure tooling
│       ├── src/protodoc/ # Clojure source
│       ├── test/protodoc/# Tests
│       ├── resources/    # Selmer templates
│       ├── Dockerfile    # temurin-25 based
│       └── deps.edn      # Dependencies
├── proto/                 # Generated per-message + per-enum markdown (cmd.* + ser.*)
└── index.md               # Generated schema index
```

### Database Schema

The `proto-db.edn` file contains:

```clojure
{:messages {"cmd.DayCamera.SetIris" {:id "cmd.DayCamera.SetIris"
                                      :name "SetIris"
                                      :package "cmd.DayCamera"
                                      :source "jon_shared_cmd_day_camera.proto"
                                      :description "User docs (preserved)"
                                      :fields [{:number 1 :name "value" :type :double
                                                :constraints {:gte 0 :lte 1}}]}}
 :enums {"ser.JonGuiDataClientType" {...}}
 :search-index {"iris" ["cmd.DayCamera.SetIris"] ...}}
```

### Interaction Metadata

Messages and fields can have optional interaction metadata for platform-agnostic UI specifications:

```clojure
;; Message-level interaction
{:interaction {:category :actuator           ; :sensor :actuator :settings :status :lifecycle :diagnostic
               :ui-pattern :slider-with-presets  ; See UI patterns below
               :feedback :pending-timeout    ; :fire-and-forget :pending-timeout :poll-confirm :optimistic-visual
               :timeout-ms 2000
               :purpose "Controls the iris aperture"
               :related-state ["ser.JonGuiDataCameraDay"]
               :related-commands ["cmd.DayCamera.SetAutoIris"]
               :preconditions ["Camera must be started" "Auto-iris disabled"]
               :notes "Implementation notes"}}

;; Field-level interaction
{:interaction {:semantic-type :normalized    ; :angle :percentage :temperature :voltage etc.
               :unit "%"                     ; Display unit
               :precision 0                  ; Decimal places
               :display-format "{value * 100}%"
               :presets [0 0.25 0.5 0.75 1.0 "auto"]}}
```

**UI Patterns (hierarchical):**
- Atomic: `:toggle` `:action-button` `:slider` `:stepper` `:indicator` `:enum-picker`
- Molecular: `:slider-with-steppers` `:press-accelerating`
- Composite: `:slider-with-presets` `:directional-mover` `:tabbed-config` `:state-machine-menu`

**Semantic Types:** `:normalized` `:angle` `:percentage` `:coordinate-geo` `:coordinate-viewport` `:temperature` `:voltage` `:current` `:power` `:distance` `:duration` `:speed` `:count` `:timestamp` `:cardinal` `:enum-label` `:toggle-state` `:identifier` `:raw`

*Authoritative enums: `UIPattern` / `SemanticType` / `FeedbackType` in [`schema.clj`](docs/.protodoc/tools/src/protodoc/schema.clj) — the lists above are illustrative and can lag the schema.*

Interaction metadata survives roundtrip regeneration and appears in the `## Interaction` section of generated markdown.

### Common Operations

```bash
# Generate docs (from repo root)
make docs-generate
make docs-docker-generate  # In Docker

# Run tests
make docs-test
make docs-docker-test      # In Docker

# Render only (DB → markdown, no parsing/extraction)
make docs-render
make docs-docker-render    # In Docker

# Coverage report
make docs-coverage
make docs-docker-coverage  # In Docker

# Lint documentation quality
make docs-docker-lint      # In Docker

# Validate database
cd docs/.protodoc/tools && clojure -M:run validate --db-path ../../proto-db.edn

# Search proto schema (via Claude command)
/proto-search iris
/proto-search camera zoom

# Coverage report (via Claude command)
/proto-coverage
```

### Claude Slash Commands

Slash commands available for proto documentation:

- `/proto-search <query>` - Fuzzy search messages, fields, enums
- `/proto-coverage` - Show documentation coverage report
- `/doc-next` - Show next undocumented message with context

These use Babashka scripts that read directly from `.protodoc/proto-db.edn`.

### Interactive Documentation Filling

The `doc-fill` skill provides an interactive workflow for filling in missing documentation:

1. **Find what's missing**: Run `/doc-next` to see undocumented items grouped by module
2. **Review context**: See field types, constraints, and suggested questions
3. **Answer questions**: Claude asks about purpose, category, UI pattern, etc.
4. **Documentation written**: Claude edits the markdown file with collected info

**Workflow example:**
```
User: /doc-next
Claude: [Shows cmd.PMU.Start needs documentation]

User: Let's document it
Claude: [Invokes doc-fill skill, asks questions interactively]
- What does PMU.Start do?
- What category? (suggesting :lifecycle)
- UI pattern? (suggesting :action-button)
...

User: [Answers each question]
Claude: [Writes documentation to docs/proto/cmd.PMU.Start.md]
```

**Questions asked for each message:**
1. Purpose - What does this message do?
2. Category - :sensor :actuator :settings :status :lifecycle :diagnostic
3. UI Pattern - :toggle :action-button :slider :slider-with-presets etc.
4. Feedback - :fire-and-forget :pending-timeout :poll-confirm :optimistic-visual
5. Related state messages (ser.*)
6. Related commands
7. For each field: semantic type, unit, precision, display format

The skill suggests answers based on field constraints and naming patterns.

### Workflow

1. **Generate** - Parse JSON descriptors, extract user content, render markdown
2. **Edit** - Users edit markdown in `docs/` (descriptions, field notes)
3. **Regenerate** - User content extracted and preserved in new output
4. **Search** - Use `/proto-search` to find messages/fields

### Data Flow

```
descriptor-set.json → parse.clj → extract.clj → proto-db.edn → render.clj → docs/*.md
                                       ↑                              │
                                       └──────── user edits ──────────┘
```

### Testing

```bash
cd docs/.protodoc/tools
clojure -M:test  # run the protodoc test suite

# See docs/.protodoc/tools/test/protodoc/ for the current test namespaces.
```

## References

### Internal Files
- See [`README.md`](./README.md) for user documentation
- See [`docs/.protodoc/tools/README.md`](./docs/.protodoc/tools/README.md) for proto docs tool documentation
- See [`scripts/proto_cleanup.awk`](./scripts/proto_cleanup.awk) for annotation removal logic

### External Documentation
- [Protocol Buffers](https://protobuf.dev/)
- [buf.validate](https://github.com/bufbuild/protovalidate)
- [nanopb](https://github.com/nanopb/nanopb)
- [ts-proto](https://github.com/stephenh/ts-proto)
- [prost](https://github.com/tokio-rs/prost)
- [Malli](https://github.com/metosin/malli) - Schema validation
- [Selmer](https://github.com/yogthos/Selmer) - Template rendering
