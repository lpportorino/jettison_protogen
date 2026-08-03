#!/usr/bin/env bash
# Canary suite for tools/lint/src/lint_gate/md.clj (namespace lint-gate.md).
#
# WHAT A CANARY HERE HAS TO PROVE, and why a red is not enough. Per
# `.claude/rules/review-discipline.md`: "A REFUSAL SHOWN IS NOT A REFUSAL
# ATTRIBUTED." Several clauses in that gate would refuse many of these fixtures,
# so each case does three things:
#
#   1. POSITIVE     plant a known-bad input, require exit 1 (a FAIL — this gate's
#                   own diagnosis), and require the reported clause id to be
#                   EXACTLY the one under test. A neighbour firing instead fails
#                   the case.
#   2. MUTATION     break THAT clause's own production expression in a COPY of
#                   the gate, PROVE the mutation landed on the exact bytes (new
#                   text present, old text absent, mutant not byte-identical),
#                   and require the same fixture to go clean. That is what
#                   separates "this clause caught it" from "something caught it".
#   3. CONTROL      run a neighbouring clause's fixture against the SAME mutated
#                   copy and require it to still fire, so the mutation is proven
#                   surgical rather than a blanket disable.
#
# FAIL vs ERROR is asserted by EXIT CODE, never by colour: 1 is a finding, 2 is
# the harness breaking. A canary that accepts any non-zero cannot tell a caught
# defect from a syntax error wearing the right colour.
#
# NO GIT MUTATING COMMAND RUNS HERE — not `init`, not `add`. Fixture trees are
# plain directories and the gate's tracked-file universe is injected through
# MD_GATE_TRACKED_FROM. Consequences, stated rather than implied: the canaries do
# NOT exercise real `git ls-files` discovery, the `gitignored` citation-resolution
# arm, or the git-failure diagnosis. Those are covered by the LIVE run
# (`make -f lint-md.mk lint-md`) over this repo.
#
# THE CLOCK IS PINNED, and that is what keeps the expiry canaries able to go red.
# MD_GATE_TODAY fixes the gate's `today`, so every exemption date below is a
# LITERAL rather than an offset computed from the real date — a fixture that drifts
# with the wall clock is a fixture whose red eventually stops meaning anything. The
# gate prints a loud stderr notice whenever that seam is engaged.
#
# HOW THE GATE IS INVOKED HERE, and why it is not the shape `lint-md.mk` uses. The
# mutation step needs to run a MODIFIED COPY of the source tree, which no `deps.edn`
# alias can point at. So the suite resolves the dependency classpath ONCE with
# `clojure -Spath` and then runs `java -cp <src-or-mutant>:<deps> clojure.main -m
# lint-gate.md --check md`. `lint-md.mk` drives the same namespace through the
# `:lint-gate` alias (`clojure -X:lint-gate lint-gate.md/gate`); both funnel into
# the same `run-md!`, and `lint-md-all` runs both, so neither shape is unexercised.

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$HERE/../../../.." && pwd)
SRC_DIR="$REPO_ROOT/tools/lint/src"
GATE="$SRC_DIR/lint_gate/md.clj"
[ -f "$GATE" ] || { printf 'ERROR: gate not found at %s\n' "$GATE" >&2; exit 2; }
[ -f "$REPO_ROOT/deps.edn" ] || {
	printf 'ERROR: no deps.edn at %s — REPO_ROOT resolved wrong\n' "$REPO_ROOT" >&2
	exit 2
}
command -v clojure >/dev/null 2>&1 || {
	printf 'ERROR: no clojure on PATH; the classpath cannot be resolved\n' >&2
	exit 2
}
command -v java >/dev/null 2>&1 || {
	printf 'ERROR: no java on PATH\n' >&2
	exit 2
}

# The dependency half of the classpath, resolved ONCE. `:paths []` drops the
# project's own source roots so the src directory under test — the real one or a
# mutated copy — is the only one on the classpath, and there is no second copy of
# lint-gate.md for a `require` to find instead.
BASE_CP=$(cd -- "$REPO_ROOT" && clojure -Spath -Sdeps '{:paths []}')
case "$BASE_CP" in
	*clojure-*.jar*) ;;
	*)
		printf 'ERROR: resolved classpath carries no clojure jar: %s\n' "$BASE_CP" >&2
		exit 2
		;;
esac

# THE SHARED PRIMITIVES, from the sibling suite family. One home rather than a
# local copy: `contains` looks trivial, but the quoting inside its `case` pattern
# is the whole correctness property, and a second copy is how one of them quietly
# stops being literal. Absent, this suite could only report cases it did not run.
PRIMITIVES="$REPO_ROOT/tools/lint/test/lib_mutate.sh"
[ -f "$PRIMITIVES" ] || {
	printf 'ERROR: missing shared primitives at %s\n' "$PRIMITIVES" >&2
	exit 2
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$PRIMITIVES"

# The pinned date every exemption fixture below is written against.
PINNED_TODAY=2026-06-15

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
RC=0
OUT=""

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }

ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }

