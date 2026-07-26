# renderer.mk — the renderer battery entry.
#
# Lives at the protogen root, beside renderer/ (the interpreter tree:
# wasm.mk + lv_conf.h + src/ + lvgl/ + generated/ + assets/ +
# wasm_harness/ + coverage_matrix/ + tools/ + edn/screens/).
# Invoke from the protogen root: `make -f renderer.mk check-renderer`.
#
# Provenance: every recipe here is adapted from the private source repo's
# battery (its harness-test / morph-parity / matrix-gate / demo-parity
# targets and its wasm build targets), with two deliberate deltas:
#   - the source repo's gate-cache wrapping is STRIPPED (a ratified
#     home-move decision: the battery runs UNCACHED first — protogen's push
#     cadence is low; port a gate-cache mechanism only on demonstrated pain);
#   - the container-exec proxy is gone — this file assumes it already
#     runs inside the toolchain container (locally: docker exec into it; in
#     CI: the Dockerfile.base image), the same posture as protogen's other
#     Make targets.
#
# Honesty ledger — what the battery consumes, and from where:
#   - `wasm` / `reference` are fully self-contained: wasm.mk + the vendored
#     lvgl/ + generated/ nanopb projections are all in renderer/.
#   - `fixtures` is the devcards corpus build+judge: tools/devcards' ONE CLI
#     against the freshly-built renderer/output/controls.wasm, with the ui
#     bindings compiled from the checkout's OWN output/java
#     (tools/devcards/tools/compile-bindings.sh, alias :bindings).
#   - The harness/oracle fixture generators (visual-regression scenes, morph
#     triples, the matrix/demo EDN→pb codegen) run from tools/renderer-gen —
#     the INTERIM fixture source relocated from the private source repo,
#     with its own deps.edn and target/proto-classes
#     (tools/compile-protos.sh). The ratified design still converges the
#     fixture source onto tools/devcards' public UiAst builder; when that
#     emitter lands it replaces these aliases — one fixture source for
#     oracles AND devcards.
#   - coverage_matrix/run.sh and tools/demo-parity.sh are ROOT-ANCHORED
#     drivers: WS derives from the renderer tree root, codegen runs via
#     `rgen` in tools/renderer-gen, and tools/in-container.sh fails loud
#     outside a container.

SHELL := bash

R := renderer
RGEN := tools/renderer-gen

.PHONY: wasm reference proto-classes bindings fixtures harness interaction \
	oracles morph-parity morph-fixtures matrix demo-parity manifests \
	generated-projection \
	devcards-test reload decode-limits clj-schema-test check-renderer \
	wasm-present fixtures-prebuilt gallery-prebuilt interaction-prebuilt

# ── Build ────────────────────────────────────────────────────────────────────
# Release build: -O2 -flto -> renderer/output/controls.wasm (the shipped,
# gate-validated artifact — proven bit-identical to the private source
# repo's build at relocation time).
wasm:
	$(MAKE) -C $(R) -f wasm.mk -j$$(nproc) all

# reference.wasm: the demo-parity/matrix diff oracle (never deployed).
reference:
	$(MAKE) -C $(R) -f wasm.mk -j$$(nproc) all reference

# ── Classpath producers (batch javac; clean rebuild, no live-JVM consumer) ──
# renderer-gen's proto classes: ALL of output/java + pronto's Java helpers —
# what the relocated pronto mappers load at require time.
proto-classes:
	cd $(RGEN) && bash tools/compile-protos.sh

# devcards' ui bindings: output/java/ui via -sourcepath (its own script).
bindings:
	cd tools/devcards && bash tools/compile-bindings.sh

# ── Devcards corpus (the render-level goldens) ──────────────────────────────
# The devcards pipeline's ONE CLI (devcards.core `generate`): build every
# card, render across theme families × dark/light, judge invariants +
# vanilla≡stock + the state-contract lanes, and (re)write the golden
# manifests. Exit code is the verdict; CI additionally asserts the committed
# goldens/ are unchanged (manifest freshness).
fixtures: wasm bindings
	cd tools/devcards && clojure -M:bindings:run generate

