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

.PHONY: wasm reference proto-classes bindings fixtures dump-contracts harness interaction \
	oracles morph-parity morph-fixtures matrix demo-parity manifests \
	generated-projection generated-projection-canary conventions-projection \
	construct-bindings \
	devcards-test state-mirror reload decode-limits wire-constraints clj-schema-test check-renderer check-renderer-lanes \
	wasm-present fixtures-prebuilt gallery-prebuilt deadzone-canary deadzone-canary-prebuilt \
	overlap-canary overlap-canary-prebuilt \
	interaction-prebuilt \
	wasm-sha-record wasm-sha-verify wasm-inputs-verify wasm-inputs-check \
	standard-brief standard-brief-generate composition-clean doc-audit \
	ui-review-preflight ui-review-preflight-canary

# ── Atomic install of a generated file ──────────────────────────────────────
# The three freshness lanes below (`manifests`, `construct-bindings`,
# `generated-projection`) all REGENERATE-THEN-DIFF: emit to a temp dir, cmp
# against the committed copy, and on drift rewrite it in place and red the gate.
# The rewrite used to be a plain `cp`, which is truncate-then-write: a reader
# racing it sees a file that is neither the old bytes nor the new ones. Three of
# those destinations are C headers the wasm build compiles and one is a Clojure
# SOURCE file four namespaces require, so a torn read surfaces as a parse error
# attributed to the lane that READ it — a red naming the wrong clause.
#
# `mv` FROM $(mktemp -d) DOES NOT FIX THIS, and that is the whole reason this
# helper exists rather than a one-word substitution. rename(2) is atomic only
# WITHIN one filesystem, and in the pinned container it is not one: /tmp is the
# image's own overlay while /workspace is an ext4 bind mount, so `stat -c %d`
# reports different devices for them. (The overlay's device NUMBER is per
# container instance — three runs of the probe below reported 97, 169 and 196 —
# so it is the INEQUALITY that is the fact here, never a particular id.) `mv`
# across that boundary degrades to copy-then-unlink and is exactly as tearable
# as the `cp` it replaced — while READING as though the hazard had been dealt
# with, which is worse than leaving it.
#
# That is not an argument from the man page: tools/perf/atomic-install-probe.sh
# measures it by INODE IDENTITY, which is deterministic where counting torn
# reads is not. A rename(2) leaves the destination path on a NEW inode; every
# in-place write leaves it on the same one. Measured there, `cp` and
# `mv`-from-$(mktemp -d) both keep the inode and this helper does not.
#
# So the staging file is created IN THE DESTINATION'S OWN DIRECTORY, which is
# the only way to guarantee the rename is same-filesystem no matter where TMPDIR
# points. A reader then sees the old inode or the new one, never a partial one.
#
# MODE IS PRESERVED FROM THE DESTINATION, not from the source and not from
# mktemp's 600 — `generated-projection`'s hazard 5 records why (renderer/generated
# is mixed 644/755 against a uniformly-755 output/c, and cmp cannot see a mode
# flip, so a mode-rewriting install stays green while dirtying the tree). $3 is
# the mode to use when the destination does NOT exist and there is therefore
# nothing to preserve; every call site that can hit that case passes it
# explicitly.
#
# NOTHING IS STAGED ON A CLEAN TREE. Every call site sits inside an
# `if ! cmp -s` branch, so a fresh checkout creates no temp file at all; the
# `rm -f` on the failure path covers the rest.
INSTALL_ATOMIC := install_atomic() { _t="$$(mktemp "$$(dirname "$$2")/.protogen-install.XXXXXX")" || return 1; _m="$$(stat -c %a "$$2" 2>/dev/null || echo "$${3:-644}")"; cat "$$1" >"$$_t" && chmod "$$_m" "$$_t" && mv -f "$$_t" "$$2" || { rm -f "$$_t"; return 1; }; };

# ── Build ────────────────────────────────────────────────────────────────────
# Release build: -O2 -flto -> renderer/output/controls.wasm (the shipped,
# gate-validated artifact — proven bit-identical to the private source
# repo's build at relocation time).
#
# THE THREE PREREQUISITES ARE THE PROJECTIONS THIS BUILD COMPILES, and each is
# a file wasm.mk reaches through `-Igenerated`:
#   generated-projection -> renderer/generated/*.pb.[ch] + the nanopb runtime
#                           (wasm.mk's NANOPB_SRCS / GEN_SRCS / DATA_TYPES_SRCS)
#   construct-bindings   -> renderer/generated/ui_luts.h      (src/renderer.c)
#   manifests            -> renderer/generated/theme_tokens.h (src/{renderer,main,theme}.c)
#                           + gesture_thresholds.h            (src/gesture.c)
# Serially this held only because check-renderer LISTED all three before `wasm`
# and make walks a prerequisite list left to right — the same accident of list
# position the `manifests -> matrix` note below was written about, on the lane
# that consumes the most generated input in the tree. `-j` discards it.
#
# WHAT THE RACE COSTS IS ATTRIBUTION, NOT A FALSE GREEN, and saying so is the
# point: all three lanes fail non-zero on any rewrite, so a battery in which one
# of them wrote is RED no matter what `wasm` compiled. What the edge buys is
# that the red names the clause that fired instead of a downstream compile error
# on half a header, and that renderer/output/controls.wasm after a red run is
# never a build of sources that no longer exist on disk — which a subsequent
# `*-prebuilt` lane would otherwise judge as though it were the real artifact.
#
# MEASURED COST, because it is not free: `manifests` is 47.0s median and
# `construct-bindings` 11.5s (interleaved -n 3, 12 cpus, loadavg 85..142), and
# the two run concurrently, so this puts ~47s ahead of the wasm build on the
# battery's critical path. Inside check-renderer that is not extra WORK — both
# lanes run there regardless — only extra ORDER. It IS extra work for a
# lane-level `make -f renderer.mk wasm`, which .github/workflows/renderer.yml
# does in two steps — "Build controls.wasm + reference.wasm (bit-identity
# artifact)" and "Harness suite + oracles + reload-cycle + decode-limits + dump
# contracts" — each of which now drags all three in. Both already run those same
# lanes as earlier steps of the same job, so this is redundant work rather than
# new work, and it is the price of the ordering holding wherever the lanes run
# rather than only where check-renderer happens to list them.
# ~16s of the 47s is `manifests-proto-db`, which writes nothing this build reads
# and could be split back out if that ever matters; it is left whole because one
# target named `manifests` is worth more than 16s here.
# NO PREREQUISITES, DELIBERATELY — and this is a CI-shape constraint, not a
# statement about the DAG. `wasm` genuinely consumes what `generated-projection`,
# `construct-bindings` and `manifests` produce, and `check-renderer` lists all
# three BEFORE it, so the ordering holds where it matters. But CI's composite
# action calls `make -f renderer.mk wasm` directly on a PLAIN RUNNER to build the
# artifact the *-prebuilt lanes consume, and those three are VERIFICATION lanes
# that re-derive generated output and compare it. Making them prerequisites runs
# a freshness check in an environment the battery never uses and that nothing
# else exercises. Order them at the call site; do not hang them here.
wasm:
	$(MAKE) -C $(R) -f wasm.mk -j$$(nproc) all

# reference.wasm: the demo-parity/matrix diff oracle (never deployed).
#
# `wasm` IS A PREREQUISITE, AND IT IS AN ORDERING CONSTRAINT RATHER THAN A DATA
# ONE — reference.wasm does not read controls.wasm. What the two share is the
# OBJECT TREE: wasm.mk keys it as `build/$(BUILD)`, and output/reference.wasm's
# prerequisites (LIB_OBJS, STUB_OBJS, COMMON_APP_OBJS, THORVG_OBJS) are the same
# object files $(OUT)'s are, ~1500 of them from the vendored LVGL + ThorVG trees
# alone. Worse, this recipe asks for `all` TOO, so both lanes also build
# output/controls.wasm and controls.wasm.build-sha themselves. Run concurrently
# these are two independent `make` processes with no shared jobserver compiling
# and linking the same paths — the classic recursive-make double-build, and it
# corrupts rather than merely duplicating.
#
# make has no mutex, so a declared edge is how a mutex is spelled here. It is
# also why this could never have been caught by reading check-renderer's list:
# `wasm reference` are ADJACENT there and serially the second is a no-op.
#
# GNU make 4.4's `.WAIT` would say this more precisely (order without
# dependency), and it is not available: the pinned container ships GNU Make 4.3
# (measured), where `.WAIT` is parsed as an ordinary prerequisite with no rule.
reference: wasm
	$(MAKE) -C $(R) -f wasm.mk -j$$(nproc) all reference

# ── controls.wasm provisioning parity ───────────────────────────────────────
# controls.wasm is built through TWO provisioning paths, and until now nothing
# compared them:
#
#   1. the pinned toolchain IMAGE — .github/workflows/renderer.yml's battery
#      job `docker run`s Dockerfile.base, whose WASI-SDK lives at /opt/wasi-sdk;
#   2. a STOCK RUNNER — .github/actions/build-controls-wasm installs the same
#      upstream WASI-SDK release tarball under $HOME and runs `wasm` directly.
#      devcards.yml's corpus job takes this path.
#
# Both then judge the SAME committed goldens, so the two builds agreeing is
# already load-bearing — it just had no direct assertion. renderer.yml printed a
# bare `sha256sum` under a step named for bit-identity and threw the value away;
# these two targets are what give that hash a comparator.
#
# WHAT THIS DOES AND DOES NOT CATCH, because the obvious reading over-claims it.
# The VERSION cannot drift: the composite action derives WASI_SDK_VERSION by
# grepping Dockerfile.base rather than re-typing it, so both paths ask for the
# same upstream release. This is therefore NOT a version gate — that axis is
# closed by construction, and the composite action's "identical compiler at the
# identical pin" is an accurate description rather than a claim needing a check.
#
# What it catches is everything BELOW the version: an image that patches or wraps
# the SDK, a poisoned composite-action cache, wasm.mk growing
# environment-dependent behaviour — and ARCHITECTURE, which is the one axis where
# the two paths are genuinely not written the same way. Dockerfile.base picks
# WASI_ARCH from `uname -m` (arm64 on aarch64, else x86_64); the composite action
# hard-codes the x86_64 asset. Both resolve to x86_64 only because both jobs run
# on `ubuntu-latest` today, so moving either onto an ARM runner silently splits
# the toolchain — and this comparison is what would say so.
#
# The goldens catch the subset of any such divergence that moves a pixel; this
# catches the rest, and it names the drift directly instead of surfacing it as a
# mystifying golden mismatch in whichever of the two workflows happened to run.
#
# IT IS ONLY A GATE IF THE BUILD IS REPRODUCIBLE, so that was MEASURED here
# rather than assumed, over the variables that actually differ between the two
# paths. Five clean release builds in the pinned image, all byte-identical:
# the baseline; a repeat from a wiped object tree (determinism at all); the tree
# bind-mounted at the hosted runner's own workspace path instead of /workspace
# (the REPO-PATH axis); WASI_SDK pointed at /root/wasi-sdk instead of
# /opt/wasi-sdk (the SDK-PATH axis — what the composite action actually does);
# and nproc forced to 2 instead of 12 (the runner has fewer cores than a dev
# box, and `wasm` recurses with an explicit -j$$(nproc)). `strings` on the
# result finds no build path at all — only clang's own version banner — which is
# the structural reason the path axes come out flat.
#
# THE ONE AXIS THAT WAS NOT MEASURED, named rather than glossed: the HOST OS.
# Every build above ran in Dockerfile.base; nothing here can run a
# GitHub-hosted runner image, and fetching one was out of scope. clang is a
# self-contained cross-compiler emitting wasm32 with its own sysroot, so host
# libc should not reach codegen — but "should not" is an argument, not a
# measurement, and if this gate ever reds on its first run with two hashes that
# differ for no reviewable reason, THAT is the hypothesis to test first.
#
# The two halves are separate targets because they run in different places:
# `wasm-sha-record` beside the build that produced the artifact, `wasm-sha-verify`
# wherever the second build lands, with WASM_SHA_EXPECTED pointing at the
# recorded sidecar. Keeping the comparison HERE rather than inline in the
# workflow is what makes it runnable — and therefore mutation-provable — outside
# Actions.
#
# reference.wasm gets NO sidecar and that is deliberate: the composite action
# builds `wasm` only, so there is no second build to compare it against. Its
# hash stays a printed log line in renderer.yml, and the comment there says so.
WASM_ARTIFACT := $(R)/output/controls.wasm
WASM_SHA_FILE := $(WASM_ARTIFACT).sha256