# --------------------------------------------------- the substring primitive
# Every assertion below reads a diagnosis with `contains`, so a primitive that
# always returned 0 would make each of them vacuous while this suite printed
# green. Its last case additionally forces the SIGPIPE/pipefail race the retired
# `printf … | grep -q …` form is subject to — the race that made this lane one of
# the two the parallel lint aggregate flaked on — so the reason this suite no
# longer uses that form stays proven rather than remembered.
printf '\n%s\n' "$(green 'md_gate canaries — the substring primitive and the pipe form it replaces')"
if contains_selftest "$WORK/_selftest"; then
	PASS=$((PASS + 7))
else
	bad 'the substring primitive failed its own self-test — every assertion below is void'
fi

# ---------------------------------------------------------------- fixture tree
# Every fixture tree carries a BASELINE that satisfies the gate's own floors:
# one rule, one skill, one agent, one command, at least one frontmatter block,
# one code span and one resolving citation — plus one file per declared exclusion
# rule, because a dead exclusion rule is itself a hard failure. A case then adds
# or overwrites exactly the one file that carries the defect under test.
baseline() {
	local root=$1
	rm -rf "$root"
	mkdir -p "$root/.claude/rules" "$root/.claude/skills/base-skill" \
		"$root/.claude/skills/ui-standard-review" "$root/.claude/agents" \
		"$root/.claude/commands" "$root/docs/proto" "$root/renderer/lvgl" \
		"$root/tools/devcards/docs" "$root/src"

	cat > "$root/.claude/rules/base-rule.md" <<-'MDEOF'
	# Base rule

	An unscoped rule. It cites `src/thing.clj` so the citation extractor has
	something to find, and that span is also the code span the floor needs.
	MDEOF

	cat > "$root/.claude/skills/base-skill/SKILL.md" <<-'MDEOF'
	---
	name: base-skill
	description: A baseline skill so the skill floor is satisfied.
	---

	Body.
	MDEOF

	cat > "$root/.claude/agents/base-agent.md" <<-'MDEOF'
	---
	name: base-agent
	description: A baseline agent so the agent floor is satisfied.
	model: sonnet
	---

	Body.
	MDEOF

	cat > "$root/.claude/commands/base-command.md" <<-'MDEOF'
	---
	description: A baseline command so the command floor is satisfied.
	---

	Body.
	MDEOF

	: > "$root/src/thing.clj"
	printf 'generated\n' > "$root/docs/proto/gen.md"
	printf 'generated index\n' > "$root/docs/index.md"
	printf 'vendored\n' > "$root/renderer/lvgl/vend.md"
	printf 'generated gallery\n' > "$root/tools/devcards/docs/gal.md"
	printf 'generated brief\n' > "$root/.claude/skills/ui-standard-review/STANDARD.md"
}

# The injected tracked-file universe: every regular file under the fixture root,
# minus this harness's own two control files. The shell creates the redirect
# target BEFORE `find` runs, so `.tracked` would otherwise list itself and put a
# harness artefact into the universe citations resolve against.
manifest() {
	local root=$1
	(cd -- "$root" && find . -type f \
		! -name .tracked ! -name .exemptions.edn \
		| sed 's|^\./||' | sort) > "$root/.tracked"
	printf '%s' "$root/.tracked"
}

empty_exemptions() {
	local root=$1
	printf '{:exemptions []}\n' > "$root/.exemptions.edn"
	printf '%s' "$root/.exemptions.edn"
}

# run_gate <root> [src-dir-override] [exemptions-override]
# Captures combined output in OUT and the exit status in RC. `set +e` around the
# call is load-bearing: under `set -e` a non-zero gate would abort the suite,
# which is the state every case here is trying to observe.
run_gate() {
	local root=$1
	local dir=${2:-$SRC_DIR}
	local exempt=${3:-$root/.exemptions.edn}
	set +e
	# `-XX:TieredStopAtLevel=1` is a HARNESS speed flag and changes no semantics:
	# every run here loads and compiles the namespace once and exits, so the C2
	# compiler never repays its own cost. Measured over three live-repo runs on this
	# machine: 6.33s -> 2.30s of user CPU, 1.97s -> 1.67s wall. Across the ~64
	# invocations below that is the difference between a suite you run and one you
	# skip.
	OUT=$(MD_GATE_ROOT="$root" MD_GATE_TRACKED_FROM="$root/.tracked" \
		MD_GATE_EXEMPTIONS="$exempt" MD_GATE_TODAY="$PINNED_TODAY" \
		java -XX:TieredStopAtLevel=1 -cp "$dir:$BASE_CP" \
		clojure.main -m lint-gate.md --check md 2>&1)
	RC=$?
	set -e
}