# ── CI prebuilt-wasm entries (no WASI toolchain on the runner) ──────────────
# The devcards corpus + gallery CI job consumes an ALREADY-BUILT
# controls.wasm (renderer.yml's battery job uploads it; devcards.yml builds it
# via the build-controls-wasm composite action) — this target must NEVER trigger
# the wasm build itself (a prebuilt runner may have no WASI-SDK). The
# guard fails LOUD when the artifact is absent: a missing wasm is a
# sequencing bug, never a skip.
wasm-present:
	@test -f $(R)/output/controls.wasm || { \
		echo "FATAL: $(R)/output/controls.wasm missing — build it (make -f renderer.mk wasm) or download the battery artifact first" >&2; \
		exit 1; }

fixtures-prebuilt: wasm-present bindings
	cd tools/devcards && clojure -M:bindings:run generate

# Gallery + generated doc pages against the prebuilt wasm — CI diffs
# tools/devcards/docs afterwards (a pixel-shifting renderer change must
# re-mint the gallery in the same change, exactly like the goldens).
gallery-prebuilt: wasm-present bindings
	cd tools/devcards && clojure -M:bindings:run gallery

# ── Harness suite (wasmtime host; adapted from the source repo's battery) ───
# visual_regression reads renderer/output/fixtures/*.pb plus the tabview
# routing screen renderer/output/ui/tabview_demo.pb — both generated fresh
# here from tools/renderer-gen (regeneration every run, never staleness).
harness: wasm proto-classes
	cd $(RGEN) && clojure -M:fixtures --tokens ../../output/manifests/design-tokens.json \
		--output ../../$(R)/output/fixtures
	cd $(RGEN) && clojure -M:codegen --tokens ../../output/manifests/design-tokens.json \
		--input edn/screens --output ../../$(R)/output/ui
	cd $(R)/wasm_harness && PATH=$$HOME/.cargo/bin:$$PATH \
		cargo test --test visual_regression

# ── Composition interaction suite (the wasmtime engine half) ────────────────
# Re-renders the SAME card bytes the devcards runner built (the `fixtures`
# lane persists them under tools/devcards/out/composition/), byte-compares
# the raw framebuffers cross-engine, and replays the pointer contract
# natively (press-seek / drag / ext-click envelope / dock fold).
#
# The card source is a DECLARED prerequisite, not merely a documented one. The
# ordering used to rest on check-renderer happening to list fixtures earlier —
# which meant `make -f renderer.mk interaction` on its own hit the guard below
# instead of building what it needs, and which a parallel build would discard
# outright (make is free to start an unconstrained target at any time).
#
# That edge is exactly why this lane needs a PREBUILT sibling, and why it cannot
# be one target: `fixtures` depends on `wasm`, so declaring it made the lane
# unrunnable on a runner with no WASI toolchain — the CI fixtures job, which
# consumes the battery job's uploaded wasm precisely so the corpus is judged
# against the SAME bytes the bit-identity step hashed. Provisioning a second
# clang there would rebuild the wasm OVER the downloaded artifact, which is the
# drift the artifact handoff exists to prevent. So `interaction` joins the
# established `*-prebuilt` family (fixtures/gallery) instead: same suite, same
# guard, differing only in HOW the cards arrive.
#
# The two entry points share ONE recipe home. Splitting the guard + cargo run
# into a third target they both depend on would be WRONG for the same reason the
# missing edge was: sibling prerequisites carry no ordering, so a parallel build
# could run the suite before the cards exist.
#
# The recipe takes the CARD SOURCE as $(1) purely so the guard can name the
# target the CALLER can actually run. A fixed string would tell a WASI-less host
# to run `fixtures` — the WASI-only target whose absence is the entire reason
# interaction-prebuilt exists — sending the operator straight into the failure
# this lane was split to avoid. A fail-loud message that misdirects is worse
# than a terse one.
#
# The guard STAYS in both. It is not redundant with the edge: the edge makes make
# BUILD the cards, the guard catches them being absent for any other reason, and
# it is also the thing that would fire first if either dependency were ever
# removed again. A missing input is a sequencing bug, never a skip.
define interaction-suite
@test -d tools/devcards/out/composition/cards || { \
	echo "FATAL: tools/devcards/out/composition missing — run 'make -f renderer.mk $(1)' first" >&2; \
	exit 1; }
