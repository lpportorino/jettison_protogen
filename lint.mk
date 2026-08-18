# lint.mk — format + lint gates for hand-authored Clojure and C.
#
#   make -f lint.mk lint        # the lanes .githooks/pre-push runs, check-only.
#                               # WHICH lanes is the `lint:` prerequisite line and
#                               # nowhere else — a count here rots on the next
#                               # lane added, and it has (this said "four").
#   make -f lint.mk fmt-fix     # rewrite formatting in place
#   make -f lint.mk lint-clj    # one lane at a time
#
# WHAT IS GATED — and the shape is NOT the same for every language, so read the
# variable rather than this heading. Two shapes live here:
#
#   POSITIVE ALLOWLIST (the Clojure lanes, LINT_CLJ_PATHS/LINT_CLJ_FILES). Each
#   tool is handed an explicit list of hand-authored paths and never walks the
#   tree, so these generated and vendored TREES are excluded by never being
#   passed in, with no exclusion pattern to drift out of sync with reality:
#     output/**               generated bindings (10 languages)
#     renderer/lvgl/**        vendored upstream, byte-exact per .gitattributes
#     renderer/generated/**   nanopb + threshold projections
#     renderer/src/font_*.c   generated LVGL font data
#     docs/proto/**           generated markdown
#
#   GIT DISCOVERY MINUS DECLARED TREES (shell, workflows, and now C —
#   LINT_SH_FILES, LINT_CI_FILES, FMT_C_FILES). The question these lanes ask is
#   "does the checkout hold a file of this kind that NOTHING judges", and an
#   allowlist cannot ask it: it is silent about what it forgot. So discovery is
#   git's, and the generated/vendored trees are subtracted by name.
#
# THE TWO SHAPES FAIL IN OPPOSITE DIRECTIONS, which is why the split is by
# language and not by taste. A forgotten path drops out of an allowlist
# SILENTLY — a green over unjudged code. A newly-generated tree that nobody
# subtracts joins a discovery lane LOUDLY, as drift on the first run. Where the
# population is enumerable and stable, the allowlist's failure is cheap to
# audit (see `audit-clj-paths`). Where a new file may appear anywhere, the loud
# failure is the safer one.
#
# THIS HEADING USED TO SAY "positive allowlist, never an ignore list" FLATLY,
# and it was already untrue of the shell lane when it was written. Do not
# restore that reading: it is what made the C lane's second coverage hole look
# like a design choice.
#
# ONE GENERATED FILE IS INSIDE A GATED ROOT, so "generated code is never passed
# in" is not true as a flat claim and is not written that way here.
# tools/renderer-gen/src/lvgl_codegen/generated/enums.clj is emitter output living
# under a `src` root, and it IS handed to cljfmt and clj-kondo ON PURPOSE: a
# projection must stay canonical and lint-clean, and both of those are satisfiable
# by regenerating it. What it is held out of is the STRUCTURAL checks, because no
# finding against it could be satisfied at all — you cannot hand-shrink a file
# whose bytes `construct-bindings` asserts equal to a fresh extraction; the fix
# would always be in the generator. `lint-gate.core/generated?` derives that
# exclusion from the path rather than listing the file, so a second generated
# projection is covered the day it appears.
# One HAND-AUTHORED tree is also held out, which the list above would not lead
# you to expect — docs/.protodoc/scripts/. It is not generated or vendored, so
# its exclusion is a debt with a written expiry rather than a scoping fact; the
# rationale and the condition that retires it sit at the LINT_CLJ_PATHS
# declaration, which is the one home for what is and is not gated.
#
# WHERE EACH LANE CAN RUN. Only the lanes that REWRITE committed source are
# bound to the pinned container (see .claude/rules/uber-container.md):
#   fmt-c / fmt-c-fix  container or host — the pinned clang-format lives in the
#                      WASI-SDK, so `tools/uber.sh` is the correct entry point.
#   fmt-clj*           container or host — cljfmt is a pinned dep in deps.edn.
#   lint-clj           HOST or CI only — clj-kondo is NOT in the uber image, and
#                      does not need to be: a linter emits findings, never a
#                      committed artifact, so the uber-container rule does not
#                      reach it. CI pins it in .github/workflows/lint.yml,
#                      alongside the Clojure CLI — see that file for the action
#                      and versions rather than copying pins here. This is why
#                      `tools/uber.sh 'make -f lint.mk lint'` fails on lint-clj
#                      while each other lane runs there happily.
#
# PARALLELISM — every lane saturates the machine it runs on. NPROC is detected
# at run time (Linux/macOS/POSIX fallback) rather than pinned, because dev
# machines differ and CI runners differ again; a hardcoded -j would either
# starve a big box or oversubscribe a small one.

NPROC := $(shell nproc 2>/dev/null \
	|| sysctl -n hw.ncpu 2>/dev/null \
	|| getconf _NPROCESSORS_ONLN 2>/dev/null \
	|| echo 4)

# clang-format REWRITES COMMITTED SOURCE, so its version is part of the
# toolchain pin, not a local detail — clang-format's output changes across
# major versions, and a dev on a different one would fight CI forever. The
# WASI-SDK the renderer is built with already ships one (it is just not on
# PATH), so the uber container and CI resolve to that pinned binary; a bare
# `clang-format` is the host fallback for a read-only check.
CLANG_FORMAT := $(firstword $(wildcard /opt/wasi-sdk/bin/clang-format) clang-format)

# EVERY JVM THIS FILE SPAWNS WRITES FINDINGS, AND THE IMAGE SETS NO UTF-8 LOCALE,
# so a finding quoting a non-ASCII character loses it AT WRITE TIME. Measured:
# LANG and LC_ALL are both unset, and `java -XshowSettings:properties` reports
# stdout.encoding = ANSI_X3.4-1968 (so does native.encoding). The doc lint's
# output then carried 105 `?` and ZERO bytes above 127 — the characters are gone
# from the file, not merely rendered oddly.
#
# `file.encoding` IS NOT THE LEVER, and is the obvious wrong fix: it already
# reports UTF-8 here, so setting it changes nothing. The stream encodings are
# separate properties and are the ones left at ASCII.
#
# WHY A VARIABLE RATHER THAN AN EXPORTED JAVA_TOOL_OPTIONS: that would be one
# edit instead of nine, and it prints `Picked up JAVA_TOOL_OPTIONS: ...` to
# stderr once per JVM — measured at TWO lines for a single lane — which is noise
# in the output of gates whose whole product is readable findings.
#
# WHY NOT FIX THE LOCALE IN THE IMAGE: that is the root cause and would fix every
# tool at once rather than the JVMs alone. It is deliberately NOT done here — it
# changes a shared image and every lane's behaviour, so it deserves its own
# change with its own battery run rather than riding along inside a lint fix.
CLJ := clojure -J-Dstdout.encoding=UTF-8 -J-Dstderr.encoding=UTF-8

# Same resolution, same reason, for clang-tidy's driver. The SDK is not on
# PATH inside the container either, so a bare `run-clang-tidy` made
# `tools/uber.sh 'make -f lint.mk lint-c-tidy'` fail with an error telling you
# to run it inside tools/uber.sh — which is what you were doing. CI was green
# only because renderer.yml exported the directory inline, i.e. the local entry
# point and CI disagreed about how the gate finds its own binary.
# `-clang-tidy-binary` is passed explicitly below for the same reason: the
# driver resolves the analyser off PATH, where the pinned one also is not.
RUN_CLANG_TIDY := $(firstword $(wildcard /opt/wasi-sdk/bin/run-clang-tidy) run-clang-tidy)
CLANG_TIDY := $(firstword $(wildcard /opt/wasi-sdk/bin/clang-tidy) clang-tidy)

# Hand-authored Clojure — the positive allowlist handed to cljfmt, clj-kondo and
# splint alike. Mostly source ROOTS; a lone FILE is equally valid to every one of
# those tools, and is the honest shape for build.clj: it is the only hand-authored
# Clojure at its tools root, which otherwise holds config, a Dockerfile, generated
# resources and a gitignored .cpcache. Naming the file says "this one file", which
# is what is true; naming the directory would claim the whole tree is source.
#
# HOW THIS SET IS KEPT HONEST — a positive allowlist is only as good as the
# audit that says nothing is missing from it, so derive the gap, do not eyeball
# it: `make -f lint.mk audit-clj-paths` diffs every TRACKED hand-authored Clojure
# file against what these paths expand to. Whatever it prints is UNGATED, and
# belongs either in the list below or in the held-out block after it. A tracked
# hand-authored file in NEITHER place is the hole that audit exists to close — a
# test tree once sat outside both the formatter and the linter for exactly as
# long as nobody ran that diff.
LINT_CLJ_PATHS := tools/devcards/src \
	tools/devcards/dev \
	tools/devcards/test \
	tools/renderer-gen/src \
	tools/renderer-gen/test \
	tools/renderer-gen/dev \
	tools/scratchcard/src \
	tools/scratchcard/test \
	tools/scratchcard/dev \
	docs/.protodoc/tools/src \
	docs/.protodoc/tools/test \
	docs/.protodoc/tools/build.clj \
	tools/lint/src \
	tools/protocol-gen/src \
	tools/protocol-gen/test \
	tools/protocol-gen/verify

# HAND-AUTHORED CLOJURE JUDGED BY clj-kondo AND cljfmt BUT NOT BY THE
# STRUCTURAL GATES. `LINT_CLJ_PATHS` above is what every lane receives; this
# list is added to the two lanes that are file-shaped rather than
# namespace-shaped.
#
# tools/scratchcard/bin/scratchcard.bb — the babashka client. It is the only
# tracked `.bb` in the repo, and it IS hand-authored first-party source, so it
# belongs in a lane. It cannot go in LINT_CLJ_PATHS: the structural gates floor
# each configured root INDIVIDUALLY and a lone script is a root of one file
# they cannot populate, so `lint-fn-size` refuses with CANNOT RUN (exit 3) —
# the per-root floor working exactly as designed, not a bug to route around.
# clj-kondo and cljfmt take a file argument happily, so it is gated by both,
# and it earned that immediately: the first run found `rest` shadowing
# `clojure.core/rest` in the client's own `-main`.
#
# RETIRES WHEN: the structural gates grow a file-grain root, or the client
# grows into a directory of namespaces.
LINT_CLJ_FILES := tools/scratchcard/bin/scratchcard.bb

