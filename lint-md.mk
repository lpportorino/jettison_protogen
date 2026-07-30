# lint-md.mk — deterministic quality gates over hand-authored MARKDOWN.
#
#   make -f lint-md.mk lint-md        # the gate (check-only)
#   make -f lint-md.mk lint-md-test   # the gate's own canaries
#   make -f lint-md.mk lint-md-all    # both, canaries FIRST
#
# WHY A SEPARATE MAKEFILE. `lint.mk` is one file over 600 lines carrying the
# Clojure, C, shell and workflow lanes; this lane's subject is a different
# artifact class with its own discovery, its own exemption store and its own
# canary suite. It is wired INTO `lint.mk`'s aggregate by two sub-make targets
# (see WIRING below) rather than folded in, which keeps a `git log` on markdown
# policy from being a `git log` on every lane at once.
#
# WHAT IT NEEDS: `clojure` (which needs a JDK), `bash` and `git`. NOT python3 —
# the gate was ported from a stdlib-only python3 script to `lint-gate.md` for one
# reason, and it is the reason this lane exists at all: NOTHING in this repo lints
# python. `bash -n` plus a payload-apostrophe check cover shell, cljfmt and
# clj-kondo cover Clojure, clang-format and clang-tidy cover C, actionlint covers
# workflows. A `.py` gate was therefore the least gated code in the tree — the
# thing enforcing the quality bar sitting outside every lane that enforces it.
# Under `LINT_CLJ_PATHS` the gate is now judged by cljfmt, by clj-kondo at the
# zero-warning floor, and by its own namespace-size ceiling.
#
# THAT SWAP MOVED THE TOOLCHAIN FLOOR, and the one caller it breaks is named here
# rather than left to a red run. `.github/workflows/hygiene.yml` runs both targets
# in a job that installs NOTHING and says so ("needs nothing but the bash and git
# already on the runner"), while `.github/workflows/lint.yml` installs
# `actions/setup-java` (temurin 25) plus `DeLaGuardo/setup-clojure` (cli
# 1.12.5.1654) before it may run cljfmt at all. Those two facts are read off the
# workflows; that the runner image therefore lacks the Clojure CLI is the
# inference, and it cannot be executed from a checkout. The hygiene job owes the
# same two setup steps. Failing loud rather than skipping is the correct half of
# this — a lane that went green because its tool was absent is the defect the gate
# exists to prevent.
#
# Nothing here rewrites a committed artifact, so `.claude/rules/uber-container.md`
# does not reach it — the pinned container is not required.
#
# WIRING — what `lint.mk` must add, and it cannot be added from here. Make has
# no way for an included file to append to a target another file already
# declared, and `lint.mk` owns `lint:`. The two targets below are the seam:
#
#     .PHONY: lint-md lint-md-test
#     lint-md:
#     	@$(MAKE) --no-print-directory -f lint-md.mk lint-md
#     lint-md-test:
#     	@$(MAKE) --no-print-directory -f lint-md.mk lint-md-test
#
# plus `lint-md-test lint-md` on the `lint:` prerequisite line. CANARIES FIRST,
# for the reason `lint-no-host-paths` gives for the same ordering: this gate's
# clauses can each refuse the same input, so "it went red" says nothing about
# WHICH clause refused until the suite has settled that by mutation. A sub-make
# is used rather than `include` because the default goal of a make run is the
# first target of the first file READ — an `include` placed above `lint.mk`'s own
# first target would silently retarget a bare `make -f lint.mk`.
#
# A NOTE ON PARALLELISM, and why there is none. Every other lane here saturates
# the machine because clang-format and clj-kondo are per-file processes. This
# gate is one JVM over the whole corpus in well under a second, and its
# non-vacuity floors are GLOBAL (zero code spans across the corpus is a failure;
# zero in one file is not), so splitting the corpus would break the floors to
# save nothing.

MD_GATE := tools/lint/src/lint_gate/md.clj
MD_GATE_CANARIES := tools/lint/md/test/md_gate_test.sh

.PHONY: lint-md lint-md-test lint-md-all help