# The expectation to verify against. Defaults to the sidecar this build wrote —
# which makes a bare `wasm-sha-verify` a self-check — and is overridden by the
# caller to the OTHER build's sidecar, which is the real use.
WASM_SHA_EXPECTED ?= $(WASM_SHA_FILE)

wasm-sha-record:
	@test -s $(WASM_ARTIFACT) || { \
		echo "FATAL: wasm-sha-record: $(WASM_ARTIFACT) is missing or empty — there is" >&2; \
		echo "  nothing to record. Build it first (make -f renderer.mk wasm)." >&2; \
		exit 1; }
	@sha256sum $(WASM_ARTIFACT) | awk '{print $$1}' >$(WASM_SHA_FILE)
	@echo "wasm-sha-record: $$(cat $(WASM_SHA_FILE))  $(WASM_ARTIFACT)"

# BOTH INPUTS ARE GUARDED, and that is the half that is easy to leave out. A
# missing artifact or a missing expectation means the comparison DID NOT HAPPEN,
# and "I could not look" must never print the same thing as "they match" — which
# is exactly what an unguarded `sha256sum ... | cmp -` would do on an absent
# sidecar.
wasm-sha-verify:
	@test -s $(WASM_ARTIFACT) || { \
		echo "FATAL: wasm-sha-verify: $(WASM_ARTIFACT) is missing or empty, so the" >&2; \
		echo "  provisioning-parity comparison DID NOT RUN. This is a sequencing bug" >&2; \
		echo "  (build the wasm first), never a pass." >&2; \
		exit 1; }
	@test -s $(WASM_SHA_EXPECTED) || { \
		echo "FATAL: wasm-sha-verify: expected-hash file '$(WASM_SHA_EXPECTED)' is" >&2; \
		echo "  missing or empty, so the provisioning-parity comparison DID NOT RUN." >&2; \
		echo "  It is written by 'make -f renderer.mk wasm-sha-record' beside the" >&2; \
		echo "  other build and carried here as an artifact. A gate that cannot look" >&2; \
		echo "  must never report green." >&2; \
		exit 1; }
	@have="$$(sha256sum $(WASM_ARTIFACT) | awk '{print $$1}')"; \
	want="$$(tr -d '[:space:]' <$(WASM_SHA_EXPECTED))"; \
	if [ "$$have" != "$$want" ]; then \
		echo "FATAL: wasm-sha-verify: PROVISIONING DRIFT — the two build paths do not" >&2; \
		echo "  agree on controls.wasm." >&2; \
		echo "    this build : $$have" >&2; \
		echo "    expected   : $$want  ($(WASM_SHA_EXPECTED))" >&2; \
		echo "  The pinned-image build and the stock-runner build are supposed to be" >&2; \
		echo "  the identical upstream compiler at the identical pin, and both judge" >&2; \
		echo "  the same committed goldens. Do not re-mint anything: find out which" >&2; \
		echo "  toolchain moved. renderer.mk's provisioning-parity block lists the" >&2; \
		echo "  axes already measured flat, and the one that was not." >&2; \
		exit 1; \
	fi; \
	echo "wasm-sha-verify: provisioning parity OK ($$have)"

# ── controls.wasm CONTENT provenance ────────────────────────────────────────
# A SECOND, STRONGER CLAIM BESIDE THE SHA STAMP, never instead of it.
#
# renderer/output/controls.wasm.build-sha records which commit HEAD was at when
# the stamp was last written. A consumer compares it against its gitlink and
# fails on a mismatch, which is what converts "did you rebuild after bumping the
# pin?" into an assertion. But "when did make last run" is not "what was
# compiled", and the gap is reachable: on a warm object tree, a commit that
# changes the LINK FLAGS rewrites the stamp to the new sha while the binary is
# neither relinked nor touched — exit 0, no output, no compiler invoked. Three
# further paths reach the same state; wasm.mk's own content-stamp block
# enumerates all four, each measured against the real makefile.
#
# So wasm.mk's link recipe now also writes controls.wasm.build-inputs: a digest
# over the exact file set that link compiles PLUS the description that compiles
# it. Only the link writes it, which is the whole property — a no-op rebuild
# leaves it untouched and therefore still TRUE, where the sha stamp becomes a
# fresh lie. This target is the comparator: recompute the digest from the tree
# as it stands, and a difference means the artifact predates the sources.
#
# WHAT A RED HERE MEANS: relink. It does NOT mean re-mint anything, and it is
# not a golden — the digest is derived from the tree every time it is asked for,
# so there is no committed expectation that could canonise a wrong value.
#
# THE COMPARISON LIVES IN A SCRIPT, and both reasons are mechanical rather than
# stylistic. GNU make exits 2 for ANY failed target, so a recipe cannot expose
# its own status: the verdict/precondition split would collapse into one value
# and a canary could take a missing artifact as proof that drift detection
# works, which is precisely the weakness .claude/rules/gate-enforcement.md §2
# refuses. And `lint-sh` discovers `*.sh`, so shell embedded in a Makefile is
# gated by nothing — a gate must itself be judged (§5). The script's codes:
# 1 CONTENT DRIFT (the verdict), 3 CANNOT RUN (no comparison happened).
# `wasm-sha-verify` above uses 1 for both; it is an existing consumer-facing
# contract and is left as it is rather than changed underneath its callers.
#
# NOT WIRED FOR A CONSUMER YET, and that is the additive half: nothing outside
# this repo runs this target until a consumer chooses to. The sha comparison a
# consumer has today keeps working, unchanged, because .build-sha is unchanged.
WASM_INPUTS_FILE := $(WASM_ARTIFACT).build-inputs

wasm-inputs-verify:
	@bash $(R)/tools/wasm_inputs_verify.sh \
		--artifact $(WASM_ARTIFACT) \
		--sidecar $(WASM_INPUTS_FILE) \
		--renderer-dir $(R)

# Ordered, not free-standing. `wasm-inputs-verify` deliberately declares no
# prerequisites — a prebuilt runner with no WASI-SDK must be able to ask it, the
# same reason `wasm-present` exists — so the battery, which runs its lanes under
# -j where a prerequisite LIST orders nothing, needs this edge to put the build
# first. Same mechanism as `reference: wasm` above.
wasm-inputs-check: wasm
	@$(MAKE) --no-print-directory -f renderer.mk wasm-inputs-verify

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
# vanilla≡stock + the state-contract lanes, VERIFY every card's raw-framebuffer
# hash against the committed goldens/manifest-*.edn, and then (re)write those
# manifests. Exit code is the verdict.
#
# THE VERIFY HALF IS WHY THIS LANE CAN FAIL ON PIXELS AT ALL. It used to only
# re-mint: corrupting a committed sha256 exited 0 and silently overwrote the
# corruption back, and a real theme-colour shift moved 243 of 468 hashes with
# `check-renderer` still printing GREEN — no lane in the battery compared a
# rendered pixel to any committed reference. Both are now measured red, the
# second with 243 findings all tagged `:gate :golden`. The mint still happens
# after the comparison, so a red run leaves the corrected manifests in the tree
# to review and commit — the same regenerate-then-diff shape `manifests` and
# `generated-projection` use.
#
# CI STILL DIFFS, AND STILL HAS TO. This lane sees the GOLDENS; the JPEG
# gallery and generated doc pages come from the `gallery` mode, which
# check-renderer does not run, so `git diff --exit-code tools/devcards/goldens
# tools/devcards/docs` remains the thing that catches changed contact-sheet
# CONTENT. The disk audit below catches an unchanged retired path — and now
# PRUNES it, so the retirement reaches that diff as a deletion instead of
# sitting outside it. The prune refuses unless the run wrote every declared
# page, so an incomplete run deletes nothing.
# `manifests`: the composition path resolves colour tokens through
# devcards.tokens, which READS output/manifests/design-tokens.json at a fixed
# relative path and throws if it is absent. The manifests lane installs that
# file with a non-atomic `cp`, so this is a declared read-after-write edge, not
# a convenience — see the matrix lane below for the full reasoning.
fixtures: wasm bindings composition-clean manifests
	cd tools/devcards && clojure -M:bindings:run generate

# Render-level dump-contract canaries: real protobuf cards through the freshly
# built wasm and the public host. One exercises OVERFLOW_VISIBLE reachability;
# one genuinely overruns TREE_BUF_SIZE and must become :dump-truncated before
# JSON parsing. They are separate from the corpus because each deliberately
# constructs the violation whose finding proves the clause is alive.
dump-contracts: wasm bindings
	cd tools/devcards && clojure -M:bindings:dump-contract-probe

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

fixtures-prebuilt: wasm-present bindings composition-clean
	cd tools/devcards && clojure -M:bindings:run generate

# out/composition is ignored scratch, but the wasmtime interaction harness
# discovers its roster with read_dir. Rebuild it from empty before every
# generate arm so a retired local slug cannot change the verdict or harness
# population for one developer while a fresh CI checkout stays green.
composition-clean:
	rm -rf tools/devcards/out/composition