# THE ONE HELD-OUT TREE — on the record, never silently absent:
#
# docs/.protodoc/scripts/ — the `#!/usr/bin/env bb` slash-command backends
# (proto-search, proto-coverage, doc-next). Hand-authored
# and first-party, so they BELONG in the list above. They are held out because
# none of them carries an `ns` form: clj-kondo then treats the whole directory as
# one implicit `user` namespace, and CROSS-FILE collisions — Shadowed var,
# duplicate require, redefined var — dominate what it reports. Lint one script on
# its own and most of its findings vanish. Reproduce both halves:
#
#   clj-kondo --cache false --lint docs/.protodoc/scripts
#   clj-kondo --cache false --lint docs/.protodoc/scripts/proto-search.clj
#
# A real residue survives that collapse (an unresolved clojure.java.io, locals
# shadowing clojure.core, unused bindings, a redundant let), and some of the
# scripts are cljfmt-dirty too. So clearing this is a deliberate SOURCE change —
# give each script a real `ns`, then rename the shadowing locals — and that
# rename is the sharp edge `.claude/rules/lint-gates.md` warns about: a reference
# you miss silently resolves to the clojure.core var, stays green under the
# linter, and dies at runtime. It has to be made against these scripts while
# RUNNING them, never as a side effect of widening a path list.
#
# RETIRES WHEN: the scripts carry `ns` forms and lint clean — at which point this
# block is deleted and `docs/.protodoc/scripts` joins LINT_CLJ_PATHS above.

# Hand-authored C. Discovery is git's, minus the generated and vendored trees
# named below — the same shape LINT_SH_FILES uses, adopted for the same measured
# reason: a formatter that cannot see a file reports green over unjudged code,
# which is the same class as an empty input set passing.
#
# THE ROOT LIST IT REPLACES SPRUNG THAT LEAK TWICE, and the second time is what
# retires the mechanism rather than patching it again. First the two
# hand-authored LVGL config headers — `renderer/lv_conf.h` (release) and
# `renderer/config/dev/lv_conf.h` (dev), both selected by name in wasm.mk's
# LV_CONF — were gated by NOTHING, and two roots were added. Then
# `tools/renderer-gen/tools/theme-style-groups/emit.c` was found outside all
# three: hand-authored C, compiled `-Wall -Wextra -Werror` by its own
# generate.sh, judged by no formatter at all. A named-root list is silent about
# the root nobody named, so the hole recurs wherever the next hand-authored C
# file lands. Discovery that starts from the checkout cannot have that hole.
#
# `--cached --others --exclude-standard`, exactly as LINT_SH_FILES and
# LINT_CI_FILES carry it and for their measured reason: the INDEX ALONE gives a
# worker who has written a new C file and not staged it a GREEN THAT NEVER READ
# IT. Ignored paths (build output, scratch) stay out, which keeps the widening
# free. Consequence worth knowing rather than discovering: an untracked,
# non-ignored `.c` DOES enter this lane, so a scratch probe left in the tree
# reds `fmt-c`. It cannot be silently REWRITTEN by the pre-push hook, whose
# dirty-tree guard reads `git status --porcelain` and so declines to auto-fix
# while one exists; a hand-run `fmt-c-fix` would rewrite it, the same exposure
# `fmt-clj-fix` already has over whole paths.
#
# THE SUBTRACTED TREES ARE THE ONES .clang-format's own header already names as
# NOT formatted — this makes that prose mechanical rather than adding a second
# list. Each is generated or vendored, so a clang-format rewrite there is either
# destroyed at the next regeneration or forks a byte-exact pin:
#   output/**               generated bindings (10 languages)
#   renderer/generated/**   nanopb + threshold projections
#   renderer/lvgl/**        vendored upstream, byte-exact per .gitattributes
#   renderer/src/font_*.c   generated LVGL font data, inside a hand-authored dir
# Measured at adoption: this expands to the 21 files the root list found PLUS
# emit.c, and nothing else — so the mechanism change costs no churn beyond the
# one file the old shape could not see.
FMT_C_EXCLUDES := ':!output/**' ':!renderer/generated/**' \
	':!renderer/lvgl/**' ':!renderer/src/font_*.c'
FMT_C_DISCOVERY_ARGS := --cached --others --exclude-standard '*.c' '*.h' $(FMT_C_EXCLUDES)
FMT_C_FILES := $(shell git ls-files $(FMT_C_DISCOVERY_ARGS) 2>/dev/null | sort)
# CAPTURED, not discarded, for the reason lint-sh's twin states: when discovery
# fails the reason IS the diagnosis, and the probe must ask the SAME question as
# the discovery or a fault specific to these flags yields an empty file list and
# an empty diagnosis. Exported so the guard reads it as a shell variable rather
# than interpolating git's own quoting into shell text.
FMT_C_DISCOVERY_ERR := $(shell git ls-files $(FMT_C_DISCOVERY_ARGS) 2>&1 >/dev/null)
export FMT_C_DISCOVERY_ERR

# Hand-authored shell. `--others --exclude-standard` widens the index to
# UNTRACKED-but-not-ignored scripts, because the index alone gives a worker who
# has written a new script and not staged it a GREEN THAT NEVER READ IT — the
# same vacuity this target's own guard below refuses, one level up. Ignored
# paths (scratch, preserved forks) stay out, which is what keeps the widening
# free. renderer/lvgl/** is vendored and excluded.
#
# git's stderr is CAPTURED rather than discarded: when discovery fails, the
# reason ("not a git repository: …") is the whole diagnosis, and lint-sh's
# guard prints it instead of guessing. Discarding it is what let a broken
# discovery read as an empty-but-fine file list.
#
# THE PROBE MUST RUN THE SAME COMMAND AS THE DISCOVERY, and it used not to: it
# dropped `--cached --others --exclude-standard`, so it was a DIFFERENT
# EXPERIMENT from the one that had failed. Any fault specific to those flags
# then produced an empty file list AND an empty diagnosis, and the guard below
# fell through to its gitdir-mount advice — which is not merely absent
# reasoning, it is the WRONG cause printed with confidence.
#
# Measured: with `core.excludesFile` naming a path git cannot read (a container
# inheriting a host-side config value is the live way to reach this),
#   git ls-files --cached --others --exclude-standard '*.sh' → exit 128,
#     "fatal: cannot use … as an exclude file"
#   git ls-files '*.sh'                                      → exit 0, no stderr
# One invocation is run twice rather than two different invocations once each,
# because make's $(shell) yields one stream per call; what matters is that both
# calls ask the identical question.
# `tools/scratchcard/bin/scratchcard` is named without an extension ON PURPOSE
# — it is the command a user types — so the '*.sh' glob cannot see it and it is
# listed explicitly, exactly as .githooks/pre-push is. A shell entry point that
# no lane parses is the least-checked code in the tree.
LINT_SH_DISCOVERY_ARGS := --cached --others --exclude-standard '*.sh' .githooks/pre-push tools/scratchcard/bin/scratchcard
LINT_SH_FILES := $(shell git ls-files $(LINT_SH_DISCOVERY_ARGS) 2>/dev/null \
	| grep -v '^renderer/lvgl/' | sort)

# The shellcheck subset: first-party only. renderer/lvgl/ is VENDORED (pinned by
# renderer/lvgl/.ported-from.edn) and third-party code is never policed here —
# and the distinction is not cosmetic. Measured over the full tracked set there
# are five ERROR-level findings; FOUR of them are SC2068 inside upstream LVGL's
# own add_lvgl_if.sh scripts. Including the vendored tree would have made the
# gate look like it needed four fixes we must not make.
LINT_SC_FILES := $(filter-out renderer/lvgl/%,$(LINT_SH_FILES))
LINT_SH_DISCOVERY_ERR := $(shell git ls-files $(LINT_SH_DISCOVERY_ARGS) 2>&1 >/dev/null)
# EXPORTED so the guard can read it as a SHELL variable instead of interpolating
# it into shell text. That is this lane's OWN bug class, living in this lane's
# diagnosis: git quotes paths in its errors as a matter of course
# ("fatal: pathspec 'x' did not match…"), and the guard used to expand the value
# inside a SINGLE-QUOTED printf argument. One apostrophe from git rebalanced the
# quoting, the rest of the recipe was reinterpreted as shell, and the target died
# with `/bin/sh: syntax error near unexpected token` and make Error 2 — an ERROR
# wearing the same red as this gate's own Error 1 FAIL, pointing at a line that
# is not the problem, with the diagnosis it was printing never printed at all.
# Measured with core.excludesFile naming a directory called `o'brien`.
# A double-quoted expansion of an exported variable is re-parsed by nothing, so
# apostrophes, quotes, `$` and backticks in git's message all survive intact.
export LINT_SH_DISCOVERY_ERR

.PHONY: lint lint-lanes lint-clj fmt-clj splint-clj fmt-c lint-sh fmt-fix fmt-clj-fix fmt-c-fix cpus \
	install-hooks hooks-status audit-clj-paths wire-contract docs-lint \
	lint-no-host-paths lint-no-host-paths-test lint-ns-size lint-clj-gate-test \
	lint-fn-size lint-docstrings lint-spec-shape lint-spec-presence \
	lint-file-size lint-file-size-test wasm-provenance-test wire-contract-codec-test \
	lint-c-tidy lint-c-tidy-test

## install-hooks: point git at .githooks (arms the pre-push gate)
# Idempotent — re-running is a no-op. Deliberately NOT armed automatically on
# session start: core.hooksPath is a mutation of YOUR local git config, and
# arming a gate that is currently red would block every push including the fix.
install-hooks:
	@git config core.hooksPath .githooks
	@printf '\033[32m[hooks]\033[0m core.hooksPath = .githooks (pre-push gate armed)\n'