# ------------------------------------------------------------------ assertions
# expect_only <label> <clause-id>
# Requires exit 1 (FAIL, not ERROR) AND that the clause under test is the ONLY
# clause reported. Attribution, not merely a red.
expect_only() {
	local label=$1
	local clause=$2
	local others
	if [ "$RC" -ne 1 ]; then
		bad "$label: expected exit 1 (FAIL), got $RC"
		printf '%s\n' "$OUT" | sed 's/^/       | /'
		return
	fi
	if ! contains "$OUT" "$clause"; then
		bad "$label: exit 1 but clause '$clause' never named"
		printf '%s\n' "$OUT" | sed 's/^/       | /'
		return
	fi
	others=$(printf '%s\n' "$OUT" | sed -n 's/^  \([a-z][a-z-]*\) .*/\1/p' \
		| sort -u | grep -vxF "$clause" || true)
	if [ -n "$others" ]; then
		bad "$label: other clause(s) also fired — red is not attributed: $(printf '%s' "$others" | tr '\n' ' ')"
		return
	fi
	ok "$label -> $clause (sole clause, exit 1)"
}

expect_rc() {
	local label=$1
	local want=$2
	local needle=$3
	if [ "$RC" -ne "$want" ]; then
		bad "$label: expected exit $want, got $RC"
		printf '%s\n' "$OUT" | sed 's/^/       | /'
		return
	fi
	if [ -n "$needle" ] && ! contains "$OUT" "$needle"; then
		bad "$label: exit $want but message lacks '$needle'"
		printf '%s\n' "$OUT" | sed 's/^/       | /'
		return
	fi
	ok "$label (exit $want)"
}

expect_clean() {
	local label=$1
	if [ "$RC" -ne 0 ]; then
		bad "$label: expected exit 0 (clean), got $RC"
		printf '%s\n' "$OUT" | sed 's/^/       | /'
		return
	fi
	ok "$label (clean)"
}

# mutate <name> <from> <to>   ->  sets MUTANT to a mutated SOURCE DIR, 0 on success
# Copy the gate's whole source root, apply ONE surgical substitution, and PROVE it
# landed before any result from it is believed. `.claude/rules/fork-isolation.md`:
# "a mutation that did not land looks exactly like a canary that did not fire."
#
# NOT a command substitution, deliberately: `MUTANT=$(mutate …)` runs the body in
# a SUBSHELL, so its `bad` output is swallowed into the captured string and its
# `FAIL=$((FAIL+1))` is discarded when the subshell exits. A mutation harness that
# cannot report its own failure is the "broken harness wearing the right colour"
# this suite exists to rule out — it was written that way first, and this comment
# is what the fix left behind.
#
# THE SUBSTITUTION IS PURE BASH, and every operator here is LITERAL because the
# pattern is QUOTED inside the expansion: `${v//"$from"/"$to"}` matches `from`
# byte-for-byte, so a `*`, `[`, `?` or `\` inside a Clojure form is not a glob.
# Unquoted it would be. `$(cat …)` strips trailing newlines, so every capture
# appends and then removes a sentinel `X` to keep the file's exact bytes.
#
# Deliberately NOT `grep -F` for the presence checks: grep is LINE-oriented, so a
# multi-line anchor is tested as one alternative per line and the assertion
# succeeds on any surviving fragment — a mutant identical to the original whose
# green then reads as attribution while proving the opposite. `case … in *"$pat"*)`
# has no such line semantics: `*` spans newlines.
#
# ONE MUTANT DIRECTORY, REUSED. Each `clojure -Spath`-free `java -cp` run costs a
# JVM start and nothing else, but a fresh directory per clause would multiply the
# copy; no two mutants are ever needed at once.
#
# A REPLACEMENT MAY NOT CONTAIN THE THING IT REPLACES, and the helper enforces it
# rather than trusting the author. The obvious way to disable a Clojure predicate is
# to WRAP it — `(not (alive? p))` -> `(and false (not (alive? p)))` — and the
# old-text-absent check then refuses, correctly: with the original still present a
# later `count_literal` would read 2 and no reader could tell which occurrence the
# mutation meant. Break the predicate by SUBSTITUTION instead (`(not (some? p))`),
# or insert inside it. Both mutations in this suite that were first written as
# wrappers were caught here, which is the two-sided proof doing its job.
MUTANT=""
count_literal() {
	# count_literal <haystack> <needle> -> echoes the occurrence count
	local rest=$1
	local needle=$2
	local n=0
	while [ -n "$rest" ]; do
		case "$rest" in
			*"$needle"*)
				n=$((n + 1))
				rest=${rest#*"$needle"}
				;;
			*) break ;;
		esac
	done
	printf '%s' "$n"
}
mutate() {
	local name=$1
	local from=$2
	local to=$3
	local src before after written hits
	MUTANT="$WORK/mutant"
	rm -rf "$MUTANT"
	mkdir -p "$MUTANT"
	cp -R -- "$SRC_DIR/." "$MUTANT/"
	src="$MUTANT/lint_gate/md.clj"
	if [ ! -f "$src" ]; then
		bad "mutation '$name': copied source root has no lint_gate/md.clj"
		return 1
	fi
	before=$(cat -- "$src"; printf X)
	before=${before%X}
	hits=$(count_literal "$before" "$from")
	if [ "$hits" -ne 1 ]; then
		bad "mutation '$name': target appears $hits time(s), need exactly 1 — no result from it is believable"
		return 1
	fi
	after=${before//"$from"/"$to"}
	printf '%s' "$after" > "$src"
	written=$(cat -- "$src"; printf X)
	written=${written%X}
	case "$written" in
		*"$to"*) ;;
		*)
			bad "mutation '$name': did not land — new text absent from the mutant"
			return 1
			;;
	esac
	case "$written" in
		*"$from"*)
			bad "mutation '$name': incomplete — old text still present in the mutant"
			return 1
			;;
	esac
	if [ "$written" = "$before" ]; then
		bad "mutation '$name': mutant is byte-identical to the original"
		return 1
	fi
	return 0
}