# Gallery + generated doc pages against the prebuilt wasm — CI diffs
# tools/devcards/docs afterwards (a pixel-shifting renderer change must
# re-mint the gallery in the same change, exactly like the goldens).
gallery-prebuilt: wasm-present bindings
	cd tools/devcards && clojure -M:bindings:run gallery

# Four mutation-sensitive DISABLED dead-zone cards through the real authored
# fixture builder and real wasm. Kept outside the golden corpus because the
# positive ARM-1 card necessarily fires the already-armed, order-free overlap
# rule; making that expected overlap green would require the per-card
# exemptions/rule special-casing the standard forbids.
#
# TWO ENTRY POINTS, EXACTLY AS interaction / interaction-prebuilt BELOW, and for
# the identical reason. The battery had only the *-prebuilt form, whose sole
# wasm constraint is the `wasm-present` GUARD — it asserts controls.wasm exists
# and cannot cause it to. Serially that was invisible: check-renderer lists
# `wasm` earlier, so the artifact was always there by the time the guard looked.
# Under -j make may start this lane immediately, and the guard then fires on a
# battery that is perfectly well-formed — a FATAL that is a scheduling artifact,
# arriving one run in some, which is the nondeterministic red the whole exercise
# exists to prevent. The battery now takes `deadzone-canary`, which BUILDS what
# it reads; CI keeps the prebuilt form, which consumes the uploaded artifact on
# a runner with no WASI-SDK.
#
# The guard stays in both — the edge makes make build the wasm, the guard catches
# it being absent for any other reason — and the recipe stays in ONE home for the
# reason `interaction-suite` records: a shared third target would be a sibling
# prerequisite, and sibling prerequisites carry no ordering. $(1) is the target
# the CALLER can actually run, so a WASI-less host is never told to run the
# WASI-only one.
define deadzone-canary-suite
@test -f $(R)/output/controls.wasm || { \
	echo "FATAL: $(R)/output/controls.wasm missing — run 'make -f renderer.mk $(1)' first" >&2; \
	exit 1; }
cd tools/devcards && clojure -M:bindings:deadzone-canary
endef

deadzone-canary: wasm bindings
	$(call deadzone-canary-suite,deadzone-canary)

deadzone-canary-prebuilt: wasm-present bindings
	$(call deadzone-canary-suite,deadzone-canary-prebuilt)

# The OVERLAP rule's real-render canary — four planted cases (siblings,
# click_area, nesting, host_proxy) built through fixtures/build-authored-card
# and rendered on the real wasm, judged with devcards.lanes' own table and
# threshold.
#
# WHY IT IS NOT A CORPUS CARD, same reason the dead-zone canary is not: three
# of the four cases are DESIGNED to fire the armed overlap lane, so parking
# them in the golden corpus would need per-card exemptions — the ratchet the
# standard forbids. Kept outside, they can assert a firing rule directly.
#
# It DOES NOT DUPLICATE `dump-contracts`. That probe's overflow-visible half
# already renders an overlap pair whose verdict turns on `descend_gate`; this
# one covers the keys it does not touch — `click_area`, `clickable`, and the
# proxy declaration triple — plus the ancestry exclusion.
#
# TWO ENTRY POINTS for the reason `deadzone-canary-suite` above records in
# full: the battery takes `overlap-canary`, which BUILDS the wasm it reads, so
# `-j` cannot start it before the artifact exists; CI takes the prebuilt form,
# which consumes the uploaded artifact on a runner with no WASI-SDK. The guard
# stays in both, and $(1) names the target the CALLER can actually run.
## scratchcard-lane: the scratch-devcard pipeline against the real wasm.
#
# GATES FIVE ASSERTIONS, and the last is why the other four mean anything: the
# run covered the DEFAULT matrix (never merely a non-empty one), every cell
# rendered, the shipped example is CLEAN across that whole matrix, vanilla ==
# stock bit-identically, and a PLANTED defect is CAUGHT. Without that last one a
# clean run would prove only that the lanes are quiet, not that they are awake.
# They sit in three `clause-` fns rather than five, which is why a count taken
# from the function list and one taken from the output disagree.
#
# The lane exits 3 for CANNOT RUN and 1 for a real verdict, so a red says which
# it was. NOTE the guard below is the ordinary make-level wasm-present check
# copied from the canary lanes and exits 1; the lane's own exit-3 path covers a
# wasm that vanishes after the guard passes.
#
# The scratchcard DAEMON is deliberately NOT exercised here: it drives docker,
# which this image does not carry. That half is host-only, like go-leg-repro.
define scratchcard-lane-suite
@test -f $(R)/output/controls.wasm || { 	echo "FATAL: $(R)/output/controls.wasm missing — run 'make -f renderer.mk $(1)' first" >&2; 	exit 1; }
cd tools/scratchcard && clojure -M:render-lane
endef

.PHONY: scratchcard-lane scratchcard-lane-prebuilt
scratchcard-lane: wasm bindings proto-classes
	$(call scratchcard-lane-suite,scratchcard-lane)

scratchcard-lane-prebuilt: wasm-present bindings proto-classes
	$(call scratchcard-lane-suite,scratchcard-lane-prebuilt)

## scratchcard-e2e: the daemon and its socket, end to end. HOST-ONLY.
#
# DELIBERATELY NOT IN `check-renderer-lanes`, and the exclusion is recorded
# HERE — beside the aggregate it is excluded from — rather than left for a
# reader to infer coverage this battery does not have. It drives `docker`
# directly and the toolchain image carries no docker CLI, which is the same
# capability fact that makes `go-leg-repro` host-only.
#
# It is also in no CI workflow, for the same reason: a runner would need
# docker-in-docker. What CI DOES cover is `scratchcard-lane`, the render half.
# The transport half is proven here, by hand, on a host.
.PHONY: scratchcard-e2e
scratchcard-e2e:
	@bash tools/scratchcard/test/daemon_e2e.sh

## scratchcard-e2e-canary: proves each of scratchcard-e2e's EXIT-STATUS arms
## fails for ITS OWN reason. HOST-ONLY (it runs that suite, which drives
## docker), and excluded from check-renderer-lanes for that reason — recorded
## here, beside the target, rather than left for a reader to infer coverage the
## battery does not have. Same shape and same exclusion as
## scratchcard-lane-canary below.
.PHONY: scratchcard-e2e-canary
scratchcard-e2e-canary:
	@bash tools/scratchcard/test/e2e_canary.sh

## scratchcard-lane-canary: proves each scratchcard-lane clause fails for ITS
## OWN reason. HOST-ONLY (it drives tools/uber.sh, which drives docker), and
## excluded from check-renderer-lanes for that reason — recorded here, beside
## the target, rather than left for a reader to infer.
SCRATCHCARD_BRIEF := .claude/rules/scratch-devcard-api.md

## scratchcard-brief-generate: rewrite the generated API rule from live code.
## GENERATOR ONLY — always exits 0. The freshness GATE is the target below.
.PHONY: scratchcard-brief-generate
scratchcard-brief-generate: proto-classes
	cd tools/scratchcard && clojure -M:brief "$$(pwd)/../.."

## scratchcard-brief: the freshness gate. Regenerates, then refuses a diff.
#
# Modelled on `standard-brief`, including its two guards, because both failure
# modes are silent: git cannot resolve a container-mounted checkout (so
# freshness would be UNKNOWN rather than fresh), and `git diff` says NOTHING
# about an untracked path (so the check would pass green over a page nothing
# has ever received).
.PHONY: scratchcard-brief
scratchcard-brief: scratchcard-brief-generate
	@git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { 		echo "FATAL: git cannot resolve this checkout, so freshness is UNKNOWN, not" >&2; 		echo "  fresh. Run this target on the host or a CI runner." >&2; 		exit 1; }
	@git ls-files --error-unmatch $(SCRATCHCARD_BRIEF) >/dev/null 2>&1 || { 		echo "FATAL: $(SCRATCHCARD_BRIEF) is NOT TRACKED — git diff says nothing about" >&2; 		echo "  an untracked path, so this check would pass green over a page nothing" >&2; 		echo "  has received. Add it: git add $(SCRATCHCARD_BRIEF)" >&2; 		exit 1; }
	@git diff --exit-code HEAD -- $(SCRATCHCARD_BRIEF) || { 		echo "FATAL: $(SCRATCHCARD_BRIEF) was STALE vs a fresh generation — regenerated" >&2; 		echo "  in place; review the diff above and commit it. An author loads this" >&2; 		echo "  page as the tool's API, so a stale one documents a tool that moved." >&2; 		exit 1; }
	@echo "scratchcard-brief: fresh ($(SCRATCHCARD_BRIEF) vs the live ops, error codes, manifest schema, armed lanes and default matrix)"

.PHONY: scratchcard-lane-canary
scratchcard-lane-canary:
	@bash tools/scratchcard/test/lane_canary.sh

define overlap-canary-suite
@test -f $(R)/output/controls.wasm || { \
	echo "FATAL: $(R)/output/controls.wasm missing — run 'make -f renderer.mk $(1)' first" >&2; \
	exit 1; }
cd tools/devcards && clojure -M:bindings:overlap-canary
endef

overlap-canary: wasm bindings
	$(call overlap-canary-suite,overlap-canary)

overlap-canary-prebuilt: wasm-present bindings
	$(call overlap-canary-suite,overlap-canary-prebuilt)