## hooks-status: report whether the pre-push gate is armed
hooks-status:
	@if [ "$$(git config --get core.hooksPath 2>/dev/null)" = ".githooks" ]; then \
		printf '\033[32m[hooks]\033[0m pre-push gate ARMED\n'; \
	else \
		printf '\033[33m[hooks]\033[0m pre-push gate NOT armed — make -f lint.mk install-hooks\n'; \
	fi


## lint: the check-only lanes the PRE-PUSH HOOK runs — not all of them
# WHO CALLS THIS, precisely, because the answer used to be written down wrong.
# `.githooks/pre-push` is the SOLE caller — its `make -f lint.mk lint` line. The
# line NUMBER is deliberately not quoted here: it was, it said 86, and the next
# edit to the hook moved it. A citation into a file that grows above the cited
# line rots on the first commit that touches it, so anchor on the invocation. No CI job invokes it: CI
# runs the lanes INDIVIDUALLY — lint.yml calls `lint-sh`, `fmt-clj`, `lint-clj`
# as three steps, and renderer.yml calls `fmt-c` and `lint-c-tidy` inside the
# pinned image. So this target is LIVE and local, and the comment that called it
# "the CI entry point" pointed a reader at a caller that does not exist while
# the one that does exist depends on it — the exact reading under which someone
# deletes a target the local gate needs.
#
# NOR IS IT "every gate", and the lanes it omits are omitted for DIFFERENT
# reasons — which is why none of them belongs in a headline that says "every":
#   lint-c-tidy    omitted for a MECHANICAL reason, not a semantic one. It needs
#                  run-clang-tidy from the pinned WASI-SDK plus a compile
#                  database emitted from the build's own flags, and `lint` is
#                  invoked BARE by the hook rather than through tools/uber.sh —
#                  so folding it in here would hard-fail every push from a
#                  machine without the image. The hook therefore calls it
#                  SEPARATELY and docker-gated. It used to run in renderer.yml
#                  ALONE, which made it "the one lane a green local push has
#                  genuinely not run"; that is no longer true where docker is
#                  present, and the hook says out loud when it is not.
#   lint-c-tidy-test  its CANARY, omitted for the same mechanical reason and
#                  wired in the same docker-gated block, canary first. Note the
#                  OPPOSITE call made for wire-contract-codec-test below, which
#                  DOES ride this aggregate: that is the same argument, not a
#                  different one — a canary rides `lint` when it needs nothing
#                  the other lanes need, and this one needs exactly the pinned
#                  toolchain its gate needs.
#   wire-contract  a different KIND of gate — a generated artifact contradicting
#                  a hand-written contract, see below — deliberately kept out of
#                  `lint` so a green `lint` keeps meaning "formatting and lint
#                  over hand-authored code".
#   docs-lint      proto-aware in the same way, and separate for the same reason.
# So the HOOK's gate set is strictly wider than this target, by the lanes listed
# above. NO COUNT IS GIVEN: this sentence said "those three" and went stale the
# moment a fourth was added to the list it points at. Read the hook for what a
# push actually runs; this list is what `lint` means, which is a narrower
# question.
# WHAT A GREEN `lint` COVERS is the prerequisite line below, read directly. No
# count is given here on purpose: this comment claimed "four" for as long as it
# took someone to add a fifth, and a stale tally beside a live list is worse than
# no tally, because it reads as a promise about scope.
#
# lint-sh runs FIRST and cheaply: it is the gate that catches the class of bug
# that has actually taken this repo down (see below).
#
# THE LEAK BAN AND ITS CANARIES RIDE HERE, and the aggregate is no longer four
# lanes — see lint-no-host-paths below for what it checks and why its CI half is
# a SEPARATE workflow rather than a step in lint.yml.
# PARALLEL BY DEFAULT, via a sub-make, because the lanes are independent and the
# serial chain was the whole cost. MEASURED interleaved and warm at -j12, three rounds
# each: 103.8 s serial -> 48.1 s, a 2.16x wall reduction, both exiting 0. Parallel
# variance is also far tighter (46.6-48.9 s vs 96.8-113.5 s), which matters for a hook
# a human waits on.
#
# THE FIRST FIGURE I MEASURED WAS 4.25x AND IT WAS WRONG — the serial baseline had been
# taken while other work ran on the machine, inflating it to 226 s. Interleaving the two
# variants is what exposed it. A speedup measured against a contaminated baseline
# overstates itself in exactly the direction that flatters the change.
#
# WHY A SUB-MAKE RATHER THAN TELLING PEOPLE TO PASS -j: the hook and CI invoke
# `make -f lint.mk lint` with no flags, so a speedup that depends on the caller
# remembering a flag is a speedup nobody gets. renderer.mk's `check-renderer` uses
# exactly this shape for exactly this reason.
#
# VERIFIED NOT TO CHECK LESS, which is the trap a parallel gate invites: every lane
# named on the prerequisite line below prints its completion marker in the -j12 log and
# `make` reports no "Nothing to be done". A faster gate that judged fewer files would
# be a regression wearing an improvement. NO LANE COUNT IS GIVEN HERE — the
# prerequisite line is the population, and a tally beside it rots the next time a lane
# lands, which this sentence's own did.
#
# WHAT PARALLELISM COSTS: interleaved output. A failing lane is still named by make's
# own `*** [<target>] Error N` line, and the canary suites print their own labels, so a
# red stays attributable — but a reader scanning for context around a failure will find
# it interleaved with other lanes. Run the single lane to read it cleanly.
lint:
	@$(MAKE) --no-print-directory -f lint.mk -j$(NPROC) lint-lanes

lint-lanes: lint-sh lint-ci lint-md-test lint-md lint-no-host-paths-test lint-no-host-paths lint-file-size-test lint-file-size lint-clj-gate-test lint-ns-size lint-fn-size lint-spec-shape lint-spec-presence lint-docstrings brief-check-test forks-release-test uber-chown-test leg-strictness-test wasm-provenance-test wire-contract-codec-test wire-contract-envelope-test fork-hazards protocol-gen-test protocol-gen-canary fmt-clj lint-clj fmt-c

## protocol-gen-test / protocol-gen-canary: the generator tool's two OWN lanes
# Delegated to `Makefile` by SUB-MAKE, exactly as lint-md is delegated to
# lint-md.mk, because that is where the targets live: `Makefile` owns the tool's
# entry points and this file owns what the pre-push gate runs. Neither fact
# belongs in the other file, so the aggregate reaches across rather than the
# targets moving.
#
# WHY THESE TWO AND NOT THE TOOL'S LINT TARGET. Its lint target was RETIRED
# rather than enrolled: it ran the root `:fmt` alias and clj-kondo over
# `tools/protocol-gen/{src,test,verify}`, and those three roots are now in
# LINT_CLJ_PATHS — so `fmt-clj` and `lint-clj` above check the same files with
# the same tools, from the same one home for the pins. A second invocation of one
# formatter is not a second authority, it is the same run done twice.
# These two are the opposite case: both need the TOOL's own `deps.edn` aliases —
# kaocha plus the armed malli instrumentation for the suite, protobuf-java for
# the oracle the canary drives — so no generic lane can absorb them.
#
# THEY GROW `lint`'S FOOTPRINT BY ONE TOOL: protoc. The canary hard-fails when
# protoc or clojure is absent rather than skipping, which is the correct shape
# for a canary and the cost of arming it here — a suite that passed because its
# toolchain was missing is the defect it exists to catch, wearing a green.
.PHONY: protocol-gen-test protocol-gen-canary
protocol-gen-test:
	@$(MAKE) --no-print-directory -f Makefile protocol-gen-test

protocol-gen-canary:
	@$(MAKE) --no-print-directory -f Makefile protocol-gen-canary

## lint-ns-size: NAMESPACE SIZE ceiling over hand-authored Clojure
# TWO AXES (code-LOC, public-var count) and TWO TIERS (a blocking ceiling and a
# non-blocking watchlist). The reasoning lives in `lint-gate.core`'s docstrings —
# one home, not two — but the fact to know before touching the numbers is that
# each blocking ceiling was SEEDED AT THIS TREE'S MEASURED MAXIMUM, with its
# provenance recorded beside it in tools/lint/gates.edn. That is what makes the
# gate green on arrival with ZERO exemptions: seeding at an aspirational value
# would need a proof-carrying waiver per outlier, and a dozen invented rationales
# is worse than one honest number, because it corrupts the one field the exemption
# machinery depends on being true. The ceilings only ever move DOWN.
#
# WRITTEN IN CLOJURE, AND THAT IS THE POINT rather than a preference: its source
# sits in LINT_CLJ_PATHS, so the gate is judged by cljfmt, by clj-kondo at the
# zero-warning floor, and by its OWN ceiling. Nothing in this repo lints a script
# language, so a gate written in one would be the least gated code in the tree —
# and it earned that immediately, since clj-kondo caught a dead private var in the
# gate on its first run. It declares no dependency: clj-kondo emits its analysis
# as EDN and `clojure.edn` is standard library.
#
# SAME PATH LIST as every other Clojure lane — LINT_CLJ_PATHS, the positive
# allowlist. `make -f lint.mk audit-clj-paths` is what keeps it honest.
#
# NEEDS clj-kondo, so it sits with lint-clj on the HOST/CI side of the container
# split (see this file's header). It reads the --analysis output rather than
# parsing source, because the analysis is a RESOLVED graph: it knows a var's
# :private and resolves aliases a text scan cannot.
lint-ns-size:
	@$(CLJ) -M:lint-gate --check ns-size $(LINT_CLJ_PATHS)

