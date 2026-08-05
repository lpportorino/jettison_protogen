---
description: The renderer-gen fixture/codegen seam — the relocated asgard.*/uigen/lvgl_codegen closure, why its namespace names are provenance not private leaks, and what a real leak looks like there. Loads when editing tools/renderer-gen.
paths:
  - "tools/renderer-gen/**"
  # scratchcard.input is the seam's second in-process caller of
  # lvgl-codegen.core/process-screen — see "A second caller, outside the CLI
  # aliases" below. It depends on this seam's CWD contract exactly as
  # fixtures.clj and -main do, so an editor of it owes this rule too.
  - "tools/scratchcard/src/scratchcard/input.clj"
---
<!-- LOAD-TEST: renderer-gen -->

# renderer-gen — the fixture/codegen seam

`tools/renderer-gen/` turns EDN screens + this repo's proto manifests into the
fixtures the renderer battery consumes — the `:codegen` / `:fixtures` /
`:morph-fixtures` aliases in `deps.edn`, driven by `renderer.mk`. Its
`src/{asgard,uigen,lvgl_codegen}` namespaces are a relocated copy of the same
closure a private source repo carries, kept in sync so their pins and behaviour
match. Provenance and the pin rationale live in `tools/renderer-gen/deps.edn`.

## The `asgard.*` name is provenance, not a leak
The `asgard.*` namespace names are lineage artifacts of that shared closure —
NOT a private backend service that leaked into this public repo. The code is
generic proto↔EDN + manifest machinery over THIS repo's OWN public schema
(`cmd.JonSharedCmd$Root`, `ser.JonGUIState`, the `output/manifests` +
`proto-db.edn` this repo generates). Do NOT re-flag the namespace name, the
directory, or a bare "asgard" (it also names the sanctioned public UI theme) as
a private reference — that mistake has cost review cycles before.

## What a real leak looks like here
A genuine leak is a PRIVATE-INFRASTRUCTURE name in prose or a value — a private
consumer app, a transport/broker, or a storage component from the private stack.
Those belong nowhere in this public tree. They sit only in comments, docstrings,
and dead schemas — never in generated `output/**` or `controls.wasm` (the seam's
private-topology surface is inert here), so a plain source edit is the whole fix:
genericize the name in place, no proto change or regen. Sweep the tree before a
push; this seam is the one place strays recur.

NOT leaks — do NOT "scrub" these. `asgard` is also the sanctioned public UI theme
name, and `cmd_server` is named by this repo's OWN public `proto/ui/ui_input.proto`
and its generated docs. A term this repo publishes itself cannot be a leak, and
genericizing it only makes the comment less accurate than the schema it describes.
Hardware part names are in the same class: this repo's own generated proto docs
name the compute module, so a comment naming the encoder on it is describing
published fact, not leaking one.

### A VALUE can be topology even when no name is — and one deletion is on record

The paragraph above is about NAMES, and it under-reaches: a bare integer carries
no name to genericize and is a leak anyway when it encodes where something is
deployed. `docs/INTERFACE-CONTRACTS.md`'s guardrail draws the line for the whole
repository and does it by example — magic bytes, field numbers and byte offsets
are protocol facts and belong here, while *"the port→stream wiring (which UDP
port carries which stream) is deployment topology and lives in the consuming
repos, not here."*

`asgard/video_config.clj` carried exactly that wiring in a `:wt-port` key, and it
is DELETED from this tree. Two facts made the call, and both were measured rather
than assumed: nothing in this repository reads any value in that map — only
`asgard.schema`'s `(keys streams)` — and the map was the sole place in the tree
outside a font's glyph table where those integers appeared. So the values were
never wrong; they documented a producer to a checkout containing no producer,
in a repository whose own contract document says that class of fact lives
elsewhere.

**RE-SYNC IS THE HAZARD, and this paragraph is the guard.** The deletion looks
like an omission to anyone diffing this closure against the source it was
relocated from, so it will be re-added by a sync that treats difference as drift
— the same way the prose genericization above would be. It is a DELIBERATE
divergence, and it is recorded here rather than in a comment because the comment
is the thing a re-sync overwrites.