# ── Two-way disk reconciliation over the generated trees ────────────────────
# No doc/golden emitter here has a DELETION path — they only ever write. So
# retire a widget, kitchen sink or lego and the generator simply stops
# emitting that unit's files, which stay on disk, TRACKED and byte-unchanged;
# the freshness step above reports nothing about them because nothing
# modified them, and once the author commits the re-mint every later run is
# green with the orphans still shipping to every consumer that clones this
# history. Measured before the audit existed: retiring one composition card
# left three tracked JPEGs behind and `git diff --exit-code` exited 0.
#
# It is NOT a standalone pass, and cannot be. The audit's CLAIMED set has to
# be the emitter's own report of the paths it just wrote — a second listing
# of the naming scheme would go stale in exactly the way the audit exists to
# catch. So the reconciliation rides the two arms that do the writing, and
# this target is the name for running both AGAINST AN ALREADY-BUILT
# renderer/output/controls.wasm: `generate` reconciles goldens/ and the
# freshly-cleaned out/composition (the roster the wasmtime harness DISCOVERS
# by read_dir), `gallery` reconciles docs/. Each fails its own arm non-zero.
doc-audit: fixtures-prebuilt gallery-prebuilt
	@echo "doc-audit: goldens/, out/composition/ and docs/ each reconciled against what its generator claimed — see the audit lines above"

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
# COUNT THE CROSS-ENGINE COMPARATOR; DO NOT COUNT THE FOUR POINTER TESTS AS
# INDEPENDENT COVERAGE. The binary holds five tests, and they are not the same
# kind of thing:
#
#   composition_cross_engine_fb  — every discovered card × dark/light, wasmtime's
#     raw framebuffer asserted byte-identical to the GraalWasm dump. This is the
#     ONLY automated cross-engine check anywhere in the repo. Nothing else can
#     see an engine divergence at all.
#   scrubber_press_seek_identity / scrubber_press_drag_stream /
#   scrubber_ext_click_envelope / dock_fold_identity — the wasmtime MIRROR of
#     devcards.interaction, which drives the same cards and the same taps on
#     GraalWasm and reports press-seek, drag, ext-click and dock findings from
#     `run-lane`, PLUS scrubber geometry/palette, long EventBinding.name,
#     proxy-content inertness and handle hit-clearance. Strictly wider.
#
# So for a defect in the SHARED renderer source these four are dominated twice
# over. They are dominated in BREADTH by the lane above, and they are dominated
# in ORDER by make: `fixtures` (`fixtures-prebuilt`) is a DECLARED prerequisite
# of `interaction` (`interaction-prebuilt`), so the Clojure lane's red aborts the
# invocation and cargo never compiles. A mutation of renderer/src therefore
# cannot be killed by these four first, whatever the battery log suggests.
#
# WHAT THEY DO COVER, stated precisely so the retraction does not overshoot into
# the opposite falsehood: agreement of the two production-host ENGINES on the
# pointer path. The framebuffer comparator renders and compares; it feeds no
# pointer input, so a wasmtime-vs-GraalWasm divergence in event handling is
# invisible to it and to every other lane. That axis is also exactly why mutation
# testing reports these four as redundant and always will — a source mutation
# perturbs both engines identically, so no edit to this repo can produce the
# divergence they exist to catch. Read a mutation score here as measuring the
# wrong axis, not as a licence to delete them.
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
#
# `construct-bindings` and `manifests` are declared for the same reason on two
# more inputs, and this lane is the one place they had to be said OUT LOUD: every
# other rgen consumer (harness / matrix / demo-parity) lists `wasm`, which now
# carries both transitively, and this one does not. The recipe reads
# output/manifests/design-tokens.json ON ITS OWN COMMAND LINE — `manifests`
# installs it — and its emitter loads `lvgl-codegen.morph-fixtures`, whose
# closure requires `lvgl-codegen.generated.enums`, the Clojure source
# `construct-bindings` writes. Its own consumers (`reload`, `morph-parity`) do
# list `wasm`, but SIBLING PREREQUISITES CARRY NO ORDERING: make is free to start
# morph-fixtures before wasm's prerequisites have finished.
morph-fixtures: proto-classes construct-bindings manifests
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

# ── wire-constraints ────────────────────────────────────────────────────────
# The BEHAVIOURAL half of output/manifests/ui-ast-constraints.json. That
# manifest records, per buf.validate constraint, whether the C leg upholds it;
# `manifests` verifies the manifest's ANCHORS (a named guard token, a named
# test) still exist, which proves neither has been deleted and proves nothing
# about either firing. This lane is what proves the firing.
#
# A SEPARATE BINARY FROM decode-limits, deliberately. That suite's subject is
# RESOURCE limits — depth, fan-out, accumulation across patches — reached by
# trees no authored screen produces. This one's subject is VALUE constraints the
# proto declares and `scripts/proto_cleanup.awk` deletes before nanopb ever sees
# them. Both build synthetic inputs and both assert refusal, which is why merging
# them is tempting; the two answer different questions and their docstrings are
# what a reader consults to know which lane owns a gap.
#
# A NEW cargo test binary is not auto-run by the named-test lanes, so — as with
# decode-limits — it is wired here explicitly, and in .github/workflows/renderer.yml
# beside it.
wire-constraints: wasm
	cd $(R)/wasm_harness && PATH=$$HOME/.cargo/bin:$$PATH \
		cargo test --test wire_constraints

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
#
# `manifests` is a prerequisite for the SAME CLASS OF REASON as the wasm note
# above, on a different artifact. That lane installs output/manifests/
# design-tokens.json with `cp` — truncate-then-write, NOT atomic — and run.sh
# reads it (`--tokens ../../output/manifests/design-tokens.json`). Serially this
# is safe only because check-renderer happens to list `manifests` before this
# lane; that is an accident of LIST POSITION, not a declared constraint, and -j
# discards it. Declared here so the ordering survives parallelism.
matrix: proto-classes wasm reference manifests
	cd $(R) && bash coverage_matrix/run.sh

# Demo-parity capstone: lv_demo_widgets rendered both ways, BIT-EQUAL per tab.
# `manifests` for the same reason as matrix above — demo-parity.sh reads the
# same design-tokens.json.
demo-parity: proto-classes wasm reference manifests
	cd $(R) && bash tools/demo-parity.sh

# ── Manifest freshness ──────────────────────────────────────────────────────
# The ratified projections protogen publishes from its design/caps sources —
# the four manifests (tokens.edn / renderer.c's caps mirror /
# ui_ast.options' wire bounds / the compiled font tables' metrics) plus the
# native theme's generated/theme_tokens.h (tokens.edn's C projection,
# src/theme.c's input) — are emitted from their homes, but nothing regenerated
# or diffed them, so a WRONG projection reddens the oracles while a STALE one
# (the source moved, the projection was not re-emitted) would pass green forever.
# Regenerate each and fail if the committed copy is not byte-identical to a
# fresh emit; a red run leaves the corrected copies in the tree to commit (the
# same regenerate-then-diff shape lint.mk uses). No wasm / proto-classes
# needed — the emitters read tokens.edn and the renderer.c source directly.
# Emit to a temp dir and cmp against the committed copies — NOT `git diff`: this
# runs inside the Dockerfile.base container where protogen is a submodule and its
# .git is not resolvable. A stale projection is regenerated in place (review + commit).
#
# THE VERDICT IS PRINTED BEFORE THE HEAL IS ATTEMPTED, for the reason
# `generated-projection` records at length: renderer.yml runs THIS step on a
# READ-ONLY mount too, so `install_atomic` cannot write and the reader used to be
# handed `mktemp: … Read-only file system` plus "installing … failed" — two
# sentences about a write, over a manifest whose actual defect was staleness.
# Measured by planting a one-byte drift in output/manifests/design-tokens.json
# and running this target through renderer.yml's own `:ro` invocation.
#
# The install failure also no longer ABORTS the loop. It used to `exit 1` on the
# spot, so a read-only run named the first drifted manifest and never compared
# the other three; now every pair is judged and every verdict is printed.
#
# THE 1/3 EXIT SPLIT IS DELIBERATELY *NOT* EXTENDED HERE. This target's
# preconditions (a failed emit) already exit 1, and nothing calls it expecting to
# tell a verdict from a precondition — `generated-projection` has that caller and
# this does not. Stated rather than left as an oversight: adopting the split here
# is a separate change owing its own canary cases.
manifests:
	@tmp="$$(mktemp -d)"; \
	( cd $(RGEN) \
	  && clojure -M -m renderer-gen.design-tokens-json \
	       --tokens edn/tokens.edn --output "$$tmp/design-tokens.json" \
	  && clojure -M -m renderer-gen.renderer-caps-json \
	       --renderer "../../$(R)/src/renderer.c" --output "$$tmp/renderer-caps.json" \
	  && clojure -M -m renderer-gen.ui-ast-bounds-json \
	       --options ../../proto/ui/ui_ast.options --output "$$tmp/ui-ast-bounds.json" \
	  && clojure -M -m renderer-gen.ui-ast-constraints-json \
	       --proto ../../proto/ui/ui_ast.proto \
	       --options ../../proto/ui/ui_ast.options \
	       --dispositions edn/ui-ast-constraint-dispositions.edn \
	       --renderer ../../$(R)/src/renderer.c \
	       --tests ../../$(R)/wasm_harness/tests/wire_constraints.rs \
	       --output "$$tmp/ui-ast-constraints.json" \
	  && clojure -M -m lvgl-codegen.theme-tokens \
	       --tokens edn/tokens.edn --output "$$tmp/theme_tokens.h" \
	  && clojure -M -m lvgl-codegen.gesture-thresholds \
	       --tokens edn/gesture-thresholds.edn --output "$$tmp/gesture_thresholds.h" \
	  && clojure -M -m lvgl-codegen.font-metrics \
	       --tokens edn/tokens.edn --output "$$tmp/font-metrics.json" ) \
	  || { rm -rf "$$tmp"; echo "FATAL: manifest emit failed" >&2; exit 1; }; \
	$(INSTALL_ATOMIC) \
	rc=0; \
	for pair in \
	  "design-tokens.json:output/manifests" \
	  "renderer-caps.json:output/manifests" \
	  "ui-ast-bounds.json:output/manifests" \
	  "ui-ast-constraints.json:output/manifests" \
	  "font-metrics.json:output/manifests" \
	  "theme_tokens.h:$(R)/generated" \
	  "gesture_thresholds.h:$(R)/generated"; do \
	  f="$${pair%%:*}"; d="$${pair##*:}"; \
	  if ! cmp -s "$$d/$$f" "$$tmp/$$f"; then \
	    echo "FATAL: $$d/$$f is STALE vs a fresh emit." >&2; \
	    if install_atomic "$$tmp/$$f" "$$d/$$f"; then \
	      echo "  Regenerated in place; review and commit it." >&2; \
	    else \
	      echo "  It could NOT be regenerated here, because this tree is not writable —" >&2; \
	      echo "  a read-only CI mount does exactly this. THE VERDICT IS UNCHANGED: the" >&2; \
	      echo "  manifest is stale, not the mount broken. Re-run this target where the" >&2; \
	      echo "  tree is writable and commit what it rewrites." >&2; \
	    fi; \
	    rc=1; \
	  fi; \
	done; \
	rm -rf "$$tmp"; \
	$(MAKE) --no-print-directory -f renderer.mk manifests-proto-db || rc=1; \
	[ "$$rc" -eq 0 ] && echo "manifests: fresh (design-tokens + renderer-caps + ui-ast-bounds + ui-ast-constraints + font-metrics + theme-tokens.h + gesture-thresholds.h + the proto-db manifests enumerated above)"; \
	exit "$$rc"