cd $(R)/wasm_harness && PATH=$$HOME/.cargo/bin:$$PATH \
	cargo test --test composition_interaction
endef

interaction: fixtures
	$(call interaction-suite,fixtures)

interaction-prebuilt: fixtures-prebuilt
	$(call interaction-suite,fixtures-prebuilt)

# ── Reload-cycle regression (full-load teardown; NOT a morph oracle) ────────
# Repeated controls_load_ui sequences the morph oracles structurally cannot
# reach — TTF/style-morph reload cycles, plus the adversarial DUPLICATE-uid
# full-load case (the mirror of the degenerate `duplicate_insert_uid` PATCH
# case on the load entry point). The renderer must refuse a colliding uid
# CLEANLY: a collided node stays unidentified (no dup uid in dump_tree), so no
# reconciler ever acts on a mis-targeted (obj, style) pair. Takes the shared
# morph-fixtures prerequisite (below) so it can never read a stale fixture. A NEW
# cargo test binary is NOT auto-run by the named-test lanes above, so it is wired
# here explicitly.
reload: wasm proto-classes morph-fixtures
	cd $(R)/wasm_harness && PATH=$$HOME/.cargo/bin:$$PATH \
		cargo test --test reload_cycle

# The (base, target, patch) triples BOTH morph lanes read. A shared phony
# prerequisite rather than a step duplicated into each lane: make builds a phony
# target once per invocation, so a full battery regenerates these ONCE while each
# lane still cannot read a stale fixture — the never-staleness property the
# duplicated form was there for, at one generation per battery instead of two
# ~8s + ~7s of identical work). Invoking either lane alone still regenerates.
# proto-classes is DECLARED, not merely implied. The emitter loads pronto, which
# needs the compiled proto classes; serially it only ever worked because `reload`
# and `morph-parity` list `proto-classes` BEFORE `morph-fixtures` and make walks
# a prerequisite list left-to-right. A parallel build discards that ordering, and
# the failure is not subtle: `ClassNotFoundException: pronto.ProtoMap`. Found by
# actually running -j rather than by reading the makefile.
morph-fixtures: proto-classes
	cd $(RGEN) && clojure -M:morph-fixtures --tokens ../../output/manifests/design-tokens.json \
		--output ../../$(R)/output/morph-fixtures

# ── Decode limits (hostile-payload contracts; NOT a pixel oracle) ───────────
# Nesting depth, plus a floor pinning the widest fan-out the corpus uses —
# the paths a crafted .pb reaches and an authored screen never does. Builds its
# trees in-process, so it needs only the wasm: no fixtures, no codegen.
#
# These are the contracts nothing else in the battery can reach. The in-tree
# fixtures top out at three nesting levels and the shipped corpus at six, so a
# cap set anywhere above six is invisible to every other lane — which is exactly
# how MAX_DECODE_DEPTH went unreachable without a single gate noticing. A NEW
# cargo test binary is not auto-run by the named-test lanes, so it is wired here
# explicitly.
decode-limits: wasm
	cd $(R)/wasm_harness && PATH=$$HOME/.cargo/bin:$$PATH \
		cargo test --test decode_limits

# ── Oracles (morph parity / coverage matrix / demo parity) ──────────────────
oracles: morph-parity matrix demo-parity

# Dual-oracle morph parity: (base, target, patch) triples applied vs
# full-reload, tree + framebuffer asserted tolerance-0.
morph-parity: wasm proto-classes morph-fixtures
	cd $(R)/wasm_harness && PATH=$$HOME/.cargo/bin:$$PATH \
		cargo test --test morph_parity