# clause_case <clause> <fixture-fn> <mutation-from> <mutation-to> <control-clause> <control-fixture-fn>
# The full three-step proof for one clause.
clause_case() {
	local clause=$1
	local root=$2
	local mfrom=$3
	local mto=$4
	local ctl_clause=$5
	local ctl=$6
	local copy

	run_gate "$root"
	expect_only "positive[$clause]" "$clause"

	if ! mutate "$clause" "$mfrom" "$mto"; then
		return
	fi
	copy=$MUTANT
	run_gate "$root" "$copy"
	if [ "$RC" -eq 0 ]; then
		ok "mutation[$clause]: clause disabled -> same fixture goes clean"
	else
		bad "mutation[$clause]: fixture still red after disabling the clause (rc=$RC) — the original red came from somewhere else"
		printf '%s\n' "$OUT" | sed 's/^/       | /'
	fi

	run_gate "$ctl" "$copy"
	if [ "$RC" -eq 1 ] && contains "$OUT" "$ctl_clause"; then
		ok "control[$clause]: neighbour '$ctl_clause' still fires under the mutation"
	else
		bad "control[$clause]: neighbour '$ctl_clause' stopped firing (rc=$RC) — the mutation was not surgical"
		printf '%s\n' "$OUT" | sed 's/^/       | /'
	fi
}

# ============================================================ clause fixtures
fx_fm_unterminated() {
	baseline "$1"
	cat > "$1/.claude/rules/broken-fm.md" <<-'MDEOF'
	---
	description: frontmatter that never closes
	paths:
	  - "src/**"

	# Body that the parser will swallow as YAML
	MDEOF
}

fx_fm_unparsed() {
	baseline "$1"
	cat > "$1/.claude/rules/odd-fm.md" <<-'MDEOF'
	---
	description: a rule whose frontmatter carries a shape the subset cannot read
	  folded continuation line with no key
	---

	Body cites `src/thing.clj`.
	MDEOF
}

fx_paths_not_list() {
	baseline "$1"
	cat > "$1/.claude/rules/scalar-paths.md" <<-'MDEOF'
	---
	description: paths as a bare scalar instead of a list
	paths: src/**
	---
	<!-- LOAD-TEST: scalar-paths -->

	Body cites `src/thing.clj`.
	MDEOF
}

fx_paths_glob_dead() {
	baseline "$1"
	cat > "$1/.claude/rules/dead-glob.md" <<-'MDEOF'
	---
	description: a scope glob that matches nothing in the tree
	paths:
	  - "no/such/tree/**"
	---
	<!-- LOAD-TEST: dead-glob -->

	Body cites `src/thing.clj`.
	MDEOF
}

fx_paths_match_all() {
	baseline "$1"
	cat > "$1/.claude/rules/match-all.md" <<-'MDEOF'
	---
	description: a scope that matches everything
	paths:
	  - "**/*"
	---
	<!-- LOAD-TEST: match-all -->

	Body cites `src/thing.clj`.
	MDEOF
}

fx_load_test_missing() {
	baseline "$1"
	cat > "$1/.claude/rules/no-sentinel.md" <<-'MDEOF'
	---
	description: a path-scoped rule with no LOAD-TEST sentinel
	paths:
	  - "src/**"
	---

	Body cites `src/thing.clj`.
	MDEOF
}

fx_load_test_mismatch() {
	baseline "$1"
	cat > "$1/.claude/rules/wrong-sentinel.md" <<-'MDEOF'
	---
	description: a sentinel copied from another rule
	paths:
	  - "src/**"
	---
	<!-- LOAD-TEST: some-other-rule -->

	Body cites `src/thing.clj`.
	MDEOF
}

fx_scope_prose() {
	baseline "$1"
	cat > "$1/.claude/rules/scope-both.md" <<-'MDEOF'
	---
	description: paths frontmatter AND a prose scope block
	paths:
	  - "src/**"
	---
	<!-- LOAD-TEST: scope-both -->

	**Scope:** the src tree.

	Body cites `src/thing.clj`.
	MDEOF
}

fx_skill_model() {
	baseline "$1"
	mkdir -p "$1/.claude/skills/modelled-skill"
	cat > "$1/.claude/skills/modelled-skill/SKILL.md" <<-'MDEOF'
	---
	name: modelled-skill
	description: a skill carrying a model key it has no business having
	model: sonnet
	---

	Body.
	MDEOF
}

fx_skill_no_desc() {
	baseline "$1"
	mkdir -p "$1/.claude/skills/mute-skill"
	cat > "$1/.claude/skills/mute-skill/SKILL.md" <<-'MDEOF'
	---
	name: mute-skill
	---

	Body.
	MDEOF
}