# The proto-db-derived manifests (endpoints / signals / sub-signals /
# reverse-index.json) share the output/manifests/ directory with the three above
# but NOT their producer: they come from docs/.protodoc/tools reading
# proto-db.edn, via the Makefile's `docs-manifests` target, and had no freshness
# comparison at all. That gap is not hypothetical — signals.json kept publishing
# an altitude bound for ~150 commits after the proto dropped it, and was
# corrected only as an incidental side effect of an unrelated docs regeneration.
#
# THE COMPARED SET IS DISCOVERED FROM THE EMIT, NOT HAND-LISTED, and that is the
# whole repair. This loop was a literal `for f in signals.json sub-signals.json
# endpoints.json` while `manifest.clj`'s writer emitted FOUR files — so
# reverse-index.json, at 163 KB the LARGEST tracked manifest, was published with
# nothing checking it. Measured: corrupting the committed copy left this target
# at exit 0 while the parent printed a "fresh" line over it, and a real emitter
# regression in `build-reverse-index` changed only that file and passed both this
# gate AND the full protodoc suite (reverse-index-test asserts shape and never
# reads :related-signal-docs). A hand-kept list beside a producer is free to go
# short exactly once and then stays short; discovering it from the emit means a
# fifth manifest is covered the day it is written.
#
# WHICH IS WHY THE NON-VACUITY FLOOR IS NOT CEREMONY: a discovered set can also
# go EMPTY (a failed emit that still exits 0, an output-dir flag that moved), and
# a zero-file loop is a green tick over zero coverage. The floor is owed by
# DISCOVERY, not by every loop — `generated-projection` below needs none because
# every name it walks is a literal in this file. protogen publishes four, so
# fewer than four means DISCOVERY broke, not that there is nothing to compare.
# More than four is fine and needs no edit here.
#
# The PASS MESSAGE ENUMERATES what it compared, for the same reason: the old one
# said "proto-db trio fresh" over a directory holding four published manifests —
# a pass message implying more than the measurement could see.
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
	emitted="$$(cd "$$tmp" && ls *.json 2>/dev/null)"; \
	n=$$(printf '%s\n' $$emitted | grep -c . || true); \
	if [ "$$n" -lt 4 ]; then \
	  echo "FATAL: the proto-db emit produced $$n manifest(s) — protogen publishes" >&2; \
	  echo "  FOUR (endpoints, signals, sub-signals, reverse-index). Fewer means" >&2; \
	  echo "  DISCOVERY broke, not that there is nothing to compare; a loop over an" >&2; \
	  echo "  empty set is a green tick over zero coverage." >&2; \
	  echo "  emitted: $$(printf '%s ' $$emitted)" >&2; \
	  rm -rf "$$tmp"; exit 1; \
	fi; \
	rc=0; \
	for f in $$emitted; do \
	  if [ ! -f "output/manifests/$$f" ]; then \
	    echo "FATAL: the emitter writes $$f but output/manifests/$$f does NOT EXIST —" >&2; \
	    echo "  a published manifest that has never been committed. Run" >&2; \
	    echo "  'make docs-manifests' and git add it." >&2; \
	    rc=1; continue; \
	  fi; \
	  sed -e 's/"generated-at":"[^"]*"/"generated-at":"SENTINEL"/' \
	      -e 's/"protogen-commit":"[^"]*"/"protogen-commit":"SENTINEL"/' \
	      "output/manifests/$$f" >"$$tmp/committed-$$f"; \
	  if ! cmp -s "$$tmp/committed-$$f" "$$tmp/$$f"; then \
	    echo "FATAL: output/manifests/$$f is STALE vs proto-db.edn — run 'make docs-manifests' and commit it." >&2; \
	    rc=1; \
	  fi; \
	done; \
	rm -rf "$$tmp"; \
	[ "$$rc" -eq 0 ] && echo "manifests: proto-db fresh ($$n compared: $$(printf '%s ' $$emitted))"; \
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
# sub-second cmp loop with nothing to build. That is why it runs at the front of
# the battery — a stale binding makes every downstream result meaningless, so it
# has to fail before anything is compiled. It sits SECOND rather than first only
# because graal-check's primacy is itself documented and load-bearing; both
# report in well under a second, so which of the two speaks first is immaterial,
# and demoting a stated invariant to win a tie is not.
#
# WHAT IS AND IS NOT PROJECTED. Everything renderer/generated tracks is covered
# here except three files, deliberately:
#   theme_tokens.h, gesture_thresholds.h — emitted and cmp'd by `manifests`
#     above. A second gate over them would be a second home for one fact.
#   ui_luts.h — emitted and cmp'd by `construct-bindings` below, from the
#     vendored LVGL headers rather than from output/c. Same reason as the
#     pair above: covered elsewhere, so not a second home here.
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
#   3. It is a strict SUBSET — output/c tracks the whole binding surface and
#      only a handful of it is PROJECTED here, of which wasm.mk compiles the .c
#      (the 3 nanopb runtime files + jon_shared_data_types + the 2 ui
#      bindings); the rest are the headers those include. A blanket copy would
#      pour jon_shared_cmd*, jon_shared_data_*, opaque/** and ui_nodes into the
#      -Igenerated namespace. The lists below ARE the projection; nothing
#      crosses.
#   4. There are NO --delete semantics. A mirroring copy would remove the two
#      manifest-owned headers and ui_luts.h, none of which exist in output/c at
#      all. Nor does anything here infer a deletion in the REVERSE direction:
#      every projected name is LISTED below, so a projection that should go away
#      goes away by an edit to that list plus a `git rm` — a deliberate act
#      visible in the diff, never something this gate discovers on its own.
#   5. FILE MODES. renderer/generated is mixed (644/755/symlink) where output/c
#      is uniformly 755, and cmp compares CONTENT — it cannot see a mode flip,
#      so `cp -p` / `install` / `rsync -a` would silently rewrite every
#      projected mode and stay green. Five in-scope destinations are tracked
#      644 against that 755 source (LICENSE.nanopb and the four ui bindings).
#      Plain `cp` preserves the destination inode's mode (measured in the
#      pinned image: a 755 source over a 644 destination leaves 644), and the
#      recipe captures and restores it anyway — but ONLY AN EXISTING
#      DESTINATION HAS A MODE TO PRESERVE. A MISSING one would be created at
#      the source's 755, and the operator told to commit a 644->755 flip.
#      So absence is not treated as staleness at all: every projected name is
#      in a FIXED list, so a missing destination is a tracked-file DELETION and
#      hard-fails untouched rather than being recreated at whatever mode cp
#      leaves after the umask.
#
# NON-VACUITY IS STRUCTURAL HERE, which is why no guard asserts it. Every name
# below is a literal in this file, so the loops cannot go empty the way a
# `$(wildcard …)` can — the failure mode a discovered set has (an empty
# expansion reading as "nothing to project", a green tick over zero coverage)
# needs a floor precisely because discovery can break silently. A hand-listed
# set cannot: shrinking it is an edit to this file, in the diff, reviewed.
#
# THE VERDICT IS PRINTED BEFORE THE HEAL IS ATTEMPTED, and that ordering is the
# whole of what this lane says out loud. Staleness is decided by `cmp` alone; the
# in-place rewrite is a CONVENIENCE for whoever is standing in a writable tree.
# Reporting the verdict only if the convenience succeeded is how this lane spent
# its first CI failure describing the wrong defect: renderer.yml mounts the
# workspace READ-ONLY, so `install_atomic`'s `mktemp` died first and the entire
# output was
#
#   mktemp: failed to create file via template
#     'renderer/generated/.protogen-install.XXXXXX': Read-only file system
#
# — a message about a MOUNT, over a tree whose actual defect was a binding that
# had not been refreshed. A reader who trusts it diagnoses the mount, and one
# did. The same inversion is recorded a few steps away in renderer.yml, where a
# `:ro` mount made a missing write surface "read like a permissions bug"; this is
# that hazard reached through a gate's own reporting rather than through a
# workflow's mount choice.
#
# EXIT CODES SEPARATE A VERDICT FROM A PRECONDITION, per
# .claude/rules/gate-enforcement.md §2 — a suite (or a caller) that can only see
# "non-zero" accepts a broken harness as proof that a clause fired:
#
#   0  every projected pair is byte-identical
#   1  FAIL     — a real staleness verdict: a projected file drifted, or a
#                 flatten symlink is not a symlink. The tree is wrong.
#   3  CANNOT RUN — a precondition: a projection SOURCE is gone, or a projected
#                 DESTINATION was deleted. Nothing can be judged about that pair,
#                 and 3 DOMINATES 1 across the run, because a run that could not
#                 look at part of its population must not report the part it did
#                 look at as the whole answer.
#
# ITS CONSUMER IS THE CANARY, AND SAYING SO MATTERS more than it looks.
# tools/generated-projection-canary.sh asserts the exact code per clause, so a
# clause that started answering the wrong class would be caught — which is the
# whole reason gate-enforcement.md §2 asks for the split. What CANNOT read it is
# a caller reaching this lane through `make`: MAKE NORMALISES EVERY RECIPE
# FAILURE TO 2 and does not propagate the recipe's status, so the code survives
# only in make's own `*** [file:line: generated-projection] Error N` line. A
# caller that branched on make's exit expecting 3 would take the wrong branch
# every time, silently.
#
# So a caller that must tell "healed" from "cannot be healed" RE-RUNS the lane
# instead: this lane is idempotent on a tree it has just rewritten, so a green
# second run means the rewrite WAS the fix.
# `.github/workflows/build-and-release.yml` does exactly that, and its step
# records why.
GENERATED_DIR := $(R)/generated

# The nanopb RUNTIME (copied verbatim from /opt/nanopb by generate-protos.sh)
# plus jon_shared_data_types, the shared scalar/enum binding every other
# jon_shared_* message type builds on. All live at output/c's root and keep
# their names.
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

# The roster, machine-readable, for tools/generated-projection-canary.sh.
#
# The canary builds its synthetic fixture from THIS output rather than from a
# hand-copied list, so a name added or removed above cannot leave the canary
# exercising a population the gate no longer has — and the canary floors all
# three lines at non-empty, which is what turns the pass line's counts into a
# checkable claim instead of two numbers nobody compares to anything.
.PHONY: generated-projection-roster
generated-projection-roster:
	@printf 'root %s\n' $(GENERATED_ROOT_FILES)
	@printf 'ui %s\n' $(GENERATED_UI_FILES)
	@printf 'link %s\n' $(GENERATED_UI_LINKS)