# Dual-oracle coverage matrix: every covered (property, value) row rendered on
# BOTH paths (controls.wasm from EDN fixtures vs reference.wasm literal lv_*),
# tree + framebuffer asserted tolerance-0. The driver builds both wasm
# oracles + the harness itself (stale-artifact trap — see its header).
# `wasm reference` are DECLARED even though run.sh builds them itself, and that
# is the point: with the edge, make has already produced both by the time the
# script runs, so the script's own build finds them fresh and WRITES NOTHING.
# Without it, run.sh is a SECOND WRITER of renderer/output/{controls,reference}
# .wasm while harness/reload/decode-limits READ those same artifacts — harmless
# serially, a write/read race under -j. The in-script build stays so the script
# remains runnable standalone (its usage line and README document that).
matrix: proto-classes wasm reference
	cd $(R) && bash coverage_matrix/run.sh

# Demo-parity capstone: lv_demo_widgets rendered both ways, BIT-EQUAL per tab.
demo-parity: proto-classes wasm reference
	cd $(R) && bash tools/demo-parity.sh

# ── Manifest freshness ──────────────────────────────────────────────────────
# The ratified projections protogen publishes from its design/caps sources —
# the two manifests (tokens.edn / renderer.c's caps mirror) plus the native
# theme's generated/theme_tokens.h (tokens.edn's C projection, src/theme.c's
# input) — are emitted from their homes, but nothing regenerated or diffed
# them, so a WRONG projection reddens the oracles while a STALE one (the
# source moved, the projection was not re-emitted) would pass green forever.
# Regenerate each and fail if the committed copy is not byte-identical to a
# fresh emit; a red run leaves the corrected copies in the tree to commit (the
# same regenerate-then-diff shape lint.mk uses). No wasm / proto-classes
# needed — the emitters read tokens.edn and the renderer.c source directly.
# Emit to a temp dir and cmp against the committed copies — NOT `git diff`: this
# runs inside the Dockerfile.base container where protogen is a submodule and its
# .git is not resolvable. A stale projection is regenerated in place (review + commit).
manifests:
	@tmp="$$(mktemp -d)"; \
	( cd $(RGEN) \
	  && clojure -M -m renderer-gen.design-tokens-json \
	       --tokens edn/tokens.edn --output "$$tmp/design-tokens.json" \
	  && clojure -M -m renderer-gen.renderer-caps-json \
	       --renderer "../../$(R)/src/renderer.c" --output "$$tmp/renderer-caps.json" \
	  && clojure -M -m lvgl-codegen.theme-tokens \
	       --tokens edn/tokens.edn --output "$$tmp/theme_tokens.h" \
	  && clojure -M -m lvgl-codegen.gesture-thresholds \
	       --tokens edn/gesture-thresholds.edn --output "$$tmp/gesture_thresholds.h" ) \
	  || { rm -rf "$$tmp"; echo "FATAL: manifest emit failed" >&2; exit 1; }; \
	rc=0; \
	for pair in \
	  "design-tokens.json:output/manifests" \
	  "renderer-caps.json:output/manifests" \
	  "theme_tokens.h:$(R)/generated" \
	  "gesture_thresholds.h:$(R)/generated"; do \
	  f="$${pair%%:*}"; d="$${pair##*:}"; \
	  if ! cmp -s "$$d/$$f" "$$tmp/$$f"; then \
	    cp "$$tmp/$$f" "$$d/$$f"; \
	    echo "FATAL: $$d/$$f was STALE vs a fresh emit — regenerated in place; review and commit it." >&2; \
	    rc=1; \
	  fi; \
	done; \
	rm -rf "$$tmp"; \
	$(MAKE) --no-print-directory -f renderer.mk manifests-proto-db || rc=1; \
	[ "$$rc" -eq 0 ] && echo "manifests: fresh (design-tokens + renderer-caps + theme-tokens.h + gesture-thresholds.h + proto-db trio)"; \
	exit "$$rc"

