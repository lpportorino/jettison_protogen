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
	oracles morph-parity matrix demo-parity manifests devcards-test reload \
	clj-schema-test check-renderer wasm-present fixtures-prebuilt gallery-prebuilt

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
# natively (press-seek / drag / ext-click envelope / dock fold). Runs AFTER
# `fixtures`; the guard fails LOUD when the devcards output is absent — a
# missing input is a sequencing bug, never a skip.
interaction:
	@test -d tools/devcards/out/composition/cards || { \
		echo "FATAL: tools/devcards/out/composition missing — run 'make -f renderer.mk fixtures' first" >&2; \
		exit 1; }
	cd $(R)/wasm_harness && PATH=$$HOME/.cargo/bin:$$PATH \
		cargo test --test composition_interaction

# ── Reload-cycle regression (full-load teardown; NOT a morph oracle) ────────
# Repeated controls_load_ui sequences the morph oracles structurally cannot
# reach — TTF/style-morph reload cycles, plus the adversarial DUPLICATE-uid
# full-load case (the mirror of the degenerate `duplicate_insert_uid` PATCH
# case on the load entry point). The renderer must refuse a colliding uid
# CLEANLY: a collided node stays unidentified (no dup uid in dump_tree), so no
# reconciler ever acts on a mis-targeted (obj, style) pair. Regenerates the
# morph-fixtures it reads (never staleness), exactly like morph-parity. A NEW cargo test
# binary is NOT auto-run by the named-test lanes above, so it is wired here
# explicitly.
reload: wasm proto-classes
	cd $(RGEN) && clojure -M:morph-fixtures --tokens ../../output/manifests/design-tokens.json \
		--output ../../$(R)/output/morph-fixtures
	cd $(R)/wasm_harness && PATH=$$HOME/.cargo/bin:$$PATH \
		cargo test --test reload_cycle

# ── Oracles (morph parity / coverage matrix / demo parity) ──────────────────
oracles: morph-parity matrix demo-parity

# Dual-oracle morph parity: (base, target, patch) triples applied vs
# full-reload, tree + framebuffer asserted tolerance-0.
morph-parity: wasm proto-classes
	cd $(RGEN) && clojure -M:morph-fixtures --tokens ../../output/manifests/design-tokens.json \
		--output ../../$(R)/output/morph-fixtures
	cd $(R)/wasm_harness && PATH=$$HOME/.cargo/bin:$$PATH \
		cargo test --test morph_parity

# Dual-oracle coverage matrix: every covered (property, value) row rendered on
# BOTH paths (controls.wasm from EDN fixtures vs reference.wasm literal lv_*),
# tree + framebuffer asserted tolerance-0. The driver builds both wasm
# oracles + the harness itself (stale-artifact trap — see its header).
matrix: proto-classes
	cd $(R) && bash coverage_matrix/run.sh

# Demo-parity capstone: lv_demo_widgets rendered both ways, BIT-EQUAL per tab.
demo-parity: proto-classes
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
	[ "$$rc" -eq 0 ] && echo "manifests: fresh (design-tokens + renderer-caps + theme-tokens.h + gesture-thresholds.h)"; \
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
check-renderer: manifests devcards-test clj-schema-test wasm reference fixtures harness interaction oracles reload
	@echo "renderer battery: GREEN (manifests + devcards-test + clj-schema-test + wasm + reference + fixtures + harness + interaction + oracles + reload)"