generated-projection:
	@rc=0; \
	worse() { case "$$1" in 3) rc=3;; *) [ "$$rc" -eq 3 ] || rc=1;; esac; }; \
	$(INSTALL_ATOMIC) \
	project() { \
	  src="$$1"; dst="$$2"; \
	  if [ ! -f "$$src" ]; then \
	    echo "CANNOT RUN: $$src is missing — renderer/generated projects it, so the" >&2; \
	    echo "  source of the projection is gone. Regenerate output/c." >&2; \
	    return 3; \
	  fi; \
	  cmp -s "$$src" "$$dst" && return 0; \
	  if [ ! -e "$$dst" ]; then \
	    echo "CANNOT RUN: $$dst is MISSING, not stale — its name is in a FIXED projection" >&2; \
	    echo "  list, so there is no mode to preserve and copying the 755 source in" >&2; \
	    echo "  would silently rewrite the tracked mode, which cmp cannot see (hazard" >&2; \
	    echo "  5). Restore a DELETED file with 'git checkout -- $$dst'; if you just" >&2; \
	    echo "  added the name to the list, create it with the mode you intend." >&2; \
	    return 3; \
	  fi; \
	  echo "FATAL: $$dst is STALE vs $$src." >&2; \
	  if install_atomic "$$src" "$$dst"; then \
	    echo "  Regenerated in place; review and commit it." >&2; \
	  else \
	    echo "  It could NOT be regenerated here, because this tree is not writable —" >&2; \
	    echo "  a read-only CI mount does exactly this. THE VERDICT IS UNCHANGED: the" >&2; \
	    echo "  projection is stale, not the mount broken. Re-run this target where the" >&2; \
	    echo "  tree is writable and commit what it rewrites." >&2; \
	  fi; \
	  return 1; \
	}; \
	for f in $(GENERATED_ROOT_FILES); do \
	  project "output/c/$$f" "$(GENERATED_DIR)/$$f" || worse "$$?"; \
	done; \
	for f in $(GENERATED_UI_FILES); do \
	  project "output/c/ui/$$f" "$(GENERATED_DIR)/$$f" || worse "$$?"; \
	done; \
	for l in $(GENERATED_UI_LINKS); do \
	  dst="$(GENERATED_DIR)/ui/$$l"; want="../$$l"; \
	  if [ -L "$$dst" ] && [ "$$(readlink "$$dst")" = "$$want" ]; then continue; fi; \
	  echo "FATAL: $$dst is not the symlink -> $$want (a recursive copy flattens it" >&2; \
	  echo "  into a regular file, and the flattened .pb.c then cannot resolve its own" >&2; \
	  echo "  quoted include)." >&2; \
	  if mkdir -p "$(GENERATED_DIR)/ui" && rm -f "$$dst" && ln -s "$$want" "$$dst"; then \
	    echo "  Recreated; review and commit it." >&2; \
	  else \
	    echo "  It could NOT be recreated here, because this tree is not writable. THE" >&2; \
	    echo "  VERDICT IS UNCHANGED: the flatten shim is wrong, not the mount broken." >&2; \
	  fi; \
	  worse 1; \
	done; \
	[ "$$rc" -eq 0 ] && echo "generated-projection: fresh ($(words $(GENERATED_ROOT_FILES) $(GENERATED_UI_FILES)) files + $(words $(GENERATED_UI_LINKS)) flatten symlinks vs output/c)"; \
	exit "$$rc"

# The canary for the lane above: a hermetic synthetic tree per case, one
# production clause broken at a time, the exact recipe exit code asserted, and a
# NAMED neighbour required to stay silent on the same mutant. It reads its
# population from `generated-projection-roster`, so it cannot drift from the
# lists it is meant to exercise.
#
# ORDERED BEFORE `generated-projection` wherever both run. A stale tree stops
# make at the first failing goal, so a canary listed second would be skipped on
# exactly the pushes where the lane's behaviour is in question — prove the gate
# can fail, then read its verdict.
#
# No toolchain: bash, make and coreutils. It writes only inside its own
# `mktemp -d`, so it runs unchanged under renderer.yml's read-only workspace
# mount and never touches the checkout.
.PHONY: generated-projection-canary
generated-projection-canary:
	@bash tools/generated-projection-canary.sh

# ── The LVGL enum factory: the OTHER projection into renderer/generated ─────
# `generated-projection` above covers what comes from output/c. These two come
# from the VENDORED LVGL HEADERS instead, via the construct factory:
#   src/lvgl_codegen/generated/enums.clj  — four namespaces require it
#   renderer/generated/ui_luts.h          — COMPILED INTO the renderer
#
# WHY THIS LANE IS NOT OPTIONAL POLISH. A whole set of enum typedefs is
# DIRECT-CAST (lvgl-codegen.construct.lift/direct-cast-typedefs is its home,
# deliberately not restated as a count here):
# renderer.c casts the proto int straight to the LVGL value, so the proto
# number must EQUAL the header value. LVGL is a re-syncable vendored port
# (renderer/lvgl/.ported-from.edn), and a bump that shifts one enumerator is a
# silently mis-mapped enum — the C compiler cannot catch it, because the
# generated header IS the declaration. Before this lane existed both files were
# committed projections with no producer and nothing asserting their freshness.
#
# THE EXTRACTOR NEEDS THREE THINGS THE DEFAULTS GET WRONG in this tree, which
# is why they are passed explicitly rather than left to the script:
#   LVGL_DIR/CONF_DIR — its defaults point at $REPO/lvgl, but the tree is
#     renderer/lvgl + renderer/lv_conf.h.
#   CLANG — the container's clang is the WASI one and is NOT on PATH. It is
#     used ONLY for the AST dump, which supplies enum GROUPING (typedef names);
#     a wasm32 target is fine for that. DERIVED FROM $WASI_SDK, never hardcoded:
#     this lane is a prerequisite of `wasm`, and CI's composite action runs that
#     on a PLAIN RUNNER where the SDK is unpacked under $HOME and /opt/wasi-sdk
#     does not exist. `wasm.mk` takes the same variable with the same default,
#     so container and runner agree without either restating a path.
#   CC=gcc — the VALUES come from a natively-compiled probe, and the C compiler
#     is the only correct oracle for them (sequential enumerators, `A | B`
#     bitmasks, macro-derived values). This must be the NATIVE compiler.
# Both files are emitted from ONE extraction because they are the same facts
# viewed twice; emitting either alone is how the two would drift apart.
construct-bindings:
	@tmp="$$(mktemp -d)"; \
	LVGL_DIR="$(CURDIR)/$(R)/lvgl" CONF_DIR="$(CURDIR)/$(R)" \
	CLANG="$${CLANG:-$${WASI_SDK:-/opt/wasi-sdk}/bin/clang}" CC="$${CC:-gcc}" \
	OUT_DIR="$$tmp/extract" \
	  $(RGEN)/tools/lvgl-extract/extract-lvgl-enums.sh >"$$tmp/lvgl-enums.edn" \
	  || { rm -rf "$$tmp"; echo "FATAL: LVGL enum extraction failed" >&2; exit 1; }; \
	: "COMPLETENESS is asserted in the factory, not here, and deliberately so."; \
	: "A shell test for an EMPTY table cannot work: the extractor's final awk"; \
	: "closes its map unconditionally, so a zero-record run still emits '}' —"; \
	: "non-empty by every shell test, and contentless. And emptiness is the"; \
	: "wrong question anyway; the failure that happens is a PARTIAL extraction"; \
	: "(an lv_conf.h feature flag off, an upstream header move). Only the"; \
	: "factory knows which typedefs it consumes, so lift/required-typedefs is"; \
	: "the one home of that fact and emitted-enums refuses there — before any"; \
	: "truncated projection can reach the cmp/cp below."; \
	( cd $(RGEN) && clojure -M -m lvgl-codegen.construct.factory \
	    --enums "$$tmp/lvgl-enums.edn" \
	    --bindings-out "$$tmp/enums.clj" \
	    --luts-out "$$tmp/ui_luts.h" ) \
	  || { rm -rf "$$tmp"; echo "FATAL: factory emit failed" >&2; exit 1; }; \
	$(INSTALL_ATOMIC) \
	rc=0; \
	for pair in \
	  "enums.clj:$(RGEN)/src/lvgl_codegen/generated/enums.clj" \
	  "ui_luts.h:$(R)/generated/ui_luts.h"; do \
	  f="$${pair%%:*}"; d="$${pair##*:}"; \
	  if ! cmp -s "$$d" "$$tmp/$$f"; then \
	    install_atomic "$$tmp/$$f" "$$d" || { rm -rf "$$tmp"; echo "FATAL: installing $$d failed" >&2; exit 1; }; \
	    echo "FATAL: $$d was STALE vs a fresh extraction — regenerated in place; review and commit it." >&2; \
	    rc=1; \
	  fi; \
	done; \
	rm -rf "$$tmp"; \
	[ "$$rc" -eq 0 ] && echo "construct-bindings: fresh (enums.clj + ui_luts.h vs a live LVGL extraction)"; \
	exit "$$rc"

# ── The conventions manifest projection: the CONSUMER-FACING export ─────────
# tools/devcards/conventions/ui-style-conventions.json is what a downstream
# producer vendors to spell lv_state_t, lv_part_t and lv_obj_flag_t — none of
# which travel on the wire as anything but raw ints. Its two homes are the
# authored EDN beside it and lvgl-codegen.generated.enums (which
# `construct-bindings` above holds to a live header extraction), so this lane
# asserts the committed JSON equals what those two produce TODAY.
#
# WHY IT EXISTS. The file called itself a generated projection with a stated
# determinism contract, and no target, workflow or test ran its generator — so
# for as long as it has been committed, nothing could have told you whether it
# still matched. It did not: the committed bytes and a fresh emit carried
# IDENTICAL data in a different layout, which is precisely the state a
# projection with no producer decays into. Same shape as `manifests`,
# `construct-bindings` and `generated-projection`: emit to a temp dir, cmp,
# rewrite in place on drift and go red.
#
# NO PREREQUISITE ON `construct-bindings`, deliberately, and the battery runs
# these concurrently. This lane reads the COMMITTED enums.clj; whether that file
# is itself fresh is the OTHER lane's subject, and it fails on its own. What
# makes the concurrency safe is $(INSTALL_ATOMIC): a rewrite there lands by
# rename, so a reader here sees the old inode or the new one, never a torn one.
#
# HOST-RUNNABLE. Pure Clojure over two committed text files — no wasm, no
# bindings, no proto classes.
CONVENTIONS_JSON := tools/devcards/conventions/ui-style-conventions.json

conventions-projection:
	@tmp="$$(mktemp -d)"; \
	( cd tools/devcards && clojure -M -m devcards.conventions "$$tmp/projection.json" ) \
	  || { rm -rf "$$tmp"; echo "FATAL: conventions projection emit failed" >&2; exit 1; }; \
	$(INSTALL_ATOMIC) \
	rc=0; \
	if ! cmp -s "$(CONVENTIONS_JSON)" "$$tmp/projection.json"; then \
	  install_atomic "$$tmp/projection.json" "$(CONVENTIONS_JSON)" \
	    || { rm -rf "$$tmp"; echo "FATAL: installing $(CONVENTIONS_JSON) failed" >&2; exit 1; }; \
	  echo "FATAL: $(CONVENTIONS_JSON) was STALE vs a fresh projection — regenerated in place; review and commit it." >&2; \
	  rc=1; \
	fi; \
	rm -rf "$$tmp"; \
	[ "$$rc" -eq 0 ] && echo "conventions-projection: fresh ($(CONVENTIONS_JSON) vs the authored EDN + the generated LVGL bindings)"; \
	exit "$$rc"