## lint-fn-size: the same ratchet one scale down — FUNCTION length, nesting, decisions
# Three axes in ONE check, matching what ns-size does with its two: they share the
# traversal, the docstring subtraction and the population, and three separate
# checks would give the three populations three chances to drift apart.
#
# MEASURED BY THE CODE THAT GATES IT. lint-gate.fnsize computed the seeded ceilings
# AND enforces them, so a ceiling cannot disagree with its own gate — which is the
# failure a separately-written measurement script invites.
#
# READS SOURCE FORMS, not clj-kondo's analysis: the analysis carries :row/:end-row,
# which is enough for LENGTH and nothing else, because nesting and decisions are
# properties of the SHAPE of a body that the analysis does not expose. So this lane
# needs no clj-kondo pass, and it floors its own population (functions) rather than
# borrowing the analysis guard, which floors namespaces and var-definitions.
#
# TWO HEAD SETS ARE THE METRIC and both deliberately diverge from the surveyed
# donor's. Nesting counts branching and iteration but NOT `let`/`do`, because a
# `let` introduces names rather than a condition. Decisions count ARMS not HEADS,
# so a 71-arm dispatch scores 71 and not 1 — this tree's largest namespaces are
# exactly that shape, so a head count would have been blind to the code it most
# needed to see. Consequence, stated because the numbers look borrowable and are
# not: the donor's thresholds measure different quantities. gates.edn records for
# each watchlist number whether it is comparable, and where it came from.
lint-fn-size:
	@$(CLJ) -M:lint-gate --check fn-size $(LINT_CLJ_PATHS)

# The gate's own canaries. They ride `lint` for the same reason brief-check-test
# and the leak-ban suite do: a gate whose canaries are never RUN is a gate nobody
# has checked since the day it landed.
#
# Each clause is proven to fire ALONE by mutating the gate's own source in a
# throw-away fixture — including the SEEDED-RATCHET property itself (BLOCK is
# strictly-greater-than, so a ceiling at the measured maximum leaves that
# namespace AT the ceiling rather than over it; relax `>` to `>=` and the canary
# goes red). The two vacuity guards are proven to be what stands between an empty
# corpus and a green tick, and with BOTH removed the run is proven to CRASH rather
# than pass — labelled exit 3, never the findings code, so a stack trace can never
# be mistaken for a verdict about the tree.
lint-clj-gate-test:
	@bash tools/lint/test/lint_gate_test.sh
	@bash tools/lint/test/lint_gate_checks_test.sh

## lint-docstrings / lint-spec-shape: the two DECLARED-SCOPE Clojure checks
# BOTH are in the `lint` aggregate, so the pre-push hook runs them.
#
# lint-docstrings — every defn/defn-/defmacro in an ENROLLED root carries a
#   non-empty docstring. Enrolment is a DECLARED SCOPE, not a baseline: the check
#   is TOTAL inside the roots named in tools/lint/gates.edn, with ZERO parked
#   findings, and a root that is not ready is left out BY NAME with its reason.
#   `.claude/rules/gate-enforcement.md` §1 is what makes that the legitimate shape
#   and a tolerated miss-count the forbidden one.
#
# lint-spec-shape — no bare `:any`, `:map` or `:string` in an `m/=>` argument or
#   return position. `:keyword` and `:int` are TIGHT and deliberately not refused:
#   for a function whose argument really is an arbitrary keyword, pushing the author
#   toward something narrower would manufacture a FALSE schema, and in a population
#   nothing checks at runtime that is the worst available outcome.
#   It reads SOURCE FORMS rather than clj-kondo's analysis, so it needs no analysis
#   pass — and its non-vacuity floor is over SPECS, which is why it is not folded
#   into the analysis-driven checks.
#
# WHAT NONE OF THE THREE CAN SEE, so no caller over-reads a green: they check
# PRESENCE and SHAPE, never TRUTH. No linter can judge truth — clj-kondo lints
# `malli.core/=>` as a no-op — and the one runtime seam that can,
# `lvgl-codegen.instrument/arm!` (kaocha post-load in tools/renderer-gen/tests.edn),
# only reaches a spec on the calls that tree's SUITE makes. So a spec that
# mis-describes a function nothing exercises still reds nothing.
# `.claude/rules/malli-schemas.md` carries the argument.
.PHONY: lint-docstrings lint-spec-shape lint-spec-presence
lint-docstrings:
	@$(CLJ) -M:lint-gate --check docstrings $(LINT_CLJ_PATHS)

lint-spec-shape:
	@$(CLJ) -M:lint-gate --check spec-shape $(LINT_CLJ_PATHS)

## lint-spec-presence: every defn in an ENROLLED NAMESPACE carries an m/=>
# THE THIRD DECLARED-SCOPE CHECK, and the one whose scope had to be finer than a
# root. Measured: only tools/renderer-gen/src practises arrow specs at all (324
# of its 380 functions), every other gated root is at 0.0%, and that root itself
# is at 85.3% — so a root-grain enrolment would have to be EMPTY, which the
# check's own floor refuses. Enrolment is therefore by NAMESPACE, seeded at the
# 31 measured at 100%, which is the same grain `lvgl-codegen.spec-coverage`
# already uses for its own scope rather than a second vocabulary.
#
# WHY THIS IS NOT THE BASELINE `.claude/rules/gate-enforcement.md` §1 REFUSES.
# The refused shape enumerates individual findings that a fix removes one at a
# time. `:enrolled` names the namespaces the check JUDGES: no entry is a finding,
# no fix removes an entry, and the list GROWS as the tree improves where a
# baseline shrinks. §1 names this move as the permitted one when a check cannot
# pass whole-tree — narrow the declared scope, say what was left out, state the
# measured finding count — and tools/lint/gates.edn does all three.
#
# IT JUDGES 18.8% OF THE GATED TREE AND PRINTS THAT EVERY RUN. §3's closing
# clause is that a floor proves the population is non-empty, never that it is the
# RIGHT one, so the unjudged remainder is reported rather than left for a reader
# to assume away.
#
# READS SOURCE FORMS because the analysis cannot ATTRIBUTE a spec to its
# function — not because it cannot see one. An `m/=>` DOES appear there, as a
# var-usage naming malli.core, once per spec; what it lacks is the subject,
# which is a separate entry sharing only its row.
lint-spec-presence:
	@$(CLJ) -M:lint-gate --check spec-presence $(LINT_CLJ_PATHS)

## lint-md / lint-md-test: markdown quality over HAND-AUTHORED .md
# Delegated to lint-md.mk by SUB-MAKE, never by `include`. Make's default goal is
# the first target of the first file READ, so an `include` above this file's own
# first target would silently retarget a bare `make -f lint.mk`.
#
# WHOLE-TREE, like the leak ban and for a sharper reason: two of its clauses
# (`dead-path-citation`, `paths-glob-dead`) resolve citations against the whole
# TRACKED UNIVERSE, so its verdict depends on files that are not markdown at all.
# A `*.md` path filter over it would be a false skip by construction — deleting a
# cited `.c` file changes the verdict with no markdown touched. Its CI half
# therefore rides .github/workflows/hygiene.yml, which carries no path filter.
#
# CANARIES FIRST, for the reason the leak-ban block below states for its own
# ordering: this gate's clauses can each refuse the same input, so a red says
# nothing about WHICH clause fired until the suite has settled it by mutation.
#
# HOST-SAFE. Needs bash, git and the CLOJURE CLI (hence a JDK) — the gate was
# ported off python, so this lane's footprint changed and its CI job had to grow a
# toolchain step it previously did not need (.github/workflows/hygiene.yml).
# Nothing here rewrites a committed artifact, so the uber-container rule does not
# reach it.
.PHONY: lint-md lint-md-test
lint-md:
	@$(MAKE) --no-print-directory -f lint-md.mk lint-md

lint-md-test:
	@$(MAKE) --no-print-directory -f lint-md.mk lint-md-test

## lint-no-host-paths: LEAK BAN — no operator-host absolute path in a checked-in file
# protogen is PUBLIC and cloned by every consumer that pins it, so a path under
# one operator's home is wrong for every other reader — and when it names a
# private sibling checkout it also leaks that the sibling exists and where.
# `.claude/rules/claude-md-policy.md` § Discouraged already DECLARED this ban;
# nothing enforced it, and the first run found a real offender.
#
# WHOLE-TREE, so it is NOT scoped by LINT_CLJ_PATHS. That is the one thing to
# know before touching its wiring: every other lane here is handed a positive
# allowlist of hand-authored paths, while this one asks a question about the
# whole checkout and reduces its scope with EXCLUSIONS instead. Its input set is
# therefore the tree, which is why its CI half cannot live behind lint.yml's
# path filter (see .github/workflows/hygiene.yml).
#
# The canaries run BEFORE the gate, on purpose: this is the only lane here whose
# clauses can each refuse the same input, so "it went red" proves nothing about
# WHICH clause refused. The suite settles that by mutation, and a gate whose
# canaries are never run is a gate nobody has checked since it landed — the same
# argument brief-check-test and forks-release-test ride this aggregate on.
lint-no-host-paths:
	@bash tools/lint/no_host_paths.sh

lint-no-host-paths-test:
	@bash tools/lint/test/no_host_paths_test.sh

## lint-file-size: SIZE CEILING over the hand-authored population
# protogen is PUBLIC, so a large file committed here is materialised in every
# consumer's checkout, in every Docker build context and in the corpus every
# whole-tree gate reads — and deleting it later does NOT remove it from history.
# The cost is permanent from the moment it lands. This gate exists because that
# already happened: a 23,515,423-byte JSON schema sat at the repository root for
# a year, referenced by nothing.
#
# WHOLE-TREE, so it is NOT scoped by LINT_CLJ_PATHS — the same shape as
# lint-no-host-paths above, and for the same reason: its input set is the
# checkout, and it reduces scope with EXCLUSIONS rather than an allowlist.
#
# SIZE x SCOPE, NOT SIZE x REACHABILITY, and the gate's header carries the
# measurement behind that choice. Briefly: a reachability predicate must decide
# by NAME, and in this tree that is unsound both ways — the motivating file's
# basename was a strict SUFFIX of a neighbour's, so a substring scan credited it
# with the neighbour's referrers, while the genuinely large legitimate files are
# consumed at DIRECTORY granularity and would all read as unreferenced.
# Provenance is decidable here and reference count is not.
#
# THE TWO NUMBERS ARE DOWN-ONLY RATCHETS with their measured provenance recorded
# at the top of the script. Raising either is a gate bypass in source form; the
# canary suite pins both to literals so it cannot be a silent one-line change.
#
# The canary runs BEFORE the gate, same ordering argument as the leak ban.
lint-file-size:
	@bash tools/lint/file_size_ceiling.sh

