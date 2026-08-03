#!/usr/bin/env bash
# lint_gate_test.sh — canaries for tools/lint/src/lint_gate/core.clj.
#
# WHAT A CANARY HERE HAS TO PROVE, and it is more than "it went red".
#
#   A REFUSAL SHOWN IS NOT A REFUSAL ATTRIBUTED. This gate has SIX clauses that
#   can each refuse, and five of them exit with the SAME code (3): no clj-kondo,
#   an empty analysis, a missing ceiling file, an unknown check name, no paths.
#   So a non-zero exit says almost nothing about which clause fired. Every case
#   below asserts the exact exit code AND a substring of the diagnosis that names
#   the clause; the blocking clauses are additionally proven by MUTATION — silence
#   that one clause and the run must go green (or change its verdict), while a
#   neighbour must still refuse.
#
#   FAIL IS NOT ERROR. The gate exits 1 for findings and 3 for cannot-run. The JVM
#   exits 1 on an uncaught exception, which is why the gate carries a top-level
#   trap re-labelling a crash as 3 — and why a suite asserting merely "non-zero"
#   would accept a stack trace as proof that a ceiling was enforced.
#
#   THE FIXTURE IS A SYNTHETIC CORPUS, not this repo. Each case builds a throw-away
#   directory holding its own `tools/lint/gates.edn` and its own tiny namespaces,
#   and runs a COPY of the gate against it via `clojure -Sdeps '{:paths [...]}'`.
#   Driving the live tree instead would mean expectations that move whenever the
#   repo does, and no way to perturb an input without editing a tracked file.
#
# Usage: bash tools/lint/test/lint_gate_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SRC="$SCRIPT_DIR/../src/lint_gate"

[ -d "$SRC" ] || {
	printf '\033[31mFAIL\033[0m — gate sources not found at %s\n' "$SRC" >&2
	exit 3
}
command -v clojure > /dev/null 2>&1 || {
	printf '\033[31mFAIL\033[0m — clojure is not on PATH; this suite cannot run.\n' >&2
	exit 3
}