# ── Devcards unit suite ─────────────────────────────────────────────────────
# The pure-helper tests (tools/devcards/test — dump-tree reductions, image
# math). No wasm / proto-classes needed, so it runs early and fails cheap.
## scratchcard-test: the scratchcard pure unit suite (no wasm, no daemon).
#
# NEEDS `proto-classes`, and the omission was caught by the battery rather than
# by hand. tools/scratchcard reaches renderer-gen and devcards as :local/root
# deps, so it inherits renderer-gen's `target/proto-classes` path — the javac
# output of output/java PLUS pronto's Java sources. Without that edge this lane
# races the compile under `-j` and dies on `ClassNotFoundException:
# pronto.ProtoMap`. It passes by hand on any tree where an earlier lane already
# compiled them, which is exactly why a missing prerequisite here survives
# manual testing and only fails cold.
.PHONY: scratchcard-test
scratchcard-test: proto-classes
	cd tools/scratchcard && clojure -M:test

devcards-test:
	cd tools/devcards && clojure -M:test

# ── The conventions/corpus MIRROR gate ──────────────────────────────────────
# conventions/ui-render-conventions.edn's `:widget-states` and corpus/spec.edn's
# per-widget `:committed-states` are two authored copies of one fact —
# corpus/README.md calls them MIRRORS — and NOTHING compared them. Measured
# before this lane existed: three classes declared `[:default]` in the manifest
# while the corpus declared `[:default :disabled]` and carried five
# `:expect :distinct` disabled cards whose committed goldens differ from their
# baselines in both asgard families. `devcards.docs` publishes the MANIFEST's
# copy on every widget page and `devcards.conventions` projects it into the
# consumer-facing JSON export, so the understatement had reached both published
# surfaces and no gate could see it.
#
# WHY IT IS NOT A PROJECTION. Neither list is derivable from the other: the
# corpus copy rides beside the cards that prove it, the manifest copy beside the
# render protocol a consumer vendors. What is available is the EQUALITY, so the
# equality is what is asserted.
#
# EXIT CODES: 0 clean, 1 FINDINGS (a verdict about the tree), 3 CANNOT RUN (a
# missing/unreadable file, an EMPTY side, an untyped or duplicated corpus
# class). It prints the COUNT it compared in every colour, so a collapsed run is
# visible without reading the exit code.
#
# HOST-RUNNABLE. Pure Clojure over two committed text files — no wasm, no
# bindings, no proto classes — and its namespace requires nothing outside
# `clojure.*` on purpose, so a require cannot throw before -main and exit 1
# wearing the FINDINGS colour.
#
# WHERE ITS ARMING ACTUALLY LIVES, stated rather than assumed. THIS target is a
# battery lane and an operator entry point; no workflow runs `check-renderer`,
# so the target alone would be reachable only by a human typing it — unarmed,
# per .claude/rules/gate-enforcement.md §6. What carries it in CI is
# `devcards-test` above, which devcards.yml runs and which contains
# `devcards.state-mirror-test`: that suite drives the REAL verdict over the
# committed pair AND over a planted disagreement per clause. Both of this
# gate's inputs live under tools/devcards/**, which is that workflow's own
# trigger path, so the trigger covers what can break it. Arming this target in
# CI too is one step in .github/workflows/devcards.yml beside the
# `conventions-projection` step — the same slot and the same reasoning — and is
# left undone here only because that file is outside this change's scope.
state-mirror:
	cd tools/devcards && clojure -M -m devcards.state-mirror

# ── UI-standard review briefing freshness ───────────────────────────────────
# .claude/skills/ui-standard-review/STANDARD.md is GENERATED, by
# tools/devcards/src/devcards/standard_brief.clj, from the standard's canonical
# sources: docs/UI-QUALITY-CONTRACTS.md (embedded verbatim), the live
# classification table (devcards.lvgl-classes), and each finding-producer's
# declared :thresholds, read from code. It is what the batched screenshot-review
# agent loads INSTEAD of re-deriving the standard per element — so a standard
# change that does not reach it leaves that agent judging against a stale copy of
# this repo's own rules, which is the drift a second prose copy always ends in.
# The gate is what makes generating it worth more than writing it once.
#
# REGENERATE-THEN-DIFF, the same shape as the goldens/gallery freshness step
# (.github/workflows/renderer.yml): the recipe rewrites the page in place, then
# fails if the committed bytes moved, leaving the corrected file in the tree to
# review and commit.
#
# `git diff`, NOT the cmp-against-a-tempdir shape `manifests` uses, because this
# gate's home is the host/CI side where git resolves. That target's caveat is
# real — inside the Dockerfile.base container a submodule checkout's .git is not
# resolvable — but here it lands on the safe side: git then EXITS NON-ZERO and
# this target reds. A gate that cannot look must never report green.
#
# THE TRACKED-FILE GUARD IS NOT CEREMONY. `git diff` is silent about an
# UNTRACKED path, so without it the first run — the one where the page has been
# generated and never added — would pass green over a file no consumer has ever
# received: the precise silence where a broken run and a clean run print the
# same nothing.
#
# HEAD is diffed rather than the index, because this runs BEFORE a push and a
# staged-but-stale copy is exactly the state it exists to catch.
#
# DELIBERATELY NOT A check-renderer PREREQUISITE. renderer.yml `docker run`s the
# battery in Dockerfile.base without mounting .git, so wiring it there would red
# every containerised battery on a repo-shape problem rather than on a standard
# problem. It belongs beside the goldens/gallery freshness step — likewise a
# workflow step on the runner, not a battery lane.
STANDARD_BRIEF := .claude/skills/ui-standard-review/STANDARD.md

# THIS TARGET IS THE GENERATOR, NOT THE GATE, and the distinction is load-bearing
# because `check-renderer-lanes` lists THIS one and not `standard-brief`.
#
# `devcards.standard-brief/-main` calls `generate!` and prints the path and byte
# count. That is all it does — its own docstring says "the freshness comparison
# is the caller's". So it REWRITES $(STANDARD_BRIEF) and exits 0 whether the page
# was already fresh or wildly stale: a moved threshold, an edited contract, a
# changed classification row all leave this target green while it silently
# overwrites a tracked file. The ONLY thing that reds it is the generator
# throwing — a malformed producer roster, an unreadable contract, an empty
# classification table. That is real coverage and it is worth the battery slot;
# it is simply not freshness, and a reader who sees this name under the section
# header above and inside a gate's lane list will assume otherwise.
#
# THE HOLE THAT LEAVES IS LOCAL-ONLY, and that was checked rather than assumed.
# In CI the real gate is armed and complete: devcards.yml runs `standard-brief`
# (the git-diff half below), and every input the generator reads matches that
# workflow's `paths:` — the contract at docs/UI-QUALITY-CONTRACTS.md, the
# classification table and each producer's :thresholds under tools/devcards/**,
# and the generated page under .claude/skills/ui-standard-review/**. renderer.yml
# does not run either target at all, so nothing about the briefing is decomposed
# into it. What the local battery loses is only EARLINESS: `check-renderer` will
# not tell you the page drifted, it will just leave the corrected page sitting in
# your working tree for `git status` to report.
#
# WHY THE OBVIOUS REPAIR IS REFUSED. Swapping this lane for `standard-brief`
# would red every containerised battery on a repo-shape problem instead of a
# standard problem — under CI's RAW `docker run` git refuses this checkout as
# dubiously owned (see the scoping note below the recipe), and
# that target's own first guard turns "cannot look" into a hard failure, exactly
# as it should. The block below records that decision; this note records what it
# costs, which is the half that was missing.
standard-brief-generate:
	cd tools/devcards && clojure -M -m devcards.standard-brief

# The FRESHNESS half is a SEPARATE target and not a check-renderer lane. It
# shells out to git, and under a RAW `docker run` git refuses the mounted
# worktree as dubiously owned (`detected dubious ownership`), so a git-based
# battery lane fails every run there while looking like a real finding. That is
# how CI invokes the container, and CI is the authority. This mirrors how the
# goldens/gallery freshness check is armed: generation runs in the container,
# the git comparison runs on the runner afterwards
# (.github/workflows/renderer.yml "Golden manifest + gallery freshness").
#
# NOT AN ABSOLUTE, and CI IS NOT THE OBSTACLE — say so plainly, because the
# first draft of this note claimed it was and that claim is refuted three lines
# of YAML away. `tools/uber.sh` declares `safe.directory` for the workspace, and
# `.github/workflows/renderer.yml`'s shellcheck lane ALREADY passes the same
# GIT_CONFIG_* env to a raw `docker run`, with its own measurement recorded
# beside it. So the capability exists on both paths and is demonstrated.
#
# What keeps this out of `check-renderer` is therefore a DECISION and not a
# blocker: a freshness check compares the working tree against the index, which
# is a different kind of assertion from the rest of the battery, and arming it
# means adding the env to the battery's own docker invocations too. Recorded
# this way so the next author does not re-derive an obstacle that is not there.
standard-brief: standard-brief-generate
	@git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { \
		echo "FATAL: git cannot resolve this checkout, so freshness is UNKNOWN, not" >&2; \
		echo "  fresh. Run this target on the host (or a CI runner), never inside" >&2; \
		echo "  the toolchain container." >&2; \
		exit 1; }
	@git ls-files --error-unmatch $(STANDARD_BRIEF) >/dev/null 2>&1 || { \
		echo "FATAL: $(STANDARD_BRIEF) is NOT TRACKED — git diff says nothing about an" >&2; \
		echo "  untracked path, so the freshness check below would pass green over a" >&2; \
		echo "  briefing nothing has ever received. Add it: git add $(STANDARD_BRIEF)" >&2; \
		exit 1; }
	@git diff --exit-code HEAD -- $(STANDARD_BRIEF) || { \
		echo "FATAL: $(STANDARD_BRIEF) was STALE vs a fresh generation — regenerated in" >&2; \
		echo "  place; review the diff above and commit it. The review agent loads this" >&2; \
		echo "  page as the standard, so a stale one judges against rules that moved." >&2; \
		exit 1; }
	@echo "standard-brief: fresh ($(STANDARD_BRIEF) vs the contract + the live classification table + the declared thresholds)"

# ── UI-standard review PREFLIGHT ────────────────────────────────────────────
# The VLM review is mandatory and is NOT a gate, so its whole failure mode is a
# GREEN: an empty batch, or a batch of the PIN's own renders, reports exactly
# what a reviewed-and-sound surface reports. `preflight.sh` resolves the review's
# inputs up front and refuses with a distinct exit code per reason; the canary
# asserts all 11 of those arms.
#
# DELIBERATELY NOT A check-renderer PREREQUISITE, and the reason is semantic
# rather than mechanical — unlike `standard-brief`, which is kept out because git
# refuses this checkout under CI's raw `docker run`. These two need no git, no
# wasm and no network and would run fine there. They stay out because
# `check-renderer` IS the gate, and a gate whose green included "the review's
# inputs resolve" invites reading that green as evidence about the review — the
# precise over-claim §0 forbids. The preflight is run BY the reviewer, at the
# start of the review, and its result belongs in the review's report.
ui-review-preflight:
	@.claude/skills/ui-standard-review/preflight.sh