**NOTHING GATES THIS FILE, ON EITHER SIDE — do not assume otherwise.** The
identity gate a consumer runs covers the codegen LOGIC namespaces, and the
`asgard.*` namespaces in this seam are outside it. Verifiable from here: no
target in this repository byte-compares any file under
`tools/renderer-gen/src/asgard/` against anything. So a divergence in this file
is caught by review or by nothing, which is the reason it is written down.

## Cited docs and rules are NOT VENDORED HERE — do not hunt for them

Many files in this seam carry a header citing `docs/lvgl-factory/NN-*.md`, and a
handful cite `docs/ui-nodes/README`. **Neither directory exists in this
repository**, and neither is going to: they are the upstream design tree this
closure was relocated from, and the citations are PROVENANCE for where a
decision was made, not a pointer you can follow here.

Verified: `docs/lvgl-factory` and `docs/ui-nodes` are both absent, cited from
roughly twenty files between them.

Left in place deliberately rather than stripped — an external design document we
do not control is exactly the citation `.claude/rules/claude-md-policy.md`
sanctions, provided the reader is told where it lives. This paragraph is that
telling. What you must NOT do is treat one as a live path: do not `ls` for it,
do not conclude the file is stale because the path 404s, and do not add a new
citation in that shape without saying, at the citation, that it is external.

**A THIRD SHAPE CARRIES THE SAME FACT AND HIDES IT BETTER — a BARE rule
filename.** A few docstrings under
`tools/renderer-gen/src/lvgl_codegen/construct/` attribute a constraint to an
engineering rule by filename alone, with no directory in front of it:
`fail-fast.md`, `cohesion.md`, `tight-schemas.md`. Those name the same
upstream's RULE corpus, not this repo's `.claude/rules/`, and they resolve
nowhere here. A `docs/`-prefixed path at least LOOKS foreign; a bare filename
reads like a local rule, so this is the shape a reader is likeliest to
`ls .claude/rules/` for, miss, and write off as stale. It is not stale: in every
one of those sentences the reason is stated INLINE and the citation only
attributes it, so the sentence is complete whether or not the target resolves.

**A sweep to strip these citations has been proposed once and refused.** The
ground is the one this section already gives — the citation is the only record
of WHERE a decision was made, so deleting it destroys that and leaves the reader
no better off — plus one the bare-filename shape adds: every one of them sits in
a sentence whose reason is already stated inline, which makes the edit look free
precisely where it buys nothing. Recording the refusal here is cheaper than
re-deriving the argument, and this is the file a re-sync cannot overwrite.

## Sharing this seam by SOURCE ROOT rather than by copy — measured, and it does not close here

A consumer keeps byte-identical copies of some of these namespaces and gates the
identity on its own side. The obvious improvement is to stop copying: ship the
shared subset as a source root the consumer takes as a dependency. It cannot be
built from this repository alone, and the reason is a measurement rather than a
judgement — so it is recorded here instead of being re-derived by whoever tries
next.

**The closure is not the list.** A shared root is not the files somebody
enumerated; it is every first-party namespace those files LOAD, and a root
missing one does not fail a review — it fails to load. Measure it before
designing anything: `dev/shared_closure_probe.clj` takes a candidate set as
arguments and prints the closure, what the set drags in, and the third-party
deps the root would owe. Run it rather than trusting a number written here.

Measured for the set a consumer currently mirrors, it pulls in namespaces that
are NOT in that set — and each one is a different obstacle, not one repeated:

- **`asgard.schema` (and, through it, `asgard.video-config`).** This is the open
  sub-decision, and the answer is SHED, not include. What the shared code
  actually reaches for is ONE named Malli primitive; the rest of that namespace
  is video-event and log-event schemas plus a stream table, which are inert in
  this tree and are a CONSUMER's own contract, not shared vocabulary. Shipping
  them in a root named "shared" would publish a consumer's application schemas
  as though this repo owned them, and would put a stripped copy of a namespace
  the consumer defines for real onto the consumer's own classpath — where
  correctness then depends on classpath ORDER, silently. The primitive should
  come from somewhere both sides can own; the surrounding namespace should not
  travel at all.