fx_model_pinned() {
	baseline "$1"
	cat > "$1/.claude/agents/pinned-agent.md" <<-'MDEOF'
	---
	name: pinned-agent
	description: an agent pinned to a version string instead of a stable alias
	model: claude-sonnet-4-5-20250929
	---

	Body.
	MDEOF
}

fx_non_kebab() {
	baseline "$1"
	cat > "$1/.claude/rules/Bad_Name.md" <<-'MDEOF'
	# Not kebab-case

	Body cites `src/thing.clj`.
	MDEOF
}

fx_backtick_unmatched() {
	baseline "$1"
	cat > "$1/README.md" <<-'MDEOF'
	# Readme

	A paragraph with a stray ` backtick that opens a span and never closes it,
	so the character renders literally and any span it meant to open is lost.

	A clean paragraph citing `src/thing.clj`.
	MDEOF
}

fx_folded_span() {
	baseline "$1"
	cat > "$1/README.md" <<-'MDEOF'
	# Readme

	The measured defect from review-discipline.md: a span folded mid-identifier
	across a line break, `devcards.
	overlap`, which renders a broken symbol and loses the grep.

	A clean paragraph citing `src/thing.clj`.
	MDEOF
}

fx_dead_citation() {
	baseline "$1"
	cat > "$1/README.md" <<-'MDEOF'
	# Readme

	This cites `src/renamed_away.clj`, which resolves nowhere.

	It also cites `src/thing.clj` and links [the same file](./src/thing.clj),
	both of which resolve.
	MDEOF
}

fx_dead_link() {
	baseline "$1"
	cat > "$1/README.md" <<-'MDEOF'
	# Readme

	See [the missing page](./docs/gone.md) for details.

	A clean paragraph citing `src/thing.clj`.
	MDEOF
}

fx_unreadable() {
	baseline "$1"
	cat > "$1/README.md" <<-'MDEOF'
	# Readme

	Cites `src/thing.clj`.

	MDEOF
	# Appended, not inlined: backticks inside a single-quoted printf FORMAT read as
	# an attempted expansion (shellcheck SC2016). This format carries only bytes.
	printf 'Invalid UTF-8 next: \xff\xfe\n' >> "$1/README.md"
}

# ================================================================= the clauses
# EVERY FIXTURE TREE IS BUILT HERE, by a DIRECT call, and each clause case then
# names the ROOT rather than the function. Passing a function NAME and calling it
# as `"$fixture" "$root"` was the earlier shape; it hid all fifteen fixtures from
# static analysis (shellcheck SC2329, "this function is never invoked"), and the
# only repairs available were fifteen inline suppressions or a repo-wide rule
# disable — both forbidden by `.claude/rules/lint-gates.md`. Removing the
# indirection removes the class instead, and it builds each tree once rather than
# twice: a control fixture is always some other clause's positive fixture.
fx_fm_unterminated "$WORK/fx_fm_unterminated"
fx_fm_unparsed "$WORK/fx_fm_unparsed"
fx_paths_not_list "$WORK/fx_paths_not_list"
fx_paths_glob_dead "$WORK/fx_paths_glob_dead"
fx_paths_match_all "$WORK/fx_paths_match_all"
fx_load_test_missing "$WORK/fx_load_test_missing"
fx_load_test_mismatch "$WORK/fx_load_test_mismatch"
fx_scope_prose "$WORK/fx_scope_prose"
fx_skill_model "$WORK/fx_skill_model"
fx_skill_no_desc "$WORK/fx_skill_no_desc"
fx_model_pinned "$WORK/fx_model_pinned"
fx_non_kebab "$WORK/fx_non_kebab"
fx_backtick_unmatched "$WORK/fx_backtick_unmatched"
fx_folded_span "$WORK/fx_folded_span"
fx_dead_citation "$WORK/fx_dead_citation"
fx_dead_link "$WORK/fx_dead_link"
for fxroot in "$WORK"/fx_*; do
	manifest "$fxroot" >/dev/null
	empty_exemptions "$fxroot" >/dev/null
done

printf '\n'
green "md_gate canaries — clause attribution (positive / mutation / control)"

clause_case fm-unterminated "$WORK/fx_fm_unterminated" \
	'(= "unterminated" status)' \
	'(= "unterminated-DISABLED" status)' \
	non-kebab-name "$WORK/fx_non_kebab"

clause_case fm-unparsed "$WORK/fx_fm_unparsed" \
	'(for [[lineno raw] unparsed]' \
	'(for [[lineno raw] []]' \
	non-kebab-name "$WORK/fx_non_kebab"

clause_case paths-not-list "$WORK/fx_paths_not_list" \
	'(and (string? value) (not= "" value))' \
	'(and (string? value) (= "" value))' \
	paths-glob-dead "$WORK/fx_paths_glob_dead"

clause_case paths-glob-dead "$WORK/fx_paths_glob_dead" \
	'(not (alive? pattern))' \
	'(not (some? pattern))' \
	paths-match-all "$WORK/fx_paths_match_all"

clause_case paths-match-all "$WORK/fx_paths_match_all" \
	'(contains? match-all-globs pattern)' \
	'(contains? #{} pattern)' \
	paths-glob-dead "$WORK/fx_paths_glob_dead"

clause_case load-test-missing "$WORK/fx_load_test_missing" \
	'(when (and (= "rule" kind) scoped)' \
	'(when (and false (= "rule" kind) scoped)' \
	load-test-name-mismatch "$WORK/fx_load_test_mismatch"

clause_case load-test-name-mismatch "$WORK/fx_load_test_mismatch" \
	':when (and m (not= (second m) expected))' \
	':when (and m (not= (second m) (second m)))' \
	load-test-missing "$WORK/fx_load_test_missing"

clause_case scope-prose-with-paths "$WORK/fx_scope_prose" \
	':when (re-find scope-prose-re line)' \
	':when (and false (re-find scope-prose-re line))' \
	load-test-missing "$WORK/fx_load_test_missing"

clause_case skill-model-key "$WORK/fx_skill_model" \
	'(contains? data "model")' \
	'(contains? data "model-DISABLED")' \
	skill-missing-description "$WORK/fx_skill_no_desc"

clause_case skill-missing-description "$WORK/fx_skill_no_desc" \
	'(and (string? desc) (not (str/blank? desc)))' \
	'(any? desc)' \
	skill-model-key "$WORK/fx_skill_model"

clause_case model-pinned-version "$WORK/fx_model_pinned" \
	'(re-find #"(?U)\d" model)' \
	'(re-find #"(?U)\dDISABLED" model)' \
	non-kebab-name "$WORK/fx_non_kebab"

clause_case non-kebab-name "$WORK/fx_non_kebab" \
	'(when-not (re-matches kebab-re expected)' \
	'(when-not (or true (re-matches kebab-re expected))' \
	skill-model-key "$WORK/fx_skill_model"

clause_case backtick-unmatched "$WORK/fx_backtick_unmatched" \
	'(for [[pos width] unmatched' \
	'(for [[pos width] []' \
	code-span-folded-at-joiner "$WORK/fx_folded_span"

clause_case code-span-folded-at-joiner "$WORK/fx_folded_span" \
	':when (and m (or (joiner? (nth m 1)) (joiner? (nth m 2))))' \
	':when (and false m (or (joiner? (nth m 1)) (joiner? (nth m 2))))' \
	backtick-unmatched "$WORK/fx_backtick_unmatched"

clause_case dead-path-citation "$WORK/fx_dead_citation" \
	'(re-seq cite-span-re line)' \
	'(re-seq cite-span-re "")' \
	dead-markdown-link "$WORK/fx_dead_link"

clause_case dead-markdown-link "$WORK/fx_dead_link" \
	'(re-seq cite-link-re line)' \
	'(re-seq cite-link-re "")' \
	dead-path-citation "$WORK/fx_dead_citation"

# `unreadable-file` is an EXCEPTION HANDLER, so there is no predicate to break.
# Its mutation is a RENAME of the emitted clause id, which proves the finding
# came from THAT emit site rather than from any other — provenance, which is the
# meaningful claim here — and NOT that the handler's logic is correct. Labelled
# as the weaker form rather than dressed up as the stronger one.
printf '\n'
green "md_gate canaries — unreadable-file (provenance mutation, weaker form)"
root="$WORK/unreadable-file"
fx_unreadable "$root"
manifest "$root" >/dev/null
empty_exemptions "$root" >/dev/null
run_gate "$root"
expect_only "positive[unreadable-file]" "unreadable-file"
if mutate unreadable-file '"unreadable-file" path 1' '"unreadable-RENAMED" path 1'; then
	run_gate "$root" "$MUTANT"
	if [ "$RC" -eq 1 ] && ! contains "$OUT" "unreadable-file"; then
		ok "mutation[unreadable-file]: id renamed -> the fixture no longer reports it"
	else
		bad "mutation[unreadable-file]: id still reported after rename (rc=$RC)"
	fi
fi

# ============================================= the crash trap (exit 2, never 1)
# NEW relative to the python predecessor this suite was ported from, and it exists
# because that predecessor FAILED it. An uncaught exception propagated out of its
# `main()`, python printed a traceback and exited 1 — the code reserved for a
# FINDING — so a broken gate and a caught markdown defect were indistinguishable
# from outside. MEASURED on the deleted script: a `raise` planted in `check_text`
# gave exit 1. The JVM exits 1 on an uncaught exception for the same reason, which
# is why `exit-guarded!` traps and relabels to 2; this case is what keeps that trap
# honest, and its absence is why the defect lived.
#
# The mutation throws from `clip`, which runs only while FORMATTING a finding —
# so the control below is a fixture whose own clause never calls it, and it must
# still report exit 1 normally. That is what separates "the crash trap fired" from
# "the mutant is broken everywhere".
printf '\n'
green "md_gate canaries — an internal CRASH must wear ERROR's colour, never FAIL's"
if mutate crash-trap '(subs s 0 (min (long n) (count s))))' \
	'(throw (ex-info "canary crash" {})))'; then
	run_gate "$WORK/fx_backtick_unmatched" "$MUTANT"
	expect_rc "crash trap: an uncaught exception exits 2, not 1" 2 \
		"the gate itself crashed"
	run_gate "$WORK/fx_non_kebab" "$MUTANT"
	if [ "$RC" -eq 1 ] && contains "$OUT" non-kebab-name; then
		ok "control[crash-trap]: a fixture that never formats through the crash still reports exit 1"
	else
		bad "control[crash-trap]: the mutation was not surgical (rc=$RC)"
		printf '%s\n' "$OUT" | sed 's/^/       | /'
	fi
fi

# ================================================== harness floors (exit 2)
printf '\n'
green "md_gate canaries — non-vacuity floors must ERROR (exit 2), never pass"

root="$WORK/floor-no-rules"
baseline "$root"
rm -f "$root/.claude/rules/base-rule.md"
manifest "$root" >/dev/null
empty_exemptions "$root" >/dev/null
run_gate "$root"
expect_rc "floor: zero rule files" 2 "DISCOVERY broke"

root="$WORK/floor-no-hand-md"
baseline "$root"
rm -f "$root/.claude/rules/base-rule.md" "$root/.claude/skills/base-skill/SKILL.md" \
	"$root/.claude/agents/base-agent.md" "$root/.claude/commands/base-command.md"
manifest "$root" >/dev/null
empty_exemptions "$root" >/dev/null
run_gate "$root"
expect_rc "floor: zero hand-authored markdown" 2 "DISCOVERY broke"

root="$WORK/floor-dead-exclusion"
baseline "$root"
rm -f "$root/docs/proto/gen.md"
manifest "$root" >/dev/null
empty_exemptions "$root" >/dev/null
run_gate "$root"
expect_rc "floor: exclusion rule matching nothing" 2 "dead exclusion"

root="$WORK/floor-no-citations"
baseline "$root"
cat > "$root/.claude/rules/base-rule.md" <<-'MDEOF'
	# Base rule

	No code spans and no citations at all in this tree.
MDEOF
manifest "$root" >/dev/null
empty_exemptions "$root" >/dev/null
run_gate "$root"
expect_rc "floor: extractor found zero code spans" 2 "stopped matching"

root="$WORK/floor-empty-tracked"
baseline "$root"
: > "$root/.tracked"
empty_exemptions "$root" >/dev/null
run_gate "$root"
expect_rc "floor: empty tracked universe" 2 "means git cannot resolve"

# ============================================ exemption proof contract (exit 2)
printf '\n'
green "md_gate canaries — an exemption is a WAIVER, and its proof is enforced"

# LITERAL dates against the PINNED clock (2026-06-15), never offsets from the real
# one. `soon` sits inside the 90-day horizon, `past` one day behind, `far` well
# beyond it. An offset computed from `date` would make these three cases drift with
# the wall clock, and a fixture that drifts is one whose red eventually stops
# meaning what its label says.
soon=2026-07-15
past=2026-06-14
far=2027-07-20

root="$WORK/exempt"
fx_dead_citation "$root"
manifest "$root" >/dev/null

write_exempt() {
	cat > "$root/.exemptions.edn"
}

# 1. a complete entry excuses the finding
write_exempt <<-EDNEOF
	{:exemptions [{:check "dead-path-citation" :file "README.md"
	  :match "src/renamed_away.clj"
	  :rationale "canary: a complete proof-carrying entry."
	  :retires-when "the canary stops needing a positive case."
	  :owner "gate-port" :expires "$soon"}]}
EDNEOF
run_gate "$root"
expect_clean "exemption: complete entry excuses its finding"

# 2. a missing proof key is refused
write_exempt <<-EDNEOF
	{:exemptions [{:check "dead-path-citation" :file "README.md"
	  :match "src/renamed_away.clj"
	  :rationale "canary: no owner."
	  :retires-when "never" :expires "$soon"}]}
EDNEOF
run_gate "$root"
expect_rc "exemption: missing ':owner' is refused" 2 "must be a non-blank string"

# 3. a BLANK proof key is refused too — presence is not proof
write_exempt <<-EDNEOF
	{:exemptions [{:check "dead-path-citation" :file "README.md"
	  :match "src/renamed_away.clj"
	  :rationale "canary: blank retires-when."
	  :retires-when "   " :owner "gate-port" :expires "$soon"}]}
EDNEOF
run_gate "$root"
expect_rc "exemption: blank ':retires-when' is refused" 2 "must be a non-blank string"

# 4. an EXPIRED entry is refused
write_exempt <<-EDNEOF
	{:exemptions [{:check "dead-path-citation" :file "README.md"
	  :match "src/renamed_away.clj"
	  :rationale "canary: expired."
	  :retires-when "never" :owner "gate-port" :expires "$past"}]}
EDNEOF
run_gate "$root"
expect_rc "exemption: expired entry is refused" 2 "EXPIRED"

# 5. an entry written never to lapse is refused — a SEPARATE clause from expiry,
#    so neither masks the other
write_exempt <<-EDNEOF
	{:exemptions [{:check "dead-path-citation" :file "README.md"
	  :match "src/renamed_away.clj"
	  :rationale "canary: beyond the horizon."
	  :retires-when "never" :owner "gate-port" :expires "$far"}]}
EDNEOF
run_gate "$root"
expect_rc "exemption: expiry beyond the 90-day horizon is refused" 2 "horizon"

# 6. a STALE entry — matching no live finding — is itself a failure
root2="$WORK/exempt-stale"
baseline "$root2"
manifest "$root2" >/dev/null
cat > "$root2/.exemptions.edn" <<-EDNEOF
	{:exemptions [{:check "dead-path-citation" :file "README.md"
	  :match "nothing/matches/this.clj"
	  :rationale "canary: matches no live finding."
	  :retires-when "never" :owner "gate-port" :expires "$soon"}]}
EDNEOF
run_gate "$root2"
expect_rc "exemption: stale entry fails so the allowlist can only shrink" 2 "STALE"

# 7. a NARROWER match must not excuse a DIFFERENT finding of the same clause
root3="$WORK/exempt-narrow"
fx_dead_citation "$root3"
cat >> "$root3/README.md" <<-'MDEOF'

	And a second dead one: `src/also_gone.clj`.
MDEOF
manifest "$root3" >/dev/null
cat > "$root3/.exemptions.edn" <<-EDNEOF
	{:exemptions [{:check "dead-path-citation" :file "README.md"
	  :match "src/renamed_away.clj"
	  :rationale "canary: narrow match excuses one citation only."
	  :retires-when "never" :owner "gate-port" :expires "$soon"}]}
EDNEOF
run_gate "$root3"
if [ "$RC" -eq 1 ] && contains "$OUT" "src/also_gone.clj" \
	&& ! contains "$OUT" "src/renamed_away.clj"; then
	ok "exemption: a narrow 'match' excuses only its own citation (exit 1)"
else
	bad "exemption: narrow match leaked or over-excused (rc=$RC)"
	printf '%s\n' "$OUT" | sed 's/^/       | /'
fi

# ================================================ the harness's own canaries
# "A canary aimed one layer off PASSES, and its green is indistinguishable from
# the green of one that reached the code the way callers do." Everything above
# rests on two helpers, so both are shown able to REFUSE. Each runs the real
# helper against an input it must reject, with PASS/FAIL swapped for the duration
# so a correct refusal counts as a pass.
printf '\n'
green "md_gate canaries — the harness helpers must be able to refuse"

harness_expect_refusal() {
	local probe_label=$1
	shift
	local before=$FAIL
	"$@" >/dev/null 2>&1 || true
	if [ "$FAIL" -gt "$before" ]; then
		FAIL=$before
		ok "$probe_label"
	else
		bad "$probe_label — the helper ACCEPTED an input it must refuse; every green above it is decoration"
	fi
}

# expect_only must refuse a red carrying TWO clauses: a fixture that trips a
# second clause is a red that is not attributed, and accepting it would let every
# positive case above pass on a neighbour's refusal.
fx_two_clauses() {
	baseline "$1"
	cat > "$1/.claude/rules/Bad_Name.md" <<-'MDEOF'
	---
	description: non-kebab name AND a dead scope glob, two clauses at once
	paths:
	  - "no/such/tree/**"
	---
	<!-- LOAD-TEST: Bad_Name -->

	Body cites `src/thing.clj`.
	MDEOF
}
root="$WORK/harness-two-clauses"
fx_two_clauses "$root"
manifest "$root" >/dev/null
empty_exemptions "$root" >/dev/null
run_gate "$root"
harness_expect_refusal "expect_only refuses an unattributed two-clause red" \
	expect_only "harness-probe" non-kebab-name

# expect_only must also refuse an ERROR (exit 2) offered as a finding.
root="$WORK/harness-error-not-fail"
baseline "$root"
rm -f "$root/docs/proto/gen.md"
manifest "$root" >/dev/null
empty_exemptions "$root" >/dev/null
run_gate "$root"
harness_expect_refusal "expect_only refuses exit 2 (ERROR) offered as a FAIL" \
	expect_only "harness-probe" non-kebab-name

# mutate must refuse a target that is not a unique substring of the gate, rather
# than reporting a mutation it did not make.
harness_expect_refusal "mutate refuses a non-unique / absent target" \
	mutate harness-probe 'THIS TEXT IS NOT IN THE GATE' 'replacement'

# mutate must refuse a NO-OP substitution. A mutant byte-identical to the original
# is the failure mode that reads as attribution while proving its exact opposite:
# every clause still works, so the fixture goes red again and a suite checking
# only "did it change colour" would score that as the clause firing.
harness_expect_refusal "mutate refuses a no-op (byte-identical mutant)" \
	mutate harness-probe '(re-matches kebab-re expected)' '(re-matches kebab-re expected)'

# ==================================================================== verdict
printf '\n'
if [ "$FAIL" -eq 0 ]; then
	green "md_gate canaries: ALL GREEN — $PASS assertion(s)"
	exit 0
fi
red "md_gate canaries: $FAIL FAILED, $PASS passed"
exit 1