# The proto-db-derived trio (signals/sub-signals/endpoints.json) shares the
# output/manifests/ directory with the pair above but NOT their producer: they
# come from docs/.protodoc/tools reading proto-db.edn, via the Makefile's
# `docs-manifests` target, and had no freshness comparison at all. That gap is
# not hypothetical — signals.json kept publishing an altitude bound for ~150
# commits after the proto dropped it, and was corrected only as an incidental
# side effect of an unrelated docs regeneration.
#
# `make generate` does NOT cover this: it regenerates language bindings, not
# these manifests. A green generate says nothing about their freshness.
#
# THE STAMP IS EXCLUDED FROM THE COMPARISON, and that is load-bearing rather
# than a convenience. Every one of these files carries `generated-at` (and
# endpoints.json a `protogen-commit`) holding the SHORT COMMIT SHA, injected at
# generation time. A byte-comparison including it differs on EVERY commit, so
# the gate would be permanently red — worse than absent, because a gate that is
# always red is one everybody learns to skip. Both sides are normalised to a
# sentinel so the comparison is about CONTENT.
#
# THIS LEG LOADS FROM SOURCE (`-M:run`) ON PURPOSE — DO NOT "SPEED IT UP" WITH
# `-M:aot:run`. The committed manifests it compares against are produced by the
# Makefile's `docs-manifests`, which runs AOT-loaded. So this comparison is also
# the only CONTINUOUS check that protodoc's two load modes still emit the same
# bytes; switching this side to AOT too would buy a couple of seconds and
# silently retire that check, leaving the equivalence proven exactly once, by
# hand, at the commit that introduced AOT. The seconds are not worth it.
.PHONY: manifests-proto-db
manifests-proto-db:
	@tmp="$$(mktemp -d)"; \
	( cd docs/.protodoc/tools \
	  && clojure -M:run manifest --db-path ../proto-db.edn \
	       --config-path ../manifest-config.edn --output-dir "$$tmp" \
	       --git-sha SENTINEL ) >/dev/null \
	  || { rm -rf "$$tmp"; echo "FATAL: proto-db manifest emit failed" >&2; exit 1; }; \
	rc=0; \
	for f in signals.json sub-signals.json endpoints.json; do \
	  [ -f "$$tmp/$$f" ] || { echo "FATAL: emit produced no $$f" >&2; rc=1; continue; }; \
	  sed -e 's/"generated-at":"[^"]*"/"generated-at":"SENTINEL"/' \
	      -e 's/"protogen-commit":"[^"]*"/"protogen-commit":"SENTINEL"/' \
	      "output/manifests/$$f" >"$$tmp/committed-$$f"; \
	  if ! cmp -s "$$tmp/committed-$$f" "$$tmp/$$f"; then \
	    echo "FATAL: output/manifests/$$f is STALE vs proto-db.edn — run 'make docs-manifests' and commit it." >&2; \
	    rc=1; \
	  fi; \
	done; \
	rm -rf "$$tmp"; \
	[ "$$rc" -eq 0 ] && echo "manifests: proto-db trio fresh (signals + sub-signals + endpoints)"; \
	exit "$$rc"