lint-file-size-test:
	@bash tools/lint/test/file_size_ceiling_test.sh

## wire-contract: assert docs/INTERFACE-CONTRACTS.md against the descriptor set
# DELIBERATELY NOT IN THE `lint` AGGREGATE. `lint` means "formatting and lint
# over hand-authored code" and every workflow that calls it expects that
# meaning; this is a different kind of gate — a generated artifact contradicting
# a hand-written contract — and folding it in would silently change what a green
# `lint` claims. It is called explicitly instead, from the three places that
# need it: .githooks/pre-push, .github/workflows/wire-contract.yml, and
# .github/workflows/build-and-release.yml.
#
# ONE COMMAND, TWO DESCRIPTOR SOURCES, AND THE DIFFERENCE MATTERS. The script
# defaults to the COMMITTED output/json-descriptors/descriptor-set.json, which
# cannot see a proto edit nobody regenerated. build-and-release.yml runs this
# same target immediately after `make generate`, where that same path now holds
# the FRESHLY generated set — so the fan-out to the consumer repos is gated on
# the descriptors actually being shipped, not on the last ones committed.
#
# Needs nothing but python3 (stdlib only), which is why it can run on a plain
# runner, in the uber container, and in the pre-push hook alike.
wire-contract:
	@python3 tools/wire_contract_check.py --quiet

## wire-contract-codec-test: the canary for wire-contract's §2 / §9-G2 clauses
# IT RIDES `lint`, NOT `wire-contract`, and the split is deliberate. The gate
# itself is kept out of `lint` because `lint` means "formatting and lint over
# hand-authored code" and folding a contract-vs-descriptor check in would change
# what a green `lint` claims. Its CANARY has no such problem: it asserts that a
# checker can still refuse, which is exactly the kind of thing every other
# `*-test` lane here asserts, and it needs nothing the other lanes do not — no
# container, no rendered surface, python3 and two tracked files.
#
# And a canary that rides only the gate it covers is a canary nobody runs: the
# three places that invoke `wire-contract` are a push hook and two workflows,
# none of which anyone reaches for while iterating. The same argument
# brief-check-test, forks-release-test and wasm-provenance-test ride this
# aggregate on.
#
# SCOPE IS THE CODEC-HEADER CLAUSES ONLY, and the suite's own header says so. It
# does not drive the §9 descriptor derivation — tools/wire-contract-proofs/
# mutation_proof.sh is the probe for that — so a green here must not be read as
# coverage of the whole checker.
#
# HERMETIC: every case mutates a COPY of the doc and drives the real checker
# through its own `--doc`, so the tracked tree is never written and there is no
# restore whose success anyone has to take on trust.
wire-contract-codec-test:
	@bash tools/lint/test/wire_contract_codec_header_test.sh

## wire-contract-envelope-test: canaries for the §9-G5 tag-bound clauses
# A SEPARATE suite from its codec-header sibling on purpose. That one declares
# its scope, in its own header, as the codec-header clauses ONLY — widening it
# would make both its name and that declaration false, and a suite whose stated
# scope has quietly grown is worth less than two whose scopes are exact.
#
# Same hermetic shape: the checker takes --schema, so each case mutates a COPY
# and the tracked schema is never written.
wire-contract-envelope-test:
	@bash tools/lint/test/wire_contract_envelope_bound_test.sh

## docs-lint: proto documentation lint — A WARNING IS A FAILURE
# Runs the protodoc linter against the COMMITTED proto-db.edn. Every constrained
# field and every enum value owes a description; the rule set is
# docs/.protodoc/tools/src/protodoc/lint.clj.
#
# WHY A WARNING FAILS: `protodoc lint` used to exit 0 while printing its findings,
# so the CI step that runs it stayed green and warnings accumulated with nothing
# ever forcing a disposition. A warning nobody must act on is training to ignore
# the linter, and a pile of them is where a real defect hides in plain sight —
# the tool reports both the same way. Two responses are permitted: fix the
# finding, or delete the rule with its reasoning recorded.
#
# This runs the tools directory directly rather than through the docs Docker
# image, so it needs no image rebuild to reflect a rule change — deliberate,
# because a gate that runs a stale copy of itself is worse than none.
#
# THE WORD THAT USED TO SIT IN THAT SENTENCE WAS "silently", AND IT NO LONGER
# APPLIES to the containerised twin: `Makefile`'s docs-docker-* legs that run the
# BAKED generator now take docs-docker-build as a prerequisite, so an image
# lagging the tools source is rebuilt rather than quietly used. The reason this
# recipe stays on the host path is the remaining one — it needs no image at all,
# and the CI job that runs it builds none.
docs-lint:
	@cd docs/.protodoc/tools && $(CLJ) -M:run lint --db-path ../proto-db.edn

## cpus: report the detected parallelism (debug aid across dev machines)
cpus:
	@echo "NPROC=$(NPROC)"

## audit-clj-paths: report tracked hand-authored Clojure that NO lane gates
# The audit a positive allowlist needs and does not otherwise get: the allowlist
# says what IS gated and is silent about what it forgot, so the only way a
# forgotten tree surfaces is by diffing it against git's own index.
#
# REPORT-ONLY, deliberately not part of `lint`. Gating on it would mean encoding
# the held-out tree as a SECOND exclusion list right here — and a second list
# that can drift out of step with the first is exactly what the positive-
# allowlist design exists to avoid. Consequence, and it is intentional: the
# held-out docs/.protodoc/scripts/ files appear in this report EVERY time, so the
# debt stays visible until it is retired rather than fading into a silent
# subtraction.
#
# NON-VACUITY GUARD, the same class lint-sh and fmt-c carry: this target's clean
# value is "prints no files", which is also precisely what a broken `git ls-files`
# prints. Both sides of the diff are asserted non-empty before an empty result is
# allowed to read as clean.
audit-clj-paths:
	@t=$$(mktemp) && g=$$(mktemp) && \
	git ls-files '*.clj' '*.cljc' '*.bb' | sort > "$$t"; \
	{ for p in $(LINT_CLJ_PATHS); do git ls-files "$$p"; done; \
	  for f in $(LINT_CLJ_FILES); do git ls-files "$$f"; done; } \
		| sed -n '/\.\(clj\|cljc\|bb\)$$/p' | sort -u > "$$g"; \
	rc=0; \
	if [ ! -s "$$t" ] || [ ! -s "$$g" ]; then \
		printf '\033[31m[audit-clj-paths] FAIL\033[0m — a side of the diff is EMPTY\n' >&2; \
		printf '  (tracked=%s gated=%s). This repo has both, so an empty set means\n' \
			"$$(wc -l < "$$t")" "$$(wc -l < "$$g")" >&2; \
		printf '  DISCOVERY broke — git cannot resolve this checkout — not that\n' >&2; \
		printf '  there is nothing to audit.\n' >&2; \
		rc=1; \
	elif comm -23 "$$t" "$$g" | grep -q .; then \
		printf '\033[33m[audit-clj-paths]\033[0m UNGATED — tracked and hand-authored, in no lane:\n'; \
		comm -23 "$$t" "$$g" | sed 's/^/  /'; \
		printf '  Add each to LINT_CLJ_PATHS (or LINT_CLJ_FILES for a lone\n'; \
		printf '  file the structural gates cannot root), or hold it out ON THE\n'; \
		printf '  RECORD beside it.\n'; \
	else \
		printf '\033[32m[audit-clj-paths]\033[0m every tracked Clojure file sits in a lane\n'; \
	fi; \
	rm -f "$$t" "$$g"; exit $$rc

## lint-clj: clj-kondo over hand-authored Clojure
# --fail-level warning: a warning (exit 2) must fail exactly like an error
#   (exit 3) — the zero-warning floor in .clj-kondo/config.edn is meaningless
#   if the gate only trips on errors.
# --cache false: the gate must be DETERMINISTIC. clj-kondo's cache dir is
#   shared with any editor/LSP or analysis-only caller; an analysis-shaped
#   entry lacks var bodies and makes the next lint emit phantom
#   "unresolved symbol" findings.
lint-clj:
	@printf '\033[32m[lint-clj]\033[0m clj-kondo (parallel, %s cpus)\n' "$(NPROC)"
	@clj-kondo --parallel --cache false --fail-level warning --lint $(LINT_CLJ_PATHS) $(LINT_CLJ_FILES)

## fmt-clj: cljfmt check (fails if any file would be rewritten)
fmt-clj:
	@printf '\033[32m[fmt-clj]\033[0m cljfmt check\n'
	@$(CLJ) -M:fmt check $(LINT_CLJ_PATHS) $(LINT_CLJ_FILES)

## splint-clj: idiomatic-pattern lint — REPORT-ONLY, deliberately not in `lint`
# Measured, so the exclusion is a decision rather than an omission:
#   The overwhelming majority of raw findings are lint/prefer-method-values,
#   whose suggestion is the literal placeholder `(CLASS/.method …)` because
#   splint cannot infer the class — nothing to copy, nothing to autocorrect,
#   and adopting the 1.12 form repo-wide changes how each call site resolves.
#   That is a house-style migration to decide on its own terms, so it is
#   disabled in .splint.edn with that reasoning. Reproduce the split with
#   `clojure -M:splint $(LINT_CLJ_PATHS)` against .splint.edn with the rule
#   re-enabled; the counts move with every Clojure edit, so run it rather than
#   trusting a number here.
#   `--autocorrect` clears a chunk of what remains — and its output does not
#   survive our own gates. Measured on this tree it dropped a load-bearing comment, blew
#   readable `str` calls out to one argument per line, flattened hand-aligned
#   map literals, and inserted a fully-qualified `clojure.string/join` into a
#   namespace with no clojure.string require, which clj-kondo then rejected
#   outright. So splint is never run in fix mode here, by the pre-push hook or
#   anyone else.
#   What remains needs JUDGEMENT, and some of it would be wrong to "fix":
#   the lint/catch-throwable hits sit in sweep/fuzz tests where catching Throwable
#   is the point — narrowing to Exception lets a StackOverflowError from deep
#   recursion escape the probe it exists to catch. Others are namespace renames
#   (naming/single-segment-namespace, naming/lisp-case) that change a public
#   surface to satisfy a style rule. So splint stays a tool you RUN, never a
#   gate that blocks: it is a source of suggestions to weigh, and a gate whose
#   findings you must sometimes ignore is a gate people learn to ignore.
splint-clj:
	@printf '\033[32m[splint-clj]\033[0m splint (report-only; not part of `lint`)\n'
	@$(CLJ) -M:splint $(LINT_CLJ_PATHS)