# THE SHARED MUTATION PRIMITIVE. Its absence is a HARD failure and never a skip:
# every attribution proof below is a mutation, so without it this suite could only
# report cases it did not run — and the shape of that report is indistinguishable
# from a green one if the sourcing were allowed to fail soft.
MUTATE_LIB="$SCRIPT_DIR/lib_mutate.sh"
[ -f "$MUTATE_LIB" ] || {
	printf '\033[31mFAIL\033[0m — missing mutation primitive at %s\n' "$MUTATE_LIB" >&2
	printf '  Every canary here proves a clause by breaking it alone, so without\n' >&2
	printf '  this file the suite has no way to break anything and its green would\n' >&2
	printf '  mean nothing.\n' >&2
	exit 3
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/lint-gate-test.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

PASS=0
FAILED=0
ok() {
	PASS=$((PASS + 1))
	printf '  \033[32mok\033[0m   %s\n' "$*"
}
bad() {
	FAILED=$((FAILED + 1))
	printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2
}
banner() { printf '\n== %s\n' "$*"; }

# fixture <name> <loc-block> <publics-block> — build a corpus dir, echo its path.
#
# Every case gets its OWN ceilings file rather than sharing this repo's, so a case
# says what it means locally and a change to the real ceilings cannot silently
# retune the suite.
fixture() {
	local name="$1"
	local loc_block="$2"
	local publics_block="$3"
	local dir="$WORK/$name"
	rm -rf -- "$dir"
	mkdir -p "$dir/tools/lint" "$dir/gate/lint_gate" "$dir/src/fixture"
	# EVERY namespace, not just core.clj: core requires util/specs/docstrings, so a
	# fixture carrying one file fails to LOAD — which would red every case here for
	# a reason that has nothing to do with the clause under test.
	cp "$SRC"/*.clj "$dir/gate/lint_gate/"
	cat > "$dir/tools/lint/gates.edn" <<EDN
{:ns-size {:loc-block $loc_block :publics-block $publics_block
           :loc-warn 3 :publics-warn 2}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}}
EDN
	# The dispatch reads BOTH config files for every check, so an absent exemptions
	# file is a CANNOT-RUN unrelated to whatever a case is testing.
	printf '{:docstrings []\n :spec-shape []}\n' > "$dir/tools/lint/exemptions.edn"
	printf '%s' "$dir"
}

# add_ns <dir> <relpath> <public-count> — write a namespace with N public defs.
add_ns() {
	local dir="$1" rel="$2" n="$3" i
	mkdir -p "$(dirname -- "$dir/$rel")"
	{
		printf '(ns %s)\n' "$(basename -- "$rel" .clj | tr '_' '-')"
		for ((i = 0; i < n; i++)); do printf '(def v%d %d)\n' "$i" "$i"; done
	} > "$dir/$rel"
}

# run_gate <dir> [extra args...] — run the fixture's gate copy from inside it.
run_gate() {
	local dir="$1"
	shift
	# -J-XX:TieredStopAtLevel=1 caps JIT at the fast baseline compiler. Each case is a
	# fresh JVM running for well under a second, so tier-4 compilation never pays for
	# itself — the run ends before the optimised code would. MEASURED interleaved on one
	# gate invocation: 1031 ms -> 729 ms mean (~29%), with tighter variance too
	# (684-763 vs 834-1208). It changes no VERDICT: the same bytecode executes.
	(cd "$dir" && clojure -J-XX:TieredStopAtLevel=1 -Sdeps '{:paths ["gate"]}' -M -m lint-gate.core "$@" 2>&1) || return $?
}

# expect <dir> <code> <needle> <label> [args...]
expect() {
	local dir="$1" want="$2" needle="$3" label="$4"
	shift 4
	local out code
	out="$(run_gate "$dir" "$@")" && code=0 || code=$?
	if [ "$code" != "$want" ]; then
		bad "$label — expected exit $want, got $code"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	if [ -n "$needle" ] && ! contains "$out" "$needle"; then
		bad "$label — exit $code was right but the diagnosis never named the clause"
		printf '       | wanted: %s\n' "$needle" >&2
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	ok "$label"
}

# mutate <dir> <old> <new> — silence ONE clause in the fixture's gate copy.
#
# The primitive lives in lib_mutate.sh, sourced above: ONE implementation, with its
# own self-test, shared by every canary suite here. A per-suite copy of a subtle
# 40-line replacement is how one copy quietly stops refusing, and a primitive that
# cannot refuse turns every attribution proof in this file into a tautology while
# the suite still prints green.
mutate() {
	local dir="$1" file="$2" old="$3" new="$4"
	local err
	if ! err="$(mutate_file "$dir/gate/lint_gate/$file" "$old" "$new" 2>&1)"; then
		bad "$err"
		return 1
	fi
	return 0
}

# ---------------------------------------------------------------------------
banner 'THE MUTATION PRIMITIVE ITSELF — it must be able to REFUSE'
# Run FIRST, because every attribution proof below depends on it. A primitive that
# always returned 0 would turn each of those into a tautology, and the failure is
# SILENT — the suite would print exactly the green it prints now.
if mutate_selftest "$WORK/_selftest"; then
	PASS=$((PASS + 7))
else
	bad 'the mutation primitive failed its own self-test — every proof below is void'
fi

# ---------------------------------------------------------------------------
banner 'THE SUBSTRING PRIMITIVE ITSELF — and the pipe form it replaces'
# Every case below reads a diagnosis with `contains`, so the same argument applies
# a second time: a primitive that always returned 0 would make each needle
# assertion vacuous while the suite printed green. Its last case additionally
# forces the SIGPIPE/pipefail race that the retired `printf … | grep -q …` form
# is subject to, so the reason this suite no longer uses that form stays proven
# rather than remembered.
if contains_selftest "$WORK/_selftest"; then
	PASS=$((PASS + 7))
else
	bad 'the substring primitive failed its own self-test — every needle assertion is void'
fi

# ---------------------------------------------------------------------------
banner 'BASE — a corpus under the ceiling passes'
D="$(fixture base 50 10)"
add_ns "$D" src/fixture/small.clj 2
expect "$D" 0 'under the ceiling' 'a small namespace is clean' --check ns-size src

# ---------------------------------------------------------------------------
banner 'CLAUSE — the PUBLIC-VAR axis blocks'
D="$(fixture publics 50 3)"
add_ns "$D" src/fixture/wide.clj 9
expect "$D" 1 'over ceiling' 'a namespace over the publics ceiling is a FINDING (exit 1)' \
	--check ns-size src

D2="$(fixture publics_mut 50 3)"
add_ns "$D2" src/fixture/wide.clj 9
# Silence ONLY the publics half of the block predicate. The LOC half, the warn
# tier and every cannot-run clause are untouched, so a red here would prove this
# canary never measured the publics axis.
if mutate "$D2" core.clj '(> publics (:publics-block conf))' '(> publics Long/MAX_VALUE)'; then
	expect "$D2" 0 'under the ceiling' \
		'MUTANT: publics axis silenced -> green (the red was that axis alone)' \
		--check ns-size src
fi

# CONTROL — on that same mutant the LOC axis must still refuse. This is what
# separates "one axis silenced" from "the whole block predicate broke".
add_ns "$D2" src/fixture/tall.clj 60
expect "$D2" 1 'over ceiling' 'CONTROL: LOC axis still red on the publics-silenced mutant' \
	--check ns-size src

# ---------------------------------------------------------------------------
banner 'CLAUSE — the LOC axis blocks'
D="$(fixture loc 8 100)"
add_ns "$D" src/fixture/tall.clj 20
expect "$D" 1 'over ceiling' 'a namespace over the LOC ceiling is a FINDING (exit 1)' \
	--check ns-size src

# ---------------------------------------------------------------------------
banner 'SEEDED-RATCHET PROPERTY — BLOCK is strictly-greater-than'
# This is the property that lets a ceiling be seeded at the measured maximum and
# still be green on arrival with ZERO exemptions. It is the whole reason the
# ratchet is legitimate under gate-enforcement.md §1, so it gets its own canary
# instead of being left as an implementation detail.
#
# A namespace of exactly N code-LOC against :loc-block N must PASS.
D="$(fixture at_ceiling 3 100)"
printf '(ns fixture.exact)\n(def a 1)\n(def b 2)\n' > "$D/src/fixture/exact.clj"
expect "$D" 0 'under the ceiling' 'a namespace exactly AT the ceiling passes' \
	--check ns-size src

D2="$(fixture at_ceiling_mut 3 100)"
printf '(ns fixture.exact)\n(def a 1)\n(def b 2)\n' > "$D2/src/fixture/exact.clj"
# Flip strictly-greater to greater-or-equal: the at-ceiling namespace must now
# fail. A green here would mean the comparison is not what makes seeding work.
if mutate "$D2" core.clj '(> loc (:loc-block conf))' '(>= loc (:loc-block conf))'; then
	expect "$D2" 1 'over ceiling' \
		'MUTANT: > relaxed to >= -> the at-ceiling namespace FAILS (seeding depends on it)' \
		--check ns-size src
fi

# ---------------------------------------------------------------------------
banner 'CLAUSE — the DEGRADED watchlist reports without blocking'
D="$(fixture watch 100 100)"
add_ns "$D" src/fixture/mid.clj 5
expect "$D" 0 'DEGRADED watchlist' 'a warn-band namespace is reported and does NOT block' \
	--check ns-size src

# ---------------------------------------------------------------------------
banner 'CLAUSE — generated projections are out of scope'
# A file under a /generated/ path cannot be hand-shrunk (its bytes are pinned to a
# generator's output), so a finding against it could never be satisfied.
# A NON-GENERATED namespace is planted alongside, and it is load-bearing rather
# than scenery: with only the generated file present the exclusion empties the row
# set and the SECOND vacuity guard refuses — correctly, but that verdict says
# nothing about the exclusion. The first draft of this canary made exactly that
# mistake and read the guard's exit 3 as a failure of the exclusion.
D="$(fixture gen 5 3)"
add_ns "$D" src/fixture/generated/enums.clj 40
add_ns "$D" src/fixture/ordinary.clj 1
expect "$D" 0 'under the ceiling' 'a hugely over-ceiling file under /generated/ is not a finding' \
	--check ns-size src

D2="$(fixture gen_mut 5 3)"
add_ns "$D2" src/fixture/generated/enums.clj 40
add_ns "$D2" src/fixture/ordinary.clj 1
# Neuter the exclusion: the same file must now be reported. This proves the green
# above came from the exclusion and not from the file being invisible for some
# other reason (a path bug, a missing analysis entry).
if mutate "$D2" util.clj '(str/includes? path "/generated/")' 'false'; then
	expect "$D2" 1 'over ceiling' \
		'MUTANT: generated exclusion neutered -> the same file IS a finding' \
		--check ns-size src
fi

# ---------------------------------------------------------------------------
banner 'CLAUSE — NON-VACUITY: an empty corpus must never read as clean'
# An empty corpus has no violators, so without a guard this run reports a clean
# gate over zero namespaces. That is the worst output a gate can produce.
D="$(fixture empty 50 10)"
expect "$D" 3 'analysis is EMPTY' 'an empty corpus is CANNOT RUN (exit 3), never a green' \
	--check ns-size src

D2="$(fixture empty_mut 50 10)"
# Silence the analysis-level guard. The run must STILL refuse — but with the
# SECOND guard's message, not the first's. That is what attributes the message
# above to this clause: remove it and that specific diagnosis disappears while
# the exit code stays 3.
if mutate "$D2" util.clj '(when (or (empty? nsd) (empty? vds))' '(when false'; then
	out="$(run_gate "$D2" --check ns-size src)" && code=0 || code=$?
	if [ "$code" = 3 ] && contains "$out" 'no hand-authored namespaces'; then
		ok 'MUTANT: analysis guard silenced -> the SECOND guard refuses (exit 3, its own message)'
	else
		bad "MUTANT: analysis guard silenced -> expected exit 3 from the second guard, got $code"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
	fi
	if contains "$out" 'analysis is EMPTY'; then
		bad 'the silenced guard still printed its own diagnosis'
	else
		ok 'MUTANT: and the silenced guard no longer prints "analysis is EMPTY"'
	fi
fi

# BOTH guards silenced. The intuitive expectation is a vacuous GREEN, and it is
# FALSE on this gate — worth pinning rather than quietly dropping. With both gone
# the summary line takes `max` over an empty corpus and THROWS, so the vacuous
# green is structurally unreachable without a third mutation to the summary too.
# What IS true is that the crash must not wear the FINDINGS code: the top-level
# trap re-labels it 3. That trap exists because this canary found the bare
# exception exiting 1.
D3="$(fixture empty_mut_both 50 10)"
if mutate "$D3" util.clj '(when (or (empty? nsd) (empty? vds))' '(when false' &&
	mutate "$D3" core.clj '(when (empty? rows)' '(when false'; then
	expect "$D3" 3 'the gate itself crashed' \
		'MUTANT: both guards silenced -> a CRASH labelled 3, never a green and never a 1' \
		--check ns-size src
fi

# ---------------------------------------------------------------------------
banner 'CLAUSE — a missing ceiling file is CANNOT RUN, never "no limit"'
D="$(fixture no_ceilings 50 10)"
add_ns "$D" src/fixture/small.clj 2
rm -f "$D/tools/lint/gates.edn"
expect "$D" 3 'no gate config' 'a deleted config file refuses, rather than disabling the gate' \
	--check ns-size src

# ---------------------------------------------------------------------------
banner 'CLAUSE — argv is validated rather than defaulted'
D="$(fixture argv 50 10)"
add_ns "$D" src/fixture/small.clj 2
expect "$D" 3 'unknown check' 'a typo in the check name refuses, never a silent no-op' \
	--check bogus src
expect "$D" 3 'no paths given' 'an empty path list refuses, rather than analysing nothing' \
	--check ns-size
expect "$D" 3 'usage:' 'a missing --check flag refuses'

# ---------------------------------------------------------------------------
banner 'CLAUSE — a missing TOOL is a hard failure with an install hint'
# THE TOOL NAME IS MUTATED, NOT THE PATH, and the first draft got this wrong in a
# way worth recording. Narrowing PATH to a hand-listed probe bin killed the
# `clojure` launcher itself — it is a shell script needing `cksum`, `cut` and a
# long tail of coreutils — so the case exited 127 from the SHELL. That is an ERROR
# wearing the gate's colour: it proved a shell could not find `cut`, and said
# nothing about whether the GATE diagnoses a missing clj-kondo. Enumerating the
# launcher's dependencies is a losing game and would rot at the next release.
#
# Mutating the looked-up binary NAME asks the clause's own question directly — "a
# tool this gate needs is not resolvable" — and leaves the launcher intact.
D="$(fixture no_kondo 50 10)"
add_ns "$D" src/fixture/small.clj 2
if mutate "$D" util.clj '(io/file % "clj-kondo")' '(io/file % "clj-kondo-absent-probe")'; then
	expect "$D" 3 'clj-kondo is not on PATH' \
		'an unresolvable tool is CANNOT RUN (exit 3) with an install hint, never a green' \
		--check ns-size src
fi

# ---------------------------------------------------------------------------
printf '\n== summary\n'
printf '  passed: %d\n' "$PASS"
printf '  failed: %d\n' "$FAILED"
if [ "$FAILED" -gt 0 ]; then
	printf '\033[31m[lint-gate-test] RED\033[0m\n' >&2
	exit 1
fi
printf '\033[32m[lint-gate-test]\033[0m ALL GREEN (%d cases)\n' "$PASS"