# ── renderer/generated freshness (the nanopb projection) ────────────────────
# renderer/generated/ is where wasm.mk's -Igenerated points, and everything the
# interpreter compiles out of it is a PROJECTION of output/c — the nanopb
# bindings generate-protos.sh emits from proto/, plus the nanopb runtime it
# copies out of the pinned /opt/nanopb. Nothing produced that projection and
# nothing compared it, so it was hand-maintained: a proto change refreshed
# output/c while these files stayed put, and the reference interpreter went on
# compiling against the OLD field tags with every gate green. The failure is
# silent by construction — the wasm builds, the oracles agree with each other,
# and the only thing that disagrees is the wire.
#
# So: project-then-diff, the same shape `manifests` above uses, and cmp rather
# than `git diff` for the same reason it does — this runs inside the toolchain
# container where protogen is a submodule and its .git is not resolvable. A
# stale copy is rewritten IN PLACE and the gate reds; review it and commit.
#
# NO PREREQUISITES, deliberately: both sides are COMMITTED, so this is a
# sub-second cmp loop over 44 files with nothing to build. That is why it runs
# at the front of the battery — a stale binding makes every downstream result
# meaningless, so it has to fail before anything is compiled. It sits SECOND
# rather than first only because graal-check's primacy is itself documented and
# load-bearing; both report in well under a second, so which of the two speaks
# first is immaterial, and demoting a stated invariant to win a tie is not.
#
# WHAT IS AND IS NOT PROJECTED. renderer/generated tracks 49 files; 46 are
# covered here (44 regular + 2 symlinks) and 3 are deliberately not:
#   theme_tokens.h, gesture_thresholds.h — emitted and cmp'd by `manifests`
#     above. A second gate over them would be a second home for one fact.
#   ui_luts.h — has NO source in this repo; its own header says so. It stays
#     UNCOVERED, said out loud rather than papered over.
#
# FIVE WAYS THE OBVIOUS `cp -r output/c renderer/generated` IS WRONG, and where
# each is handled below:
#   1. It DESTROYS the two mode-120000 symlinks under generated/ui/. Only FLAT
#      regular files are copied here; the symlinks get their own loop, which
#      asserts them and recreates one that drifted.
#   2. The flattening is ASYMMETRIC: output/c/ui/ui_ast.pb.c must land at
#      generated/ui_ast.pb.c, yet its own `#include "ui/ui_ast.pb.h"` still has
#      to resolve. That is exactly what the generated/ui/ symlinks are for — a
#      quoted include is searched from the includer's own directory first, so
#      it finds generated/ui/ui_ast.pb.h and follows it back to ../ui_ast.pb.h.
#      Losing one (hazard 1) breaks the build; asserting them is what makes the
#      flattening sound, so the two hazards are one mechanism.
#   3. It is a strict SUBSET — output/c tracks 102 files and only 44 of them
#      are PROJECTED here, of which wasm.mk compiles the 21 .c (3 nanopb
#      runtime + 15 cmd + jon_shared_data_types + the 2 ui bindings); the rest
#      are the headers those include. A blanket copy would pour
#      jon_shared_data_*, opaque/** and ui_nodes into the -Igenerated
#      namespace. The three lists below ARE the projection; nothing crosses.
#   4. There are NO --delete semantics. A mirroring copy would remove the two
#      manifest-owned headers and ui_luts.h, none of which exist in output/c at
#      all. The reverse direction is instead CHECKED and never acted on: an
#      orphaned jon_shared_cmd* projection (its proto was deleted upstream)
#      reds the gate naming the file to `git rm`, and nothing is deleted here.
#   5. FILE MODES. renderer/generated is mixed (644/755/symlink) where output/c
#      is uniformly 755, and cmp compares CONTENT — it cannot see a mode flip,
#      so `cp -p` / `install` / `rsync -a` would silently rewrite 44 modes and
#      stay green. Five in-scope destinations are tracked 644 against that 755
#      source (LICENSE.nanopb and the four ui bindings). Plain `cp` preserves
#      the destination inode's mode (measured in the pinned image: a 755 source
#      over a 644 destination leaves 644), and the recipe captures and restores
#      it anyway — but ONLY AN EXISTING DESTINATION HAS A MODE TO PRESERVE. A
#      MISSING one would be created at the source's 755, and the operator told
#      to commit a 644->755 flip. So absence is not treated as staleness at
#      all: in the two FIXED lists it is a tracked-file DELETION and hard-fails
#      untouched, and in the DERIVED cmd list it is a legitimately new binding,
#      created at an EXPLICIT 755 — the mode all 30 tracked cmd projections
#      already carry — rather than at whatever cp leaves after the umask.
#
# The cmd family is DERIVED from output/c rather than listed, matching wasm.mk's
# own `$(wildcard generated/jon_shared_cmd*.pb.c)`, so a new cmd proto flows
# through both without an edit here. Its non-vacuity is guarded: this repo has
# fifteen cmd pairs, so an empty expansion means discovery broke, not that there
# is nothing to project (an empty input set is a green tick over zero coverage).
GENERATED_DIR := $(R)/generated