## lint-sh: parse check + payload-apostrophe check over hand-authored shell
# THIS ONE HAS EARNED ITS PLACE. generate-protos.sh builds most of its work as
# SINGLE-QUOTED `bash -c` payloads, so one bare apostrophe inside a comment
# terminates the payload and the rest of the comment executes as shell. That
# exact typo broke binding generation for four consecutive CI runs, and was
# then reintroduced the SAME DAY, in the SAME FILE, by the person who had just
# fixed it and written a warning comment about it — because a warning comment
# is not a gate. `bash -n` catches it in milliseconds, needs no new dependency,
# and would have caught both occurrences before the push.
#
# The fork lifecycle gate's own canary suite. It rides `lint` rather than the
# renderer battery because it touches no rendered surface and needs no container
# — and because a gate whose canaries are never RUN is a gate nobody has checked
# since the day it landed. The suite asserts each brief-check clause fires for
# ITS OWN reason (a FAIL, not an ERROR), which is the property that separates a
# real refusal from a neighbouring clause that would also have refused.
# The two structural traps a fork LIFT re-introduces, both mechanical and both
# recurring inside one ten-fork wave: tracked files under the per-fork scratch
# directory (a cherry-pick carries them past the .gitignore that guards
# authoring), and a dev/proof script whose repo root does not resolve from where
# it now sits (a lift moves nearly every probe a fork writes, and the resulting
# red is indistinguishable from a caught defect). Each clause is mutation-proven
# to fire alone; see the header of the script.
.PHONY: fork-hazards
fork-hazards:
	@bash tools/claude/fork-hazards.sh

brief-check-test:
	@bash tools/claude/brief_check_test.sh

# The other half of the fork lifecycle: whether `release` can DELETE a fork at
# all. Rides `lint` for the same reasons brief-check-test does — no container,
# no rendered surface, and a gate whose canaries are never run is a gate nobody
# has checked since it landed.
#
# ITS PERMISSION CANARIES NEED A NON-ROOT UID. An unwritable directory does not
# refuse root, so as root the suite reports UNJUDGED, declines to print ALL
# GREEN, and exits 0 rather than blocking a container run. The pre-push hook is
# this target's live caller and runs as the developer, which is where those
# canaries are judged. `tools/uber.sh 'make -f lint.mk lint'` reaches this
# target — it is ordered before lint-clj, not after — and would report them
# UNJUDGED rather than judged; that aggregate then fails on lint-clj anyway,
# for the reason this file's header gives.
.PHONY: forks-release-test
forks-release-test:
	@bash tools/claude/forks_release_test.sh

# generate-protos.sh's LEG STRICTNESS preamble — the one line that decides whether
# a language leg can fail on the LEFT of a pipe. Every payload re-arms a bare
# `set -e`, which clears neither -u nor pipefail, so the prepend at the dispatcher
# reaches all eleven legs. It rides `lint` for the same reasons the two above do:
# no rendered surface, and its cases are hermetic bash over synthetic payloads.
#
# It asserts BOTH directions and attributes the red: the mutant (pipefail silenced)
# goes green while nounset STILL REFUSES on that same mutant — a control that merely
# stayed green would be satisfied by a dead shell.
.PHONY: leg-strictness-test
leg-strictness-test:
	@bash tools/leg_strictness_test.sh

# tools/uber.sh's chown-back — the line that decides whether root-owned residue
# in a checkout is REPORTED or silent. It rides `lint` for the same reasons the
# two above do: no rendered surface, and its hermetic cases need nothing but
# bash, coreutils and a stub `docker` on PATH.
#
# ITS LAST CASE WANTS DOCKER AND THE PINNED IMAGE, and reports UNJUDGED without
# them rather than passing: that case is the one that runs the captured payload
# in a REAL container against the REAL /workspace, which is what closes the
# workspace-path substitution the hermetic cases make. So it is judged on a
# developer host with the image built, and UNJUDGED on a plain runner and inside
# `tools/uber.sh 'make -f lint.mk lint'` (no docker CLI in the base image). The
# suite prints three counts and refuses to say ALL GREEN while any case is
# unjudged; it exits 0 in that state rather than blocking a container run.
.PHONY: uber-chown-test
uber-chown-test:
	@bash tools/uber_chown_test.sh

# The controls.wasm CONTENT-PROVENANCE stamp and its verifier — the pair that
# answers "was this binary built from these sources", where the older build-sha
# stamp answers only "which commit was HEAD when make last ran". Those are
# different questions, and a warm object tree can move the second without moving
# the first: renderer/wasm.mk's content-stamp block records the four measured
# ways, and this suite reproduces the sharpest of them end to end and requires
# the gate to refuse it.
#
# IT RIDES `lint` RATHER THAN THE RENDERER BATTERY, for the same reasons the
# three suites above do: no rendered surface, no container, and a gate whose
# canary is never RUN is a gate nobody has checked since the day it landed. It
# needs no WASI-SDK either — the compiler is stubbed, because what is under test
# is what make DECIDES and what the recipes WRITE, never what clang emits. The
# gate ITSELF is armed separately, as `wasm-inputs-check` inside renderer.mk's
# `check-renderer`, where a real artifact exists to judge.
#
# ITS CASES ARE MUTATION-PROVEN TO FIRE ALONE. Blinding the digest to a single
# header fails the header case and nothing else; moving the sidecar write out of
# the link into an always-rerun target — the tempting wrong design, which is the
# original defect in a newer coat — fails exactly the no-op-rebuild case and the
# defect case; removing the discovery floors fails only the collapsed-root case.
.PHONY: wasm-provenance-test
wasm-provenance-test:
	@bash tools/wasm_provenance_test.sh

# Parse-only, deliberately: shellcheck is not present on the host or in the
# uber image, and adding a toolchain dependency for it is a separate decision.
# `bash -n` is the floor, not the ceiling.
lint-sh:
# NON-VACUITY GUARD. This gate's PASS value equals its NOTHING-RAN value: with an
# empty file list `xargs` runs nothing and exits 0, so a discovery failure reads
# as a clean gate. That is not hypothetical — `git ls-files` returns nothing when
# git cannot resolve the repo, which is the NORMAL state in a submodule checkout
# whose gitlink points outside the container's bind mount. The gate then printed
# "0 scripts" and passed, checking nothing at all. An empty input set is a green
# tick over zero coverage, so it must FAIL LOUD instead.
	@if [ -z "$(strip $(LINT_SH_FILES))" ]; then \
		printf '\033[31m[lint-sh] FAIL\033[0m — discovered ZERO shell scripts.\n' >&2; \
		printf '  This repo tracks shell scripts, so an empty set means DISCOVERY broke,\n' >&2; \
		printf '  not that there is nothing to check.\n' >&2; \
		if [ -n "$$LINT_SH_DISCOVERY_ERR" ]; then \
			printf '  git said: %s\n' "$$LINT_SH_DISCOVERY_ERR" >&2; \
		fi; \
		printf '  THE LINE ABOVE IS THE DIAGNOSIS, if there is one. The commonest cause\n' >&2; \
		printf '  is that git cannot resolve this checkout: in a container, a gitfile\n' >&2; \
		printf '  checkout (submodule or linked worktree) needs its real gitdir mounted,\n' >&2; \
		printf '  and tools/uber.sh does that for a self-contained gitdir. It is NOT the\n' >&2; \
		printf '  only cause — a broken core.excludesFile fails the same way — so read\n' >&2; \
		printf '  what git said before acting on this paragraph.\n' >&2; \
		exit 1; \
	fi
	@printf '\033[32m[lint-sh]\033[0m bash -n (%s cpus, %s scripts)\n' \
		"$(NPROC)" "$(words $(LINT_SH_FILES))"
	@printf '%s\n' $(LINT_SH_FILES) | xargs -P $(NPROC) -n 1 bash -n
# `bash -n` alone does NOT cover the bug the header above describes, and this
# gate claimed it did. An EVEN number of apostrophes inside a single-quoted
# payload rebalances the quoting: the parse stays valid, the check passes, and
# the payload silently becomes EMPTY — every language leg then dies at runtime.
# That is the THIRD occurrence of this bug in this file, and the first two were
# odd-count, which is why `bash -n` looked sufficient. Parity is not the
# invariant; absence is.
	@printf '\033[32m[lint-sh]\033[0m no bare apostrophe in a single-quoted payload\n'
# ONE awk call over ALL files, deliberately: the non-vacuity floor is GLOBAL.
# Most shell scripts carry no payload block, so a per-file floor would be wrong,
# but zero across the whole set means the opener stopped matching.
	@awk -f tools/payload_apostrophes.awk $(LINT_SH_FILES)
