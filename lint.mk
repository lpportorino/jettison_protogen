# lint.mk — format + lint gates for hand-authored Clojure and C.
#
#   make -f lint.mk lint        # the four lanes .githooks/pre-push runs
#                               # (check-only; CI runs each lane separately)
#   make -f lint.mk fmt-fix     # rewrite formatting in place
#   make -f lint.mk lint-clj    # one lane at a time
#
# WHAT IS GATED — positive allowlist, never an ignore list. Each tool is handed
# an explicit list of hand-authored paths and never walks the tree, so
# generated and vendored code is excluded by never being passed in. There is no
# exclusion pattern to drift out of sync with reality:
#   output/**               generated bindings (10 languages)
#   renderer/lvgl/**        vendored upstream, byte-exact per .gitattributes
#   renderer/generated/**   nanopb + threshold projections
#   renderer/src/font_*.c   generated LVGL font data
#   docs/proto/**           generated markdown
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
	docs/.protodoc/tools/src \
	docs/.protodoc/tools/test \
	docs/.protodoc/tools/build.clj

# THE ONE HELD-OUT TREE — on the record, never silently absent:
#
# docs/.protodoc/scripts/ — the `#!/usr/bin/env bb` slash-command backends
# (proto-search, proto-coverage, doc-next, proto-lint, patch-lint). Hand-authored
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

# Hand-authored C. `find` + explicit -not, so a NEW hand-written file is picked
# up automatically while the generated font tables stay out.
FMT_C_FILES := $(shell find renderer/src -maxdepth 1 \
	\( -name '*.c' -o -name '*.h' \) -not -name 'font_*.c' 2>/dev/null | sort)

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
LINT_SH_DISCOVERY_ARGS := --cached --others --exclude-standard '*.sh' .githooks/pre-push
LINT_SH_FILES := $(shell git ls-files $(LINT_SH_DISCOVERY_ARGS) 2>/dev/null \
	| grep -v '^renderer/lvgl/' | sort)
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

.PHONY: lint lint-clj fmt-clj splint-clj fmt-c lint-sh fmt-fix fmt-clj-fix fmt-c-fix cpus \
	install-hooks hooks-status audit-clj-paths wire-contract

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


## lint: the four check-only lanes the PRE-PUSH HOOK runs — not all of them
# WHO CALLS THIS, precisely, because the answer used to be written down wrong.
# `.githooks/pre-push` (line 86) is the SOLE caller. No CI job invokes it: CI
# runs the lanes INDIVIDUALLY — lint.yml calls `lint-sh`, `fmt-clj`, `lint-clj`
# as three steps, and renderer.yml calls `fmt-c` and `lint-c-tidy` inside the
# pinned image. So this target is LIVE and local, and the comment that called it
# "the CI entry point" pointed a reader at a caller that does not exist while
# the one that does exist depends on it — the exact reading under which someone
# deletes a target the local gate needs.
#
# NOR IS IT "every gate", and the two it omits are omitted for DIFFERENT
# reasons — which is why neither belongs in a headline that says "every":
#   lint-c-tidy    not in this aggregate and NOT in the hook either. It is
#                  container-only (it needs a compile database emitted from the
#                  build's own flags) and runs in renderer.yml alone, so it is
#                  the one lane a green local push has genuinely not run.
#   wire-contract  a different KIND of gate — a generated artifact contradicting
#                  a hand-written contract, see below — deliberately kept out of
#                  `lint` so a green `lint` keeps meaning "formatting and lint
#                  over hand-authored code". The hook calls it SEPARATELY
#                  (.githooks/pre-push:109), so the hook's gate set is wider
#                  than this target by exactly that one lane.
# A green `lint` is four lanes. Naming them is the only honest headline.
#
# lint-sh runs FIRST and cheaply: it is the gate that catches the class of bug
# that has actually taken this repo down (see below).
lint: lint-sh brief-check-test fmt-clj lint-clj fmt-c

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
	for p in $(LINT_CLJ_PATHS); do git ls-files "$$p"; done \
		| sed -n '/\.\(clj\|cljc\|bb\)$$/p' | sort > "$$g"; \
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
		printf '  Add each to LINT_CLJ_PATHS, or hold it out ON THE RECORD beside it.\n'; \
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
	@clj-kondo --parallel --cache false --fail-level warning --lint $(LINT_CLJ_PATHS)

## fmt-clj: cljfmt check (fails if any file would be rewritten)
fmt-clj:
	@printf '\033[32m[fmt-clj]\033[0m cljfmt check\n'
	@clojure -M:fmt check $(LINT_CLJ_PATHS)

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
	@clojure -M:splint $(LINT_CLJ_PATHS)

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
brief-check-test:
	@bash tools/claude/brief_check_test.sh

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
# NON-VACUITY GUARD, same class as lint-sh's: `find` yields the empty set if
# renderer/src is ever moved or renamed, xargs then runs nothing, and the gate
# reports a green zero-file line over no coverage at all.
	@if [ -z "$(strip $(FMT_C_FILES))" ]; then \
		printf '\033[31m[fmt-c] FAIL\033[0m — discovered ZERO C files under renderer/src.\n' >&2; \
		printf '  Hand-authored C is tracked there, so an empty set means the search\n' >&2; \
		printf '  path is wrong (moved or renamed tree), not that there is nothing to\n' >&2; \
		printf '  format-check.\n' >&2; \
		exit 1; \
	fi
	@printf '\033[32m[fmt-c]\033[0m %s drift-compare (%s cpus, %s files)\n' \
		"$(CLANG_FORMAT)" "$(NPROC)" "$(words $(FMT_C_FILES))"
	@printf '%s\n' $(FMT_C_FILES) \
		| xargs -P $(NPROC) -I{} sh -c \
		'$(CLANG_FORMAT) --style=file "$$1" | diff -u "$$1" - > /dev/null \
		 || { echo "clang-format drift: $$1" >&2; exit 1; }' _ {}

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
	@[ -f renderer/compile_commands.json ] || $(MAKE) -C renderer -f wasm.mk compile-db
	@cd renderer && $(RUN_CLANG_TIDY) -clang-tidy-binary $(CLANG_TIDY) -p . -quiet -j $(NPROC)

## fmt-fix: rewrite formatting in place (both languages)
fmt-fix: fmt-clj-fix fmt-c-fix

fmt-clj-fix:
	@printf '\033[32m[fmt-clj-fix]\033[0m cljfmt fix\n'
	@clojure -M:fmt fix $(LINT_CLJ_PATHS)

fmt-c-fix:
	@printf '\033[32m[fmt-c-fix]\033[0m %s -i (%s cpus)\n' "$(CLANG_FORMAT)" "$(NPROC)"
	@printf '%s\n' $(FMT_C_FILES) \
		| xargs -P $(NPROC) -n 1 $(CLANG_FORMAT) --style=file -i