# The nanopb RUNTIME (copied verbatim from /opt/nanopb by generate-protos.sh)
# plus the shared scalar/enum types every cmd binding includes. All live at
# output/c's root and keep their names.
GENERATED_ROOT_FILES := LICENSE.nanopb pb.h \
	pb_common.c pb_common.h pb_decode.c pb_decode.h pb_encode.c pb_encode.h \
	jon_shared_data_types.pb.c jon_shared_data_types.pb.h

# The two ui bindings the interpreter decodes: ui.Screen (the AST it renders)
# and ui.HostToWasm (controls_host_message's input). FLATTENED out of
# output/c/ui/ — see hazard 2.
GENERATED_UI_FILES := ui_ast.pb.c ui_ast.pb.h ui_input.pb.c ui_input.pb.h

# The flatten shims: generated/ui/<name> -> ../<name>. Two symlinks, tracked at
# mode 120000, and the reason the flattened .pb.c files still compile.
GENERATED_UI_LINKS := ui_ast.pb.h ui_input.pb.h

GENERATED_CMD_FILES := $(notdir $(wildcard output/c/jon_shared_cmd*.pb.[ch]))

generated-projection:
	@if [ -z "$(strip $(GENERATED_CMD_FILES))" ]; then \
	  echo "FATAL: discovered ZERO output/c/jon_shared_cmd*.pb.[ch] — this repo" >&2; \
	  echo "  tracks fifteen cmd pairs, so an empty set means DISCOVERY broke," >&2; \
	  echo "  not that there is nothing to project." >&2; \
	  exit 1; \
	fi
	@rc=0; \
	project() { \
	  src="$$1"; dst="$$2"; newok="$$3"; \
	  if [ ! -f "$$src" ]; then \
	    echo "FATAL: $$src is missing — renderer/generated projects it, so the" >&2; \
	    echo "  source of the projection is gone. Regenerate output/c." >&2; \
	    return 1; \
	  fi; \
	  cmp -s "$$src" "$$dst" && return 0; \
	  if [ ! -e "$$dst" ]; then \
	    if [ "$$newok" != new ]; then \
	      echo "FATAL: $$dst is MISSING, not stale — its name is in a FIXED projection" >&2; \
	      echo "  list, so there is no mode to preserve and copying the 755 source in" >&2; \
	      echo "  would silently rewrite the tracked mode, which cmp cannot see (hazard" >&2; \
	      echo "  5). Restore a DELETED file with 'git checkout -- $$dst'; if you just" >&2; \
	      echo "  added the name to the list, create it with the mode you intend." >&2; \
	      return 1; \
	    fi; \
	    cp "$$src" "$$dst" && chmod 755 "$$dst" || return 1; \
	    echo "FATAL: $$dst is a NEW projection of $$src — created at 755, the mode every" >&2; \
	    echo "  tracked cmd projection carries; review and commit it." >&2; \
	    return 1; \
	  fi; \
	  mode="$$(stat -c %a "$$dst")" || return 1; \
	  cp "$$src" "$$dst" && chmod "$$mode" "$$dst" || return 1; \
	  echo "FATAL: $$dst was STALE vs $$src — regenerated in place; review and commit it." >&2; \
	  return 1; \
	}; \
	for f in $(GENERATED_ROOT_FILES); do \
	  project "output/c/$$f" "$(GENERATED_DIR)/$$f" fixed || rc=1; \
	done; \
	for f in $(GENERATED_CMD_FILES); do \
	  project "output/c/$$f" "$(GENERATED_DIR)/$$f" new || rc=1; \
	done; \
	for f in $(GENERATED_UI_FILES); do \
	  project "output/c/ui/$$f" "$(GENERATED_DIR)/$$f" fixed || rc=1; \
	done; \
	for l in $(GENERATED_UI_LINKS); do \
	  dst="$(GENERATED_DIR)/ui/$$l"; want="../$$l"; \
	  if [ -L "$$dst" ] && [ "$$(readlink "$$dst")" = "$$want" ]; then continue; fi; \
	  mkdir -p "$(GENERATED_DIR)/ui" && rm -f "$$dst" && ln -s "$$want" "$$dst" || rc=1; \
	  echo "FATAL: $$dst was not the symlink -> $$want (a recursive copy flattens it" >&2; \
	  echo "  into a regular file, and the flattened .pb.c then cannot resolve its own" >&2; \
	  echo "  quoted include) — recreated; review and commit it." >&2; \
	  rc=1; \
	done; \
	for f in $(GENERATED_DIR)/jon_shared_cmd*.pb.[ch]; do \
	  [ -e "$$f" ] || continue; \
	  b="$$(basename "$$f")"; \
	  [ -f "output/c/$$b" ] && continue; \
	  echo "FATAL: $$f is an ORPHAN — output/c has no $$b, so its proto is gone" >&2; \
	  echo "  upstream while wasm.mk still compiles this copy. Delete it: git rm $$f" >&2; \
	  rc=1; \
	done; \
	[ "$$rc" -eq 0 ] && echo "generated-projection: fresh ($(words $(GENERATED_ROOT_FILES) $(GENERATED_CMD_FILES) $(GENERATED_UI_FILES)) files + $(words $(GENERATED_UI_LINKS)) flatten symlinks vs output/c)"; \
	exit "$$rc"

