#!/usr/bin/env bash
# file_size_ceiling_test.sh — canaries for tools/lint/file_size_ceiling.sh.
#
# WHAT A CANARY HERE HAS TO PROVE, and it is more than "it went red".
#
#   A REFUSAL SHOWN IS NOT A REFUSAL ATTRIBUTED. This gate has TWO clauses that
#   exit non-zero — the ceiling and the non-vacuity guard — and an empty repo
#   satisfies the ceiling clause vacuously while tripping the guard. So every
#   canary below runs TWICE: once against the real script, where it must fail
#   with ITS OWN exit code and ITS OWN message, and once against a MUTANT in
#   which that one clause alone is silenced, where the verdict must change. On
#   each mutant a NEIGHBOURING clause is then required to still refuse, which is
#   what separates "this clause was silenced" from "the scanner broke".
#
#   THE MUTATION MUST BE PROVEN TO HAVE LANDED. A sed that silently matched
#   nothing yields a mutant identical to the original, whose unchanged verdict
#   would then read as attribution while proving the exact opposite. The shared
#   `mutate_file` asserts the new text present AND the old gone, and refuses
#   otherwise; its own self-test runs first, because every proof here depends on
#   it being able to refuse.
#
#   FAIL IS NOT ERROR. The gate exits 1 for findings and 3 for cannot-run, so a
#   bash syntax error (exit 2) or a missing command (127) can never be mistaken
#   for a clause firing. Every case asserts the EXACT code plus a substring of
#   the diagnosis that names the clause.
#
#   THE FIXTURE IS A SYNTHETIC GIT REPO, not this one. The gate resolves its root
#   with `git rev-parse --show-toplevel`, so a copy of it placed inside a throw-
#   away repo measures THAT repo. A suite driven against the live tree could not
#   plant a 1 MiB file without touching tracked state, and its expectations would
#   move every time the repo grew.
#
#   AND IT PINS THE TWO CEILINGS. The last section asserts MAX_BYTES and
#   WATCH_BYTES are the seeded values. That is the down-only ratchet made
#   mechanical: raising either to make a red go away turns this suite red
#   instead, so the change has to be a deliberate edit here, on the record —
#   which is what `.claude/rules/gate-enforcement.md` § A METRIC CEILING asks
#   for and what nothing but review would otherwise enforce.
#
# Usage: bash tools/lint/test/file_size_ceiling_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SUT="$SCRIPT_DIR/../file_size_ceiling.sh"
SUT_REL='tools/lint/file_size_ceiling.sh'

[ -f "$SUT" ] || {
	printf '\033[31mFAIL\033[0m — subject under test not found at %s\n' "$SUT" >&2
	exit 3
}