## help: list the targets in this file
help:
	@grep -E '^## ' $(firstword $(MAKEFILE_LIST)) \
		| sed 's/^## //' \
		| awk 'BEGIN{FS=": "}{printf "  \033[32m%-14s\033[0m %s\n", $$1, $$2}'

## lint-md: markdown quality gate over hand-authored .md (frontmatter, spans, citations)
# THE GATE FILE ITSELF IS A PRECONDITION, checked here rather than trusted. A
# missing namespace makes the CLI exit with a `FileNotFoundException` for
# `lint_gate/md.clj`, which is the same colour as this gate's own ERROR and points
# at the wrong thing; and if the recipe were ever written to tolerate it, a renamed
# gate would read as a clean lane. Same class of guard as `lint-c-tidy`'s
# `command -v` check.
#
# `-X` AND NOT `-M`, and this is measured rather than stylistic. `deps.edn`'s
# `:lint-gate` alias pins `:main-opts` to `lint-gate.core`, and the Clojure CLI
# CONCATENATES command-line main-opts onto an alias's rather than overriding them:
# `clojure -M:lint-gate -m lint-gate.md` reaches `lint-gate.core/-main` with
# `["-m" "lint-gate.md"]` and is refused as a usage error. `-X` ignores
# `:main-opts` entirely, so ONE alias — one home for `:extra-paths`, which is what
# keeps this gate inside `LINT_CLJ_PATHS` — dispatches both gates.
#
# Folding this into `lint-gate.core`'s `checks` map is possible and is NOT a
# drop-in; the three things it owes are named at `lint-gate.md/gate`.
lint-md:
	@command -v clojure >/dev/null 2>&1 || { \
		printf '\033[31m[lint-md] FAIL\033[0m — no clojure on PATH.\n' >&2; \
		printf '  This gate is stdlib-only Clojure driven by the CLI, the same\n' >&2; \
		printf '  footprint as fmt-clj. Install it\n' >&2; \
		printf '  (https://clojure.org/guides/install_clojure), or run it inside\n' >&2; \
		printf '  the toolchain container:\n' >&2; \
		printf "  tools/uber.sh 'make -f lint-md.mk lint-md'\n" >&2; \
		exit 1; }
	@[ -f $(MD_GATE) ] || { \
		printf '\033[31m[lint-md] FAIL\033[0m — gate source missing: %s\n' \
			"$(MD_GATE)" >&2; \
		printf '  A moved or renamed gate must not read as a clean lane.\n' >&2; \
		exit 1; }
	@clojure -X:lint-gate lint-gate.md/gate

## lint-md-test: the markdown gate's own canaries — every clause proven to fire ALONE
# A gate whose canaries are never RUN is a gate nobody has checked since the day
# it landed — the argument brief-check-test, forks-release-test and
# lint-no-host-paths-test all ride `lint` on. Each clause here is proven by
# MUTATION: break that clause's own production expression in a COPY of the source
# root, watch the same fixture go clean, and watch a neighbouring clause's fixture
# still fire. The suite asserts EXIT CODES (1 = FAIL, 2 = ERROR), so a broken
# harness cannot pass for a caught defect.
#
# NO SYNTAX FLOOR RIDES HERE ANY MORE, and its absence is the port paying off
# rather than coverage lost. The python predecessor ran `python3 -m py_compile`
# because no lane in this repo lints python and a gate that cannot parse is worse
# than one that is merely unlinted. This gate's source is Clojure under
# `LINT_CLJ_PATHS`, so `lint-clj` (clj-kondo, zero-warning floor) and `fmt-clj`
# already read it — and the suite below loads and compiles the namespace on every
# one of its ~64 invocations, so a parse error cannot reach a green run.
lint-md-test:
	@[ -f $(MD_GATE_CANARIES) ] || { \
		printf '\033[31m[lint-md-test] FAIL\033[0m — canary suite missing: %s\n' \
			"$(MD_GATE_CANARIES)" >&2; \
		printf '  An absent suite is not a passing suite.\n' >&2; \
		exit 1; }
	@bash $(MD_GATE_CANARIES)

## lint-md-all: canaries then the gate — the order `lint` should use
lint-md-all: lint-md-test lint-md