# `git -C` is a TRAP in anything reachable from tools/uber.sh. That script
# exports GIT_DIR=/gitdir and GIT_WORK_TREE=/workspace so a BARE git resolves
# this submodule checkout inside the container (the guard above depends on it),
# and GIT_DIR BEATS -C: `GIT_DIR=<A> git -C <B> rev-parse HEAD` answers about A,
# exit 0, plausible sha, no error. Verified live.
#
# So inside that container a bare `git` is CORRECT and `git -C <other-repo>` is
# silently WRONG — it reports the wrong repository while looking authoritative.
# renderer/wasm.mk's provenance stamp is the live stake: it records the commit
# controls.wasm was built from, and consumers compare that against their
# gitlink. A `-C` there would stamp the wrong sha, and a wrong stamp is worse
# than none — it reads as proof while being false.
#
# The escape hatch when another repo genuinely must be addressed is to drop the
# inherited environment explicitly: `env -u GIT_DIR -u GIT_WORK_TREE git -C …`
# (uber.sh already defends its own host side that way).
	@if bad=$$(grep -nE '(^|[^-])\bgit +-C\b' $(LINT_SH_FILES) renderer/wasm.mk renderer.mk Makefile lint.mk 2>/dev/null \
		| grep -v 'env -u GIT_DIR' \
		| grep -vE ':[0-9]+:[[:space:]]*#' \
		| grep -vE 'printf|echo '); then 		printf '\033[31m[lint-sh] FAIL\033[0m — bare `git -C` reachable from uber.sh:\n' >&2; 		printf '%s\n' "$$bad" >&2; 		printf '  GIT_DIR overrides -C in that container, so this answers about the\n' >&2; 		printf '  WRONG repo. Use: env -u GIT_DIR -u GIT_WORK_TREE git -C <repo> …\n' >&2; 		exit 1; 	fi
	@printf '\033[32m[lint-sh]\033[0m no bare `git -C` (GIT_DIR would override it)\n'

## lint-ci: actionlint over the GitHub Actions workflows
#
# WHY THIS GATE EXISTS AT ALL: a broken workflow does not fail loudly. It fails
# by NOT RUNNING, which reads exactly like CI being green — and this repo's
# release workflow fans out to ten consumer repositories behind path filters, so
# a workflow that silently stops firing is a distribution outage nobody sees.
#
# SHELLCHECK IS DISABLED HERE DELIBERATELY, and the split is what makes this
# adoptable. actionlint embeds shellcheck over every `run:` block; on this tree
# that reports style/info findings (SC2035, SC2129, SC2012) while actionlint's
# OWN checks — syntax, expression errors, action references, matrix shapes —
# report ZERO. Wiring the two together would put a clean syntax gate behind a
# pile of shell style findings, and it would never land. The shell half belongs
# with the shell lane, over whole scripts, where its findings can be
# dispositioned as a set.
#
# COMPOSITE ACTIONS ARE NOT COVERED, and cannot be by this tool: actionlint
# parses whatever it is handed as a WORKFLOW, so `.github/actions/*/action.yml`
# yields false `"jobs" section is missing` / `"on" section is missing` errors.
# Measured, not assumed. Discovery below therefore names workflows only, and the
# uncovered action file is a stated residual rather than an oversight.
# `--cached --others --exclude-standard`, the same widening lint-sh carries and
# for the same measured reason: the INDEX ALONE gives an author who has written a
# new workflow and not staged it a GREEN THAT NEVER READ IT. That is not
# hypothetical here — this lane reported "clean over 6 workflow(s)" on a tree that
# held 7, because the seventh had not been staged yet, and the one class of file
# whose breakage is INVISIBLE (a broken workflow does not fail, it stops running)
# was the class going unchecked. Ignored paths stay out, which keeps it free.
LINT_CI_FILES := $(shell git ls-files --cached --others --exclude-standard \
	'.github/workflows/*.yml' '.github/workflows/*.yaml' 2>/dev/null)

.PHONY: lint-ci
lint-ci:
# MISSING-TOOL GUARD. Without it a bare `actionlint` dies `make: actionlint: No
# such file or directory` with Error 127 — an ERROR wearing this gate's colour,
# naming the wrong problem (a broken gate rather than a missing dependency) and
# carrying no way to fix it. `.claude/rules/gate-enforcement.md` §4 requires the
# classification to happen at the seam where the tool is resolved, which is here.
	@command -v actionlint >/dev/null 2>&1 || { \
		printf '\033[31m[lint-ci] CANNOT RUN\033[0m — actionlint is not on PATH.\n' >&2; \
		printf '  A broken workflow does not fail loudly, it stops RUNNING — which reads\n' >&2; \
		printf '  exactly like CI being green. So this lane may not be skipped.\n' >&2; \
		printf '  It is a single static binary; CI installs it in\n' >&2; \
		printf '  .github/workflows/lint.yml, pinned. Take the version from there:\n' >&2; \
		printf '    curl -fsSL https://github.com/rhysd/actionlint/releases/download/\n' >&2; \
		printf '      v<VER>/actionlint_<VER>_linux_amd64.tar.gz | tar -xz actionlint\n' >&2; \
		exit 3; \
	}
# NON-VACUITY GUARD, the same class lint-sh carries. Bare `actionlint` discovers
# its own files and exits 0 when it finds none, so a discovery failure reads as
# a clean gate. Discovery is explicit here precisely so it can be guarded.
	@if [ -z "$(strip $(LINT_CI_FILES))" ]; then \
		printf '\033[31m[lint-ci] CANNOT RUN\033[0m — discovered ZERO workflow files.\n' >&2; \
		printf '  This repo tracks GitHub Actions workflows, so an empty set means\n' >&2; \
		printf '  DISCOVERY broke, not that there is nothing to check. The commonest\n' >&2; \
		printf '  cause is git being unable to resolve this checkout — see lint-sh.\n' >&2; \
		exit 3; \
	fi
# TOOL-PRESENCE GUARD. actionlint is REQUIRED, and without this the recipe invoked a
# missing binary and make reported `actionlint: No such file or directory` followed by
# `Error 127` — which names the tool but nothing else: not that it is required rather
# than optional, not where the gate expects to find it, not how to get it. This gate
# is reached from the host-side `lint` aggregate, and actionlint is NOT part of any
# host toolchain here, so a developer meets that 127 on a clean machine the first time
# they push. It is a HARD FAIL and not a skip: skipping a check because its tool is
# absent is a bypass vector (fail-fast.md), and a workflow syntax error would then
# reach CI unjudged.
	@command -v actionlint >/dev/null 2>&1 || { \
		printf '\033[31m[lint-ci] FAIL\033[0m — actionlint is REQUIRED and not on PATH.\n' >&2; \
		printf '  This gate judges workflow syntax; skipping it would let a broken\n' >&2; \
		printf '  workflow reach CI unchecked, so it fails rather than passing.\n' >&2; \
		printf '  Install the pinned version (same one .github/workflows/lint.yml uses):\n' >&2; \
		printf '    curl -fsSL https://github.com/rhysd/actionlint/releases/download/v1.7.10/actionlint_1.7.10_linux_amd64.tar.gz \\\n' >&2; \
		printf '      | tar -xz -C ~/.local/bin actionlint\n' >&2; \
		exit 1; \
	}
	@actionlint -shellcheck= $(LINT_CI_FILES)
	@printf '\033[32m[lint-ci]\033[0m actionlint clean over %s workflow(s)\n' "$(words $(LINT_CI_FILES))"

## fmt-c: clang-format DRIFT check over hand-authored C, one process per cpu
# NOT `--dry-run --Werror`. That mode DISAGREES with `-i` under .clang-format's
# MaxEmptyLinesToKeep:0 + SeparateDefinitionBlocks:Always pairing: after `-i`
# has converged, --dry-run still reports edits it will never make, and the
# count is stable across repeated -i passes (reproduce:
# `clang-format --style=file --dry-run renderer/src/renderer.c` on a tree where
# `make -f lint.mk fmt-c` is green). A
# gate built on it fails forever on a correctly-formatted tree — which is
# exactly what happened here, and was misdiagnosed as the CONFIG being
# unsatisfiable before the two were told apart.
#
# Drift-compare instead: run the formatter to stdout and diff against the file.
# It asks the question the gate actually cares about — "is the committed file
# what the formatter produces?" — and it is the method sych gates C with, which
# is why the two repos can share one config byte-for-byte.
fmt-c:
# NON-VACUITY GUARD, same class as lint-sh's — and the same failure mode now
# that discovery is git's: `git ls-files` returns nothing when git cannot
# resolve the checkout, xargs then runs nothing, and the gate reports a green
# zero-file line over no coverage at all.
	@if [ -z "$(strip $(FMT_C_FILES))" ]; then \
		printf '\033[31m[fmt-c] FAIL\033[0m — discovered ZERO hand-authored C files.\n' >&2; \
		printf '  This repo tracks hand-authored C, so an empty set means DISCOVERY\n' >&2; \
		printf '  broke, not that there is nothing to format-check.\n' >&2; \
		if [ -n "$$FMT_C_DISCOVERY_ERR" ]; then \
			printf '  git said: %s\n' "$$FMT_C_DISCOVERY_ERR" >&2; \
		fi; \
		printf '  THE LINE ABOVE IS THE DIAGNOSIS, if there is one. See lint-sh for\n' >&2; \
		printf '  the usual causes — a gitfile checkout whose real gitdir is not\n' >&2; \
		printf '  mounted, or a broken core.excludesFile.\n' >&2; \
		exit 1; \
	fi