# THE SHARED MUTATION PRIMITIVE. Absence is a HARD failure, never a skip: every
# attribution proof below is a mutation, so a soft-failing source would leave
# this suite reporting cases it never ran — in a shape indistinguishable from
# green.
MUTATE_LIB="$SCRIPT_DIR/lib_mutate.sh"
[ -f "$MUTATE_LIB" ] || {
	printf '\033[31mFAIL\033[0m — missing mutation primitive at %s\n' "$MUTATE_LIB" >&2
	printf '  Every canary here proves a clause by breaking it alone, so without\n' >&2
	printf '  this file the suite cannot break anything and its green means nothing.\n' >&2
	exit 3
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

# The seeded ceilings this suite's fixtures are built to straddle. Kept as
# literals rather than read from the gate: a fixture derived from the value it
# is meant to test cannot detect that value moving.
EXPECT_MAX=1048576
EXPECT_WATCH=262144

WORK="$(mktemp -d "${TMPDIR:-/tmp}/file-size-test.XXXXXX")"
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

# The gate is invoked with a BARE `git`, and this suite builds throw-away repos.
# GIT_DIR/GIT_WORK_TREE in the environment (tools/uber.sh exports both) would
# override every -C and make each fixture resolve to the real checkout instead,
# so they are dropped for every git call the suite makes AND for the gate itself.
git_at() {
	local repo="$1"
	shift
	env -u GIT_DIR -u GIT_WORK_TREE git -C "$repo" "$@"
}

# Deterministic filler of an exact byte count. The CONTENT is irrelevant to a
# size gate — which is the whole reason this gate catches the plain text an
# extension filter misses — so any deterministic source will do.
#
# NO PIPE, and that is not stylistic. `yes 'x' | head -c N` was the first form
# and it exits 141: `head` closes the pipe, `yes` takes SIGPIPE, and this
# suite's own `set -o pipefail` promotes that to the pipeline's status. The
# failure surfaced as the SUITE dying mid-run with 141 rather than as a case
# going red, which is the shape a harness fault always takes — and exactly what
# `.claude/rules/gate-enforcement.md` §2 means by demanding a FAIL rather than
# an ERROR. Reading from /dev/zero needs no pipe and cannot signal.
mkfile() {
	local path="$1" bytes="$2"
	mkdir -p "$(dirname -- "$path")"
	head -c "$bytes" /dev/zero > "$path"
}

# ---------------------------------------------------------------------------
# The synthetic base tree: a handful of small files, so every expectation below
# is decidable by reading this function. Deliberately contains NOTHING over the
# watch tier, so any watch/fail output in a case comes from that case's fixture.
# ---------------------------------------------------------------------------
build_repo() {
	local repo="$1"
	mkdir -p "$repo/tools/lint" "$repo/renderer/src" "$repo/docs"
	cp "$SUT" "$repo/$SUT_REL"
	printf 'int main(void) { return 0; }\n' > "$repo/renderer/src/main.c"
	printf '# docs\n' > "$repo/docs/index.md"
	git_at "$repo" init -q
	git_at "$repo" config user.email t@example.invalid
	git_at "$repo" config user.name t
	git_at "$repo" add -A
	git_at "$repo" commit -qm base
}

# Run the gate inside a fixture repo. Captures merged output; returns the code.
run_gate() {
	local repo="$1"
	(cd "$repo" && env -u GIT_DIR -u GIT_WORK_TREE bash "$SUT_REL" 2>&1) || return $?
}

# expect <repo> <expected-code> <expected-substring> <label>
expect() {
	local repo="$1" want="$2" needle="$3" label="$4" out code
	out="$(run_gate "$repo")" && code=0 || code=$?
	if [ "$code" != "$want" ]; then
		bad "$label — expected exit $want, got $code"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	if [ -n "$needle" ] && ! contains "$out" "$needle"; then
		bad "$label — exit $code was right but the diagnosis never named the clause"
		printf '       | wanted substring: %s\n' "$needle" >&2
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	ok "$label"
}

# refute <repo> <substring> <label> — the output must NOT contain the substring.
refute() {
	local repo="$1" needle="$2" label="$3" out
	out="$(run_gate "$repo")" || true
	if contains "$out" "$needle"; then
		bad "$label — output still contained: $needle"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	ok "$label"
}

mutate() {
	local repo="$1" old="$2" new="$3"
	local err
	if ! err="$(mutate_file "$repo/$SUT_REL" "$old" "$new" 2>&1)"; then
		bad "$err"
		return 1
	fi
	return 0
}

fresh() {
	# Two `local` statements, not one: bash expands EVERY word of a `local`
	# before performing any of its assignments, so `local a="$1" b="$WORK/$a"`
	# reads `a` while it is still unset and dies under `set -u`.
	local name="$1"
	local repo="$WORK/$name"
	rm -rf -- "$repo"
	build_repo "$repo"
	printf '%s' "$repo"
}

# An empty repo: initialised, nothing committed. The gate copy is excluded from
# nothing, but it is the only file, so discovery after `add` is still non-empty —
# these fixtures deliberately never add it.
create_empty_repo() {
	local repo="$1"
	rm -rf -- "$repo"
	mkdir -p "$repo/tools/lint"
	git_at "$repo" init -q 2> /dev/null || { mkdir -p "$repo" && git_at "$repo" init -q; }
	git_at "$repo" config user.email t@example.invalid
	git_at "$repo" config user.name t
	cp "$SUT" "$repo/$SUT_REL"
	printf '%s\n' "$SUT_REL" > "$repo/.gitignore"
	printf '.gitignore\n' >> "$repo/.gitignore"
}

# The mutation that neuters the non-vacuity guard. Silenced AT ITS CONDITION,
# not at its `exit 3`: replacing only the exit would leave the printf block in
# place, so the mutant would still print "discovered ZERO files" — a mutant that
# half-fired cannot attribute the message to the clause.
NEUTER_GUARD_OLD='if [ -z "$files" ]; then'
NEUTER_GUARD_NEW='if false; then'

# ---------------------------------------------------------------------------
banner 'THE MUTATION PRIMITIVE ITSELF — it must be able to REFUSE'
# First, because every attribution proof below depends on it.
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
banner 'base tree is GREEN, and says how much it measured'
R="$(fresh base)"
expect "$R" 0 'no hand-authored file over' 'a small clean tree passes'
refute "$R" '[file-size] watch' 'a tree with nothing over the watch tier prints no watchlist'

# ---------------------------------------------------------------------------
banner 'CLAUSE 1 — the CEILING (a hand-authored file over MAX_BYTES)'
R="$(fresh over)"
mkfile "$R/docs/huge.json" $((EXPECT_MAX + 1))
git_at "$R" add -A
git_at "$R" commit -qm huge
expect "$R" 1 'hand-authored file(s) over' 'a file one byte over MAX is a FINDING (exit 1)'
expect "$R" 1 'docs/huge.json' 'and the report NAMES the offending file'

# A file exactly AT the ceiling is not a finding — the comparison is `>`, so this
# pins the boundary rather than leaving it to be re-derived from the source.
R="$(fresh at_limit)"
mkfile "$R/docs/exact.json" "$EXPECT_MAX"
git_at "$R" add -A
git_at "$R" commit -qm exact
expect "$R" 0 'no hand-authored file over' 'a file EXACTLY at MAX is not a finding'

# Attribution: silence ONLY the arm that turns an over-size file into a finding.
# Discovery, measurement, the watchlist and the vacuity guard are untouched, so a
# red here would prove this canary was never measuring clause 1.
R2="$(fresh over_mut)"
mkfile "$R2/docs/huge.json" $((EXPECT_MAX + 1))
git_at "$R2" add -A
git_at "$R2" commit -qm huge
if mutate "$R2" 'if [ -n "$over" ]; then' 'if false; then'; then
	expect "$R2" 0 'no hand-authored file over' 'MUTANT: ceiling clause silenced -> green (red was clause 1 alone)'
fi

# CONTROL on that same mutant: the vacuity guard must STILL refuse an empty
# discovery. This is what separates "clause 1 silenced" from "the gate broke".
# The mutated gate is copied into an empty repo so the mutation is identical.
R3="$WORK/over_mut_empty"
create_empty_repo "$R3"
cp "$R2/$SUT_REL" "$R3/$SUT_REL"
expect "$R3" 3 'discovered ZERO files' 'CONTROL: vacuity guard still refuses on the ceiling-silenced mutant'

# ---------------------------------------------------------------------------
banner 'CLAUSE 2 — the NON-VACUITY guard (empty discovery is not clean)'
# Without the guard this run exits 0 and reports a clean gate over zero files.
# That is the whole failure the guard prevents, which is why this is not merely
# an error path.
R="$WORK/empty"
create_empty_repo "$R"
expect "$R" 3 'discovered ZERO files' 'zero discovery is CANNOT RUN (exit 3), never a green'

# Attribution, and here it is DIRECTLY observable: neuter the guard and the
# vacuous green appears, with no other clause able to backstop it.
R2="$WORK/empty_mut"
create_empty_repo "$R2"
if mutate "$R2" "$NEUTER_GUARD_OLD" "$NEUTER_GUARD_NEW"; then
	expect "$R2" 0 'no hand-authored file over' 'MUTANT: guard neutered -> vacuous GREEN over zero files'
	refute "$R2" 'discovered ZERO files' 'MUTANT: and the neutered guard no longer prints its own diagnosis'
fi

# CONTROL on that same mutant: with real content present the ceiling clause must
# still refuse, proving the mutation removed the guard and not the scanner.
R3="$(fresh empty_mut_control)"
mkfile "$R3/docs/huge.json" $((EXPECT_MAX + 1))
git_at "$R3" add -A
git_at "$R3" commit -qm huge
if mutate "$R3" "$NEUTER_GUARD_OLD" "$NEUTER_GUARD_NEW"; then
	expect "$R3" 1 'hand-authored file(s) over' 'CONTROL: ceiling clause still red on the guard-neutered mutant'
fi

# ---------------------------------------------------------------------------
banner 'CLAUSE 3 — the WATCHLIST reports but must NEVER block'
R="$(fresh watch)"
mkfile "$R/docs/mid.json" $((EXPECT_WATCH + 1))
git_at "$R" add -A
git_at "$R" commit -qm mid
expect "$R" 0 '[file-size] watch' 'a file over WATCH but under MAX is reported'
expect "$R" 0 'docs/mid.json' 'and the watchlist NAMES it'
expect "$R" 0 'no hand-authored file over' 'and the run still SUCCEEDS — the watch tier never blocks'

# A file exactly AT the watch tier is not on the watchlist (`>` again).
R="$(fresh at_watch)"
mkfile "$R/docs/exactw.json" "$EXPECT_WATCH"
git_at "$R" add -A
git_at "$R" commit -qm exactw
refute "$R" '[file-size] watch' 'a file EXACTLY at WATCH is not on the watchlist'

# Attribution: silence the watchlist block alone. The exit code must not move —
# it was already 0 — so the thing that changes is the NAMING, which is what this
# clause owns. A canary asserting only the exit code could not tell this clause
# from a dead one.
R2="$(fresh watch_mut)"
mkfile "$R2/docs/mid.json" $((EXPECT_WATCH + 1))
git_at "$R2" add -A
git_at "$R2" commit -qm mid
if mutate "$R2" 'if [ -n "$watch" ]; then' 'if false; then'; then
	expect "$R2" 0 'no hand-authored file over' 'MUTANT: watchlist silenced -> still exit 0'
	refute "$R2" 'docs/mid.json' 'MUTANT: and the file is no longer named (clause 3 owned that line)'
fi

# CONTROL on the watchlist-silenced mutant: an over-MAX file is still a finding.
mkfile "$R2/docs/huge.json" $((EXPECT_MAX + 1))
git_at "$R2" add -A
git_at "$R2" commit -qm huge
expect "$R2" 1 'hand-authored file(s) over' 'CONTROL: ceiling still red on the watchlist-silenced mutant'

# ---------------------------------------------------------------------------
banner 'SCOPE — the declared generated and vendored trees are out of scope'
# This is the clause that keeps the gate adoptable: this repo's genuinely
# largest files live in these trees, they are machine-produced, and no human
# could act on a finding against them.
R="$(fresh excluded)"
mkfile "$R/output/java/Big.java" $((EXPECT_MAX * 2))
mkfile "$R/renderer/lvgl/src/font/big.c" $((EXPECT_MAX * 2))
mkfile "$R/renderer/generated/ui.pb.h" $((EXPECT_MAX + 1))
mkfile "$R/tools/renderer-gen/generated/theme.json" $((EXPECT_MAX + 1))
mkfile "$R/docs/.protodoc/proto-db.edn" $((EXPECT_MAX + 1))
git_at "$R" add -A
git_at "$R" commit -qm generated
expect "$R" 0 'no hand-authored file over' 'output/, renderer/lvgl/, */generated/ and proto-db.edn are out of scope'

# Attribution: break the exclusion pattern alone (make it match nothing) and
# every one of those files becomes a finding. That proves they were excused BY
# THE EXCLUSION and not by, say, never having been discovered at all.
R2="$(fresh excluded_mut)"
mkfile "$R2/output/java/Big.java" $((EXPECT_MAX * 2))
git_at "$R2" add -A
git_at "$R2" commit -qm generated
if mutate "$R2" "EXCLUDE_RE='^(output/" "EXCLUDE_RE='^(NOTHINGMATCHESTHIS/"; then
	expect "$R2" 1 'output/java/Big.java' 'MUTANT: exclusion broken -> the generated file IS reported (the exclusion owned it)'
fi

# The exclusion is a PREFIX anchor, not a substring: a hand-authored path that
# merely CONTAINS a declared segment must still be measured. Without the `^` a
# file like renderer/src/output/x would be silently excused.
R="$(fresh prefix_anchor)"
mkfile "$R/renderer/src/output/notgenerated.c" $((EXPECT_MAX + 1))
git_at "$R" add -A
git_at "$R" commit -qm nested
expect "$R" 1 'renderer/src/output/notgenerated.c' 'a path merely CONTAINING "output/" is still in scope'

# ---------------------------------------------------------------------------
banner 'DISCOVERY — an UNTRACKED file is measured, not silently skipped'
# This is the PRIMARY case, not an edge: the failure being prevented is an
# author redirecting a generator into the tree. An index-only scan cannot see
# that file until it has already been staged.
R="$(fresh untracked)"
mkfile "$R/docs/dumped.json" $((EXPECT_MAX + 1))
# Deliberately NOT added to the index.
expect "$R" 1 'docs/dumped.json' 'a never-staged file is still measured'

# And an IGNORED path stays out — that is what keeps the widening cheap.
R="$(fresh ignored)"
printf 'scratchdir/\n' > "$R/.gitignore"
mkdir -p "$R/scratchdir"
mkfile "$R/scratchdir/huge.bin" $((EXPECT_MAX + 1))
git_at "$R" add .gitignore
git_at "$R" commit -qm ignore
expect "$R" 0 'no hand-authored file over' 'a gitignored path is out of scope'

# ---------------------------------------------------------------------------
banner 'A SYMLINK is skipped — its size is its target string, not its content'
# Measuring a symlink would report a handful of bytes for a huge target, so a
# symlink is neither a finding nor a way to smuggle one past the gate: the
# TARGET is measured on its own if it is in the tree.
R="$(fresh symlink)"
mkfile "$R/docs/real.bin" 100
ln -s ../docs/real.bin "$R/docs/link.bin"
git_at "$R" add -A
git_at "$R" commit -qm link
expect "$R" 0 'no hand-authored file over' 'a symlink does not itself produce a finding'

# ---------------------------------------------------------------------------
banner 'THE CEILINGS ARE PINNED to literals, and the fixtures are pinned to them'
#
# WHAT THIS DOES AND DOES NOT BUY — measured, because the first version of this
# section CLAIMED to make the down-only ratchet mechanical and did not.
#
# That version compared the gate's MAX_BYTES against EXPECT_MAX — the same
# variable every fixture above is built from. Raising both together therefore
# left the whole suite GREEN: 33 passed, 0 failed, with the gate's ceiling at
# 100 MiB. The check was self-referential, so it was worthless against exactly
# the bypass it was written to catch, while reading as protection against it.
#
# The literals below are independent of EXPECT_*, and the second loop pins the
# fixtures to them, so the two can no longer drift apart silently either.
#
# THE HONEST SCOPE, since no canary can stop a determined coordinated edit:
#   - An UNCOORDINATED raise — the realistic case, someone raising the ceiling
#     to clear a red — reds this suite in two independent ways: these cases, and
#     seven fixture-relative cases above that stop straddling the boundary.
#   - A COORDINATED raise must now edit THREE sites, one of which is this block,
#     whose only purpose is to state that the number moves DOWN only. It cannot
#     be done as a one-line change that looks like maintenance.
#   - It is NOT proof against someone who edits all three deliberately. Nothing
#     a canary can do is. That residue belongs to review.
RATCHET_MAX=1048576  # 1 MiB   — see the header of the gate for the measurement
RATCHET_WATCH=262144 # 256 KiB — that seeded each of these

for pair in "MAX_BYTES=$RATCHET_MAX" "WATCH_BYTES=$RATCHET_WATCH"; do
	if grep -qE "^${pair%%=*}=${pair#*=}([[:space:]]|$)" "$SUT"; then
		ok "$SUT_REL still declares ${pair%%=*}=${pair#*=}"
	else
		bad "${pair%%=*} is no longer ${pair#*=} in $SUT_REL — a ceiling here moves DOWN only.
       If this is a deliberate LOWERING, update RATCHET_* and EXPECT_* here in
       the same commit. If it is a raise to clear a red, it is a gate bypass in
       source form (.claude/rules/gate-enforcement.md § A METRIC CEILING)."
	fi
done

# And the fixtures must still straddle the pinned numbers. Without this, EXPECT_*
# could be moved alone: every case above would keep passing against a boundary
# that is no longer the gate's, which is a suite measuring nothing while green.
if [ "$EXPECT_MAX" = "$RATCHET_MAX" ] && [ "$EXPECT_WATCH" = "$RATCHET_WATCH" ]; then
	ok 'the fixture boundaries still equal the pinned ceilings'
else
	bad "EXPECT_MAX/EXPECT_WATCH ($EXPECT_MAX/$EXPECT_WATCH) have drifted from the
       pinned ceilings ($RATCHET_MAX/$RATCHET_WATCH). Every case above is built
       from EXPECT_*, so while they disagree this suite is testing a boundary
       the gate does not have."
fi

# ---------------------------------------------------------------------------
printf '\n== summary\n'
printf '  passed: %d\n' "$PASS"
printf '  failed: %d\n' "$FAILED"
if [ "$FAILED" -gt 0 ]; then
	printf '\033[31m[file-size-test] RED\033[0m\n' >&2
	exit 1
fi
printf '\033[32m[file-size-test]\033[0m ALL GREEN (%d cases)\n' "$PASS"