- **`lvgl-codegen.proto-schema`.** Reached by three of the mirrored namespaces,
  and outside the identity gate. **The durable structural point stands alone and
  needs no consumer attribution: an identity gate asserts the LEAVES of a graph,
  never its CLOSURE** — which is the reason this section exists at all, since a
  set chosen by enumerating what to mirror does not thereby cover what those
  files LOAD. WHY a given consumer excludes a given file is that consumer's own
  business and is not visible from here; do not supply a motive for it. It is
  HAND-AUTHORED in this
  repository — no producer emits it, it carries no generated banner, the lint
  substrate classifies it as hand-authored by deriving `generated?` from the path
  (`/generated/`), and it is the measured maximum behind the `:publics-block`
  ceiling, a gate that excludes generated files by construction. So it is not a
  projection, and a shared root would have to MOVE it or duplicate it like any
  other hand-authored member. How a CONSUMER holds its copy is not visible from
  here and is deliberately not asserted either way.
- **`lvgl-codegen.generated.enums`.** A generated projection, and the ONE
  generated file inside a gated root — emitted by `lvgl-codegen.construct.factory`
  through `make -f renderer.mk construct-bindings` and byte-compared by
  `check-renderer`. A source root would still have to decide whether it SHIPS the
  projection or expects the consumer's own.
- **The two are NOT peers, and the ordering is the load-bearing part.**
  `proto-schema` REQUIRES `generated.enums`, so one is a consumer of the other —
  which means a root that took the hand-authored file without its generated
  dependency would not fail review, it would fail to LOAD. That is the same
  closure argument one level down, and it is why "just ship the shared subset"
  keeps producing a set that is too small.

**And the root cannot be NARROWED without either a move or a duplicate.**
`:paths` names directories, and the shared namespaces sit interleaved with
protogen-only ones in the same directories, so a narrowed root means either
relocating those files — which breaks a path the consumer's gate resolves, i.e.
exactly the simultaneous consumer edit a donation must not require — or a
directory of symlinks, which is a second hand-maintained roster of the same
membership fact and rots the way the first one can. Taking the WHOLE project as
a dependency instead avoids both and reintroduces the `asgard.*` shadowing above.

None of that makes the copy the right mechanism. It makes the shared root a
change whose first step is upstream and structural — give the shared code a
dependency closure that contains only shared things — rather than a packaging
change. The probe is what tells you when that step is done: a candidate set
whose closure equals itself, plus third-party, is a set that can ship as a root.

## Load-bearing subset vs. inert weight
Only a small generic subset is reachable from the battery — the manifest joins,
the `cmd.Root` pronto wrap, and the Malli primitives. The video/log schemas, the
state-decode path, and the runtime command backends are inert in this tree,
carried by the relocate-whole decision and retired when the seam converges onto
`tools/devcards`' public UiAst builder. `renderer.md` covers the C interpreter +
battery contract this seam feeds.

## A second caller, outside the CLI aliases

`tools/scratchcard` reaches `lvgl-codegen.core/process-screen` (plus
`load-ui-defs` / `validate-class-defs!` / `component/load-components`)
in-process, not through `:codegen` / `:fixtures` / `:morph-fixtures` — its
`deps.edn` takes `protogen/renderer-gen` as a `:local/root` edge and calls the
namespace directly. It is a caller of the SAME reachable subset above, over an
author-supplied screen rather than the committed `renderer/edn/screens/`
corpus, never a wider one. Two invocation shapes reach it: `scratchcard-lane`,
inside `check-renderer`, renders the shipped example; the live per-fork daemon,
gated by nothing, renders whatever an author points it at
(`.claude/rules/scratch-devcard.md` covers that side).

This is why the `:codegen` alias's CWD contract — `edn/` + `assets/` resolved
against the process's own working directory — is now load-bearing for two
invocation shapes rather than one. A long-lived daemon has no meaningful
per-request CWD, so this caller cannot inherit it: `scratchcard.input` resolves
every path absolutely instead. Anyone changing what this seam assumes about its
working directory now has every in-process call site to satisfy — `core.clj`'s
own `-main`, `fixtures.clj`, and `scratchcard.input` — not the first two alone.