ui-review-preflight-canary:
	@tools/ui-review-preflight-canary.sh

# ── renderer-gen schema guard suite ─────────────────────────────────────────
# The lvgl_codegen schema guard tests (tools/renderer-gen/test): pure in-memory
# validate-screen-semantics checks — the leaf content-sizing guard that fails a
# childless leaf (lv_bar/lv_slider/lv_led) content-sized to a ~0px collapse. No
# wasm and no proto-classes: the suite is hermetic and builds its screens in
# memory, so it still runs early and fails cheap (18.1s median, of which the
# added prerequisite below is 11.5s).
#
# `construct-bindings` IS declared, and this is the lane where the rule "every
# consumer of a generated file names its producer" is least obvious and most
# worth keeping uniform. `lvgl-codegen.schema` requires
# `lvgl-codegen.generated.enums` — a Clojure SOURCE file this suite compiles at
# load time and `construct-bindings` rewrites. The two are siblings in the
# battery, so under -j the suite can be reading it while the factory writes it.
# The install is atomic now, so the surviving hazard is a STALE read rather than
# a torn one, and a stale read cannot manufacture a green: the factory reds the
# battery on any rewrite. The edge is here so the red names the clause anyway,
# and so `lvgl_parity_test`'s direct-cast assertions are known to have been made
# against the same enums.clj the rest of the run compiled.
#
# WHAT IS *NOT* DECLARED HERE, deliberately, and it is a live residual: this
# project's deps.edn puts `target/proto-classes` in its BASE `:paths`, so it is
# on the classpath of every alias including `:test` — while `proto-classes`
# (tools/compile-protos.sh) `rm -rf`s exactly that directory before javac. A
# concurrent battery therefore deletes and repopulates a classpath ROOT under
# this live JVM.
#
# It is unarmed TODAY, and the boundary is exact rather than reassuring, because
# it was measured by asking the JVM instead of grepping. Requiring every test
# namespace in this suite loads FOURTEEN project namespaces, and NO pronto
# namespace is among them. `pronto.ProtoMap` IS resolvable — the directory is on
# the classpath — so the safety here is that nothing the suite loads ever
# triggers that resolution, NOT that the class is out of reach.
#
# `lvgl-codegen.emit-proto` is now in that closure and is the first namespace in
# it to touch a protobuf class at all, so the question "has the suite started
# reaching into generated Java?" is live in a way it was not before. It has not:
# `com.google.protobuf.ByteString` resolves out of the protobuf-java JAR, never
# out of `target/proto-classes`, which is the directory the hazard is about.
#
# Re-derive both facts rather than trusting this paragraph — it is the kind that
# goes stale silently, and it already did once:
#   tools/uber.sh 'bash tools/renderer-gen/dev/wirelut_coverage_boundary.sh /workspace'
#
# Four namespaces in this project genuinely `:require` pronto; none is in the
# closure above. A require reaching any of them from this suite arms the hazard,
# and the failure would be `ClassNotFoundException: pronto.ProtoMap` at exactly
# the moment the javac window opens — the same symptom morph-fixtures already
# records. The edge is omitted rather than added because declaring it would make
# an 18s hermetic lane wait on a 32.5s javac for a dependency it does not have;
# if such a require appears, declare `proto-classes` here and delete this
# paragraph.
# proto-classes is load-bearing here and its absence was invisible locally: the
# suite needs pronto.ProtoMap, and `check-renderer` happens to build the classes
# earlier in its chain, so this target passed for as long as nothing invoked it
# alone. renderer.yml does exactly that, and got ClassNotFoundException.
clj-schema-test: proto-classes construct-bindings
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

# ── The battery runs its lanes CONCURRENTLY ─────────────────────────────────
# `check-renderer` is the documented entry (the CLAUDE.md charter, .claude/rules/
# renderer.md, tools/uber.sh) and stays the entry: it re-invokes this makefile
# with `-j` on `check-renderer-lanes`, which carries the lane list. Passing -j at
# the top level instead would have worked equally well and would have left every
# operator who types the documented command running serially, which is the whole
# point of arming it here.
#
# BATTERY_JOBS IS 4, NOT $(nproc), AND THAT IS DELIBERATE. Three lanes already
# parallelise INTERNALLY and none of them participates in make's jobserver:
# `wasm`/`reference` recurse with an explicit `-j$(nproc)` (an explicit -j in a
# sub-make overrides the inherited jobserver), coverage_matrix/run.sh dispatches
# `MATRIX_JOBS=$(nproc)` concurrent harness spawns, and every cargo lane runs its
# own job pool. A top-level -j$(nproc) multiplies against all three. 4 is a
# starting point, not a measured optimum — override it (`make -f renderer.mk
# BATTERY_JOBS=1 check-renderer` is the old serial behaviour, and the control arm
# of every measurement in this change).
#
# WHAT THE LANES CONTEND ON, since -j makes it matter and none of it is visible
# in the list: SEVEN lanes write renderer/wasm_harness/target. They do not
# corrupt each other — cargo takes its own locks — but the locks are PER PROFILE
# DIRECTORY, not one per target/, and that split is what decides who waits for
# whom. Verified on disk: target/{debug,release}/.cargo-{lock,build-lock,
# artifact-lock}. The five `cargo test` lanes (harness, interaction, reload,
# morph-parity, decode-limits) all build DEBUG and serialise against each other;
# the two `cargo build --release` calls inside coverage_matrix/run.sh and
# tools/demo-parity.sh serialise against each other in RELEASE; and the two
# groups do NOT contend. So `-j` buys real concurrency ACROSS the two groups and
# almost none within either, whatever BATTERY_JOBS says.
#
# The two oracle scripts also each re-invoke `make -f wasm.mk all reference`;
# that is a no-op only because `wasm` and `reference` are declared prerequisites
# of both and have therefore already finished.
BATTERY_JOBS ?= 4

## spec-coverage: THE JOIN — every ENROLLED m/=> spec is exercised by the suite
# Line coverage says a line ran; it cannot say a CONTRACT was checked. This asks the
# sharper question — for each function carrying an `m/=>`, was it CALLED, and did its
# contract hold — over a DECLARED SCOPE of namespaces, total inside it with ZERO
# tolerated misses.
#
# NOT A PERCENTAGE AND NOT A CEILING. A count of unexercised specs would be a list of
# individual findings wearing a number: each entry is one function with one unambiguous
# fix, so a committed total is parked findings, which gate-enforcement.md §1 refuses.
# Enrolment is the sanctioned shape, exactly as lint-docstrings is scoped.
#
# IT DRIVES ITS OWN RUN rather than hooking kaocha, and that is a correction:
# `:kaocha.hooks/post-run` was MEASURED NOT TO FIRE — a namespace enrolled at 0%
# exercised left the suite green, i.e. the gate silently checked nothing. post-load
# does fire, which is why the malli arming seam still uses it.
#
# SCOPE, stated so a green is not over-read: it sees what `clojure -M:test` sees. The
# E2E generator legs this makefile drives take exercised specs from 50 to 165 of 318,
# so a wider enrolment is possible but needs a driver that runs those legs too. The
# namespace's docstring carries both measurements.
spec-coverage: proto-classes
	cd $(RGEN) && clojure -M:spec-coverage

## dead-c-externs: no hand-authored C symbol is externally linked yet unreachable
# `-Wunused-function -Werror` already makes an unused `static` a hard build failure,
# so dead INTERNAL C cannot exist here. It says nothing about a symbol with EXTERNAL
# linkage: the linker must keep it, the compiler has no complaint, and no caller can
# reach it. clang-tidy cannot answer it either — it is PER TRANSLATION UNIT and cannot
# know whether another object uses the symbol.
#
# THE VERDICT IS AN INTERSECTION over the two link targets, which never link together
# (eight symbols are defined in BOTH renderer.c and reference_ui.c). Six symbols
# currently look dead in the reference link alone and are LIVE in controls; a union
# would report all six. The gate's header carries the argument.
#
# WHY IT LIVES HERE AND NOT IN `lint.mk lint`: it reads COMPILED OBJECTS and needs
# llvm-nm from the pinned WASI-SDK, so it cannot run on the plain lint runner. Its
# prerequisites are `wasm` and `reference` — both link targets' objects must exist, and
# reading only one would silently judge half the question.
.PHONY: dead-c-externs dead-c-externs-test
dead-c-externs: wasm reference
	@bash tools/lint/dead_c_externs.sh

# Its canaries ride the battery for the same reason every other canary suite does: a
# gate whose canaries are never RUN is a gate nobody has checked since it landed. They
# build their own synthetic two-object corpora, so they perturb no tracked file.
dead-c-externs-test:
	@bash tools/lint/test/dead_c_externs_test.sh

check-renderer:
	@$(MAKE) --no-print-directory -f renderer.mk -j$(BATTERY_JOBS) check-renderer-lanes

# ONE NAME IN THE LIST BELOW IS NOT A GATE. `standard-brief-generate` is the
# briefing GENERATOR: it rewrites a tracked page and exits 0 whether that page
# was fresh or stale, so the battery cannot go red on briefing staleness and a
# green here says nothing about it. It earns its slot by proving the generator
# still RUNS; the freshness comparison is `standard-brief`, which is armed in
# devcards.yml and is NOT armed here — a DECISION, not an impossibility. The
# older reason ("git does not resolve inside the container") was measured and
# retired: `tools/uber.sh` declares safe.directory for the workspace, so git
# resolves normally under it, which is exactly what makes `dead-c-externs-test`
# — and therefore this battery — runnable locally at all. See
# .claude/rules/uber-container.md, which states outright that this target's
# freshness half is armable on both paths and stays unarmed by choice. Keeping
# the retired reason would tell a reader the check CANNOT be run here, which is
# the stale-belief-as-blocker that rule exists to prevent.
# That target's own block carries the full boundary. Every other name below
# fails on its own subject.
check-renderer-lanes: graal-check generated-projection-canary generated-projection construct-bindings conventions-projection state-mirror manifests devcards-test scratchcard-test scratchcard-brief clj-schema-test spec-coverage standard-brief-generate wasm wasm-inputs-check reference dead-c-externs dead-c-externs-test fixtures deadzone-canary overlap-canary scratchcard-lane dump-contracts harness interaction oracles reload decode-limits wire-constraints
	@echo "renderer battery: GREEN ($^)"