# MISSING-TOOL GUARD, and note WHICH failure it covers: not "no formatter" but
# "no formatter whose verdict means anything". clang-format's output changes across
# major versions, so an UNPINNED copy answers a different question than CI asks —
# it can report drift on a tree CI accepts, or accept a tree CI rejects. Either way
# the verdict is unreliable, and a gate must not print a clean line it cannot
# stand behind (`.claude/rules/gate-enforcement.md` §4, last paragraph: resolve the
# pinned one or drop to check-only, and SAY WHICH HAPPENED).
#
# So the unpinned case is neither silently used nor silently skipped: it runs and
# says out loud that its verdict is advisory. It is not promoted to a hard refusal
# because CI is the authoritative half and DOES resolve the pinned binary — this
# lane failing closed on every developer without the WASI-SDK would block pushes
# on a verdict CI is about to make correctly anyway.
	@if [ "$(CLANG_FORMAT)" = "clang-format" ]; then \
		command -v clang-format >/dev/null 2>&1 || { \
			printf '\033[31m[fmt-c] CANNOT RUN\033[0m — no clang-format at /opt/wasi-sdk/bin and none on PATH.\n' >&2; \
			printf '  The PINNED one ships with the WASI-SDK, so the correct entry point is\n' >&2; \
			printf "    tools/uber.sh 'make -f lint.mk fmt-c'\n" >&2; \
			exit 3; \
		}; \
		printf '\033[33m[fmt-c]\033[0m ADVISORY — using an UNPINNED clang-format (%s).\n' \
			"$$(command -v clang-format)" >&2; \
		printf '  Its output differs across major versions, so a red here may not be CI'"'"'s\n' >&2; \
		printf '  verdict and a green here does not establish CI'"'"'s. The pinned binary lives\n' >&2; \
		printf '  in the WASI-SDK: tools/uber.sh '"'"'make -f lint.mk fmt-c'"'"'\n' >&2; \
	fi
	@printf '\033[32m[fmt-c]\033[0m %s drift-compare (%s cpus, %s files)\n' \
		"$(CLANG_FORMAT)" "$(NPROC)" "$(words $(FMT_C_FILES))"
	@printf '%s\n' $(FMT_C_FILES) \
		| xargs -P $(NPROC) -I{} sh -c \
		'$(CLANG_FORMAT) --style=file "$$1" | diff -u "$$1" - > /dev/null \
		 || { echo "clang-format drift: $$1" >&2; exit 1; }' _ {}

## lint-sh-shellcheck: shellcheck over first-party shell, at ERROR severity
# CONTAINER-ONLY, and deliberately NOT in the `lint` aggregate — same reasoning
# as lint-c-tidy above. `lint` runs on a plain runner whose shellcheck version is
# whatever the runner image happens to ship, and a gate whose findings depend on
# an unpinned tool is not reproducible. The pinned one is in Dockerfile.base
# (SHELLCHECK_VERSION, checksum-verified), so the lane runs where that pin lives.
#
# -S error is the ENTRY of a ratchet, not the destination. Measured over the 45
# first-party scripts at adoption: 1 error, 21 warnings, 66 notes. The single
# error (SC1087, an unbraced $body followed by a literal bracket) was fixed in
# the same change that added this lane, so the gate lands GREEN and every future
# error is a real regression. Raising to -S warning is 21 fixes and is the next
# rung; do not lower it.
#
# A missing tool HARD-FAILS with the container hint rather than skipping: a
# skipped check reports the same green as a clean one.
.PHONY: lint-sh-shellcheck
lint-sh-shellcheck:
	@command -v shellcheck >/dev/null 2>&1 || { \
		printf '\033[31m[lint-sh-shellcheck]\033[0m cannot run: shellcheck is not on PATH.\n' >&2; \
		printf '  It is pinned in Dockerfile.base, so run this in the image:\n' >&2; \
		printf '  container: tools/uber.sh '\''make -f lint.mk lint-sh-shellcheck'\''\n' >&2; exit 1; }
# NON-VACUITY GUARD, for the same reason lint-sh carries one: with an empty file
# list shellcheck checks nothing and exits 0, which is indistinguishable from a
# clean tree. Discovery through `git ls-files` is exactly what breaks inside a
# container that cannot resolve the checkout, so this is the live failure mode.
	@if [ -z "$(strip $(LINT_SC_FILES))" ]; then \
		printf '\033[31m[lint-sh-shellcheck] FAIL\033[0m — discovered ZERO first-party scripts.\n' >&2; \
		printf '  This repo tracks shell scripts, so an empty set means DISCOVERY broke.\n' >&2; \
		printf '  See lint-sh above for the usual cause (git cannot resolve the checkout).\n' >&2; \
		exit 1; \
	fi
	@printf '\033[32m[lint-sh-shellcheck]\033[0m %s (%s first-party scripts, -S error)\n' \
		"$$(shellcheck --version | awk '/^version:/{print $$2}')" "$(words $(LINT_SC_FILES))"
	@shellcheck -S error $(LINT_SC_FILES)

## lint-c-tidy: clang-tidy static analysis over hand-authored C
# CONTAINER-ONLY (like fmt-c): needs the pinned WASI-SDK clang-tidy AND a
# compile database. The DB is emitted by the build itself
# (`make -f wasm.mk compile-db`) from the build's own flags, so clang-tidy sees
# exactly what the compiler sees — a hand-assembled flag list silently diverges
# and invents diagnostics (measured: three phantom parse errors from a missing
# -std=c23). The config is renderer/.clang-tidy (WarningsAsErrors:'*'), adopted
# from the fleet's jettison config; run-clang-tidy exits non-zero on any finding.
#
# NOT in the `lint` aggregate: `lint` is the fast host-runnable gate the
# pre-push hook calls, and this needs docker. It runs in CI's renderer job,
# which already has the toolchain image and builds the compile DB there.
lint-c-tidy:
	@command -v $(RUN_CLANG_TIDY) >/dev/null 2>&1 || { \
		printf '\033[31m[lint-c-tidy]\033[0m cannot run: no run-clang-tidy at /opt/wasi-sdk/bin and none on PATH.\n' >&2; \
		printf '  The pinned one ships with the WASI-SDK, so run this inside the toolchain\n' >&2; \
		printf '  container: tools/uber.sh '\''make -f lint.mk lint-c-tidy'\''\n' >&2; exit 1; }
	@printf '\033[32m[lint-c-tidy]\033[0m %s (%s cpus)\n' "$(RUN_CLANG_TIDY)" "$(NPROC)"
# ALWAYS REGENERATE, never `[ -f … ] ||`. The guard this replaces REUSED a stale
# database: edit WARN_FLAGS or APP_STD and clang-tidy keeps analysing under the
# PREVIOUS flags, silently answering a question about a build that no longer
# exists. That is the same class as a gate reporting clean over code it never
# read, and the reason the DB must come from the build's own variables in the
# first place. Regeneration is a few make-variable expansions into a JSON file —
# cheaper than the risk it removes.
	@$(MAKE) -C renderer -f wasm.mk compile-db
	@cd renderer && $(RUN_CLANG_TIDY) -clang-tidy-binary $(CLANG_TIDY) -p . -quiet -j $(NPROC)

## lint-c-tidy-test: the canary for lint-c-tidy's SIX-AXIS SIZE CHECK
# WHAT IT PROVES. `readability-function-size` is one check with six thresholds,
# so a red naming it attributes nothing to any one axis. The suite tightens ONE
# axis to 1, relaxes the other five to 9999, and requires the lane to refuse with
# THAT axis's own note while all five neighbours stay silent — for every axis,
# not one. It then relaxes all six together and requires the lane GREEN, which is
# what rules out a lane that refuses everything, and runs the tracked config
# unmutated, which is the other direction the aggregate would otherwise never
# see. That mutation shape is not invented here: it is the experiment the six
# thresholds were originally MEASURED with, kept runnable instead of surviving
# only as a hand-executed sequence in a commit message.
#
# HERMETIC. The lane hardcodes `cd renderer` and regenerates the compile database
# from that directory's own variables, so it cannot be pointed at a config
# elsewhere — but it CAN be pointed at a copy of the tree, which is what the
# suite does. Nothing tracked is written, and the suite byte-compares
# renderer/.clang-tidy at the end rather than claiming it in prose.
#
# NOT IN THE `lint` AGGREGATE, for the SAME mechanical reason lint-c-tidy is not:
# it needs run-clang-tidy from the pinned WASI-SDK, and `lint` is invoked BARE by
# the hook, so folding it in would hard-fail every push from a machine without
# the image. Note this is the OPPOSITE conclusion to wire-contract-codec-test,
# which rides `lint` even though its own gate does not — and it is the same
# argument, not a different one: that canary needs nothing the other lanes need,
# and this one needs exactly what its gate needs. It is wired beside the gate in
# .githooks/pre-push's docker-gated block instead, canary FIRST, in the same
# container invocation.
#
# THE CI HALF IS OWED. A hook-only gate is armed for whoever armed the hook and
# nobody else (`.claude/rules/gate-enforcement.md` §6 wants both). The CI home is
# the renderer job that already runs `lint-c-tidy` in the pinned image.
#
# IT COSTS WHAT THE GATE COSTS, times eight — one lane run per axis, plus the
# relaxed control and the baseline. Measured at roughly 6 s per run on a 32-core
# host. Run the suite alone to read its output cleanly; the cases are sequential
# and each prints its own line.
lint-c-tidy-test:
	@bash tools/lint/test/c_tidy_size_test.sh

## fmt-fix: rewrite formatting in place (both languages)
fmt-fix: fmt-clj-fix fmt-c-fix

# LINT_CLJ_FILES RIDES HERE TOO, and its absence was a real gap rather than a
# tidy-up: `fmt-clj` above CHECKS `$(LINT_CLJ_PATHS) $(LINT_CLJ_FILES)` while
# this target only ever FIXED the paths, so the one member of that list —
# tools/scratchcard/bin/scratchcard.bb, which the structural lanes cannot root
# and which therefore exists only as a FILE entry — was checked by a lane no fix
# target could satisfy. The pre-push hook's loop (apply formatters, list what
# was rewritten, block so it gets committed) cannot converge for such a file:
# `fmt-fix` leaves the drift, `fmt-clj` keeps refusing, and the printed remedy
# is the command that just declined to act. A check whose fix counterpart has a
# narrower population is a gate with no exit.
fmt-clj-fix:
	@printf '\033[32m[fmt-clj-fix]\033[0m cljfmt fix\n'
	@$(CLJ) -M:fmt fix $(LINT_CLJ_PATHS) $(LINT_CLJ_FILES)

fmt-c-fix:
	@printf '\033[32m[fmt-c-fix]\033[0m %s -i (%s cpus)\n' "$(CLANG_FORMAT)" "$(NPROC)"
	@printf '%s\n' $(FMT_C_FILES) \
		| xargs -P $(NPROC) -n 1 $(CLANG_FORMAT) --style=file -i