# ── Devcards unit suite ─────────────────────────────────────────────────────
# The pure-helper tests (tools/devcards/test — dump-tree reductions, image
# math). No wasm / proto-classes needed, so it runs early and fails cheap.
devcards-test:
	cd tools/devcards && clojure -M:test

# ── renderer-gen schema guard suite ─────────────────────────────────────────
# The lvgl_codegen schema guard tests (tools/renderer-gen/test): pure in-memory
# validate-screen-semantics checks — the leaf content-sizing guard that fails a
# childless leaf (lv_bar/lv_slider/lv_led) content-sized to a ~0px collapse. No
# wasm / proto-classes needed (schema/ loads only enums + style-props + malli),
# so it runs early and fails cheap.
clj-schema-test:
	cd $(RGEN) && clojure -M:test

# ── The battery ─────────────────────────────────────────────────────────────
# graal-check FIRST, and it is deliberately not Clojure. Polyglot degrades to the
# INTERPRETER on a JDK without JVMCI + the Graal compiler — same results, ~21x
# slower, and the battery is 25 minutes instead of 2. devcards.host's
# assert-optimizing-runtime! already hard-fails on that, but only once a leg
# actually RENDERS, roughly a minute in past manifests/tests/wasm/reference/
# fixtures. This reports in well under a second.
#
# `java -version` rather than a Clojure snippet BECAUSE the Clojure floor is
# ~3.8s of load+compile (measured), so a Clojure precheck would tax every single
# battery run by more than the misconfiguration it guards against costs to
# discover late. A plain JVM has no such floor.
#
# NECESSARY, NOT SUFFICIENT — which is why the engine assertion STAYS as the
# backstop rather than being replaced. This proves the JDK is JVMCI-capable; it
# does NOT prove Truffle actually selected an optimizing runtime. Only
# Engine.getImplementationName() answers that, and it is checked where the
# engine is built.
.PHONY: graal-check
graal-check:
	@java -version 2>&1 | grep -q jvmci || { \
	  echo "FATAL: this JDK reports no jvmci — Truffle will INTERPRET the wasm" >&2; \
	  echo "  (~21x slower; the battery becomes ~25min instead of ~2min)." >&2; \
	  echo "  Run the battery in protogen's own image: tools/uber.sh 'make -f renderer.mk check-renderer'" >&2; \
	  java -version 2>&1 | sed 's/^/  /' >&2; \
	  exit 1; }
	@echo "graal-check: JVMCI present ($$(java -version 2>&1 | sed -n 2p))"

check-renderer: graal-check generated-projection manifests devcards-test clj-schema-test wasm reference fixtures harness interaction oracles reload decode-limits
	@echo "renderer battery: GREEN (generated-projection + manifests + devcards-test + clj-schema-test + wasm + reference + fixtures + harness + interaction + oracles + reload)"
