# lint.mk — format + lint gates for hand-authored Clojure and C.
#
#   make -f lint.mk lint        # every gate (check-only; what CI runs)
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
#                      reach it. CI pins it via clj-kondo/setup-clj-kondo (the
#                      devcards workflow already does). This is why
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

# Hand-authored shell, from git's own index so a new script is picked up the
# moment it is tracked. renderer/lvgl/** is vendored and excluded.
#
# git's stderr is CAPTURED rather than discarded: when discovery fails, the
# reason ("not a git repository: …") is the whole diagnosis, and lint-sh's
# guard prints it instead of guessing. Discarding it is what let a broken
# discovery read as an empty-but-fine file list.
LINT_SH_FILES := $(shell git ls-files '*.sh' .githooks/pre-push 2>/dev/null \
	| grep -v '^renderer/lvgl/' | sort)
LINT_SH_DISCOVERY_ERR := $(shell git ls-files '*.sh' .githooks/pre-push 2>&1 >/dev/null)

.PHONY: lint lint-clj fmt-clj splint-clj fmt-c lint-sh fmt-fix fmt-clj-fix fmt-c-fix cpus \
	install-hooks hooks-status audit-clj-paths

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


## lint: every gate, check-only — the CI entry point
# lint-sh runs FIRST and cheaply: it is the gate that catches the class of bug
# that has actually taken this repo down (see below).
lint: lint-sh fmt-clj lint-clj fmt-c

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
#   522 findings raw. 467 of them (89%) are lint/prefer-method-values, whose
#   suggestion is the literal placeholder `(CLASS/.method …)` because splint
#   cannot infer the class — nothing to copy, nothing to autocorrect, and
#   adopting the 1.12 form repo-wide changes how each call site resolves. That
#   is a house-style migration to decide on its own terms, so it is disabled in
#   .splint.edn with that reasoning, leaving 55 real findings.
#   `--autocorrect` clears 29 of the 55 — and its output does not survive our
#   own gates. Measured on this tree it dropped a load-bearing comment, blew
#   readable `str` calls out to one argument per line, flattened hand-aligned
#   map literals, and inserted a fully-qualified `clojure.string/join` into a
#   namespace with no clojure.string require, which clj-kondo then rejected
#   outright. So splint is never run in fix mode here, by the pre-push hook or
#   anyone else.
#   The 55 that remain need JUDGEMENT, and some would be wrong to "fix":
#   all 4 lint/catch-throwable sit in sweep/fuzz tests where catching Throwable
#   is the point — narrowing to Exception lets a StackOverflowError from deep
#   recursion escape the probe it exists to catch. Others are namespace renames
#   (naming/single-segment-namespace, naming/lisp-case) that change a public
#   surface to satisfy a style rule. So splint stays a tool you RUN, never a
#   gate that blocks: it is a source of suggestions to weigh, and a gate whose
#   findings you must sometimes ignore is a gate people learn to ignore.
splint-clj:
	@printf '\033[32m[splint-clj]\033[0m splint (report-only; not part of `lint`)\n'
	@clojure -M:splint $(LINT_CLJ_PATHS)

## lint-sh: `bash -n` parse check over every hand-authored shell script
# THIS ONE HAS EARNED ITS PLACE. generate-protos.sh builds most of its work as
# SINGLE-QUOTED `bash -c` payloads, so one bare apostrophe inside a comment
# terminates the payload and the rest of the comment executes as shell. That
# exact typo broke binding generation for four consecutive CI runs, and was
# then reintroduced the SAME DAY, in the SAME FILE, by the person who had just
# fixed it and written a warning comment about it — because a warning comment
# is not a gate. `bash -n` catches it in milliseconds, needs no new dependency,
# and would have caught both occurrences before the push.
#
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
		if [ -n "$(strip $(LINT_SH_DISCOVERY_ERR))" ]; then \
			printf '  git said: %s\n' '$(LINT_SH_DISCOVERY_ERR)' >&2; \
		fi; \
		printf '  git must be able to resolve this checkout. In a container, a gitfile\n' >&2; \
		printf '  checkout (submodule or linked worktree) needs its real gitdir mounted;\n' >&2; \
		printf '  tools/uber.sh does that for a self-contained gitdir.\n' >&2; \
		exit 1; \
	fi
	@printf '\033[32m[lint-sh]\033[0m bash -n (%s cpus, %s scripts)\n' \
		"$(NPROC)" "$(words $(LINT_SH_FILES))"
	@printf '%s\n' $(LINT_SH_FILES) | xargs -P $(NPROC) -n 1 bash -n
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
	@if bad=$$(grep -nE '(^|[^-])\bgit +-C\b' $(LINT_SH_FILES) renderer/wasm.mk lint.mk 2>/dev/null \
		| grep -v 'env -u GIT_DIR' \
		| grep -vE ':[0-9]+:[[:space:]]*#' \
		| grep -vE 'printf|echo '); then 		printf '\033[31m[lint-sh] FAIL\033[0m — bare `git -C` reachable from uber.sh:\n' >&2; 		printf '%s\n' "$$bad" >&2; 		printf '  GIT_DIR overrides -C in that container, so this answers about the\n' >&2; 		printf '  WRONG repo. Use: env -u GIT_DIR -u GIT_WORK_TREE git -C <repo> …\n' >&2; 		exit 1; 	fi
	@printf '\033[32m[lint-sh]\033[0m no bare `git -C` (GIT_DIR would override it)\n'

## fmt-c: clang-format DRIFT check over hand-authored C, one process per cpu
# NOT `--dry-run --Werror`. That mode DISAGREES with `-i` under .clang-format's
# MaxEmptyLinesToKeep:0 + SeparateDefinitionBlocks:Always pairing: after `-i`
# has converged, --dry-run still reports edits it will never make (measured:
# 130 on renderer.c, 18 on theme.c, unchanged across repeated -i passes). A
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
	@command -v run-clang-tidy >/dev/null 2>&1 || { \
		printf '\033[31m[lint-c-tidy]\033[0m run-clang-tidy not on PATH — run inside tools/uber.sh\n' >&2; exit 1; }
	@printf '\033[32m[lint-c-tidy]\033[0m clang-tidy (%s cpus)\n' "$(NPROC)"
	@[ -f renderer/compile_commands.json ] || $(MAKE) -C renderer -f wasm.mk compile-db
	@cd renderer && run-clang-tidy -p . -quiet -j $(NPROC)

## fmt-fix: rewrite formatting in place (both languages)
fmt-fix: fmt-clj-fix fmt-c-fix

fmt-clj-fix:
	@printf '\033[32m[fmt-clj-fix]\033[0m cljfmt fix\n'
	@clojure -M:fmt fix $(LINT_CLJ_PATHS)

fmt-c-fix:
	@printf '\033[32m[fmt-c-fix]\033[0m %s -i (%s cpus)\n' "$(CLANG_FORMAT)" "$(NPROC)"
	@printf '%s\n' $(FMT_C_FILES) \
		| xargs -P $(NPROC) -n 1 $(CLANG_FORMAT) --style=file -i
