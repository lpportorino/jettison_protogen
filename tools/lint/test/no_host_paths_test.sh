#!/usr/bin/env bash
# no_host_paths_test.sh — canaries for tools/lint/no_host_paths.sh.
#
# WHAT A CANARY HERE HAS TO PROVE, and it is more than "it went red".
#
#   A REFUSAL SHOWN IS NOT A REFUSAL ATTRIBUTED. This gate has FOUR clauses that
#   can each refuse, and three of them would refuse some of the same inputs — an
#   offending line, a stale allowlist entry and an empty discovery all exit
#   non-zero. So every canary below runs TWICE: once against the real script,
#   where it must fail with ITS OWN message, and once against a MUTANT in which
#   that one clause alone is silenced, where it must go GREEN. If a neighbour
#   were doing the work, the mutant would stay red and this suite fails.
#
#   THE MUTATION MUST BE PROVEN TO HAVE LANDED. A sed that silently matched
#   nothing yields a mutant identical to the original, whose green would then be
#   read as proof of attribution while proving the exact opposite. `mutate`
#   asserts the new text is present AND the old text is gone, and refuses
#   otherwise.
#
#   FAIL IS NOT ERROR. The gate exits 1 for findings and 3 for cannot-run, so a
#   bash syntax error (exit 2) or a missing command (127) can never be mistaken
#   for a clause firing. Every canary asserts the EXACT code plus a substring of
#   the diagnosis that names the clause.
#
#   THE FIXTURE IS A SYNTHETIC GIT REPO, not this one. The gate resolves its root
#   with `git rev-parse --show-toplevel`, so a copy of it placed inside a throw-
#   away repo scans THAT repo. (This is the retargeting hazard
#   .claude/rules/fork-isolation.md warns about, used deliberately: the root
#   comes from git, never from the script's own location.) A suite driven against
#   the live tree could not perturb inputs without editing tracked files, and its
#   expectations would move whenever the repo did.
#
# Usage: bash tools/lint/test/no_host_paths_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SUT="$SCRIPT_DIR/../no_host_paths.sh"
SUT_REL='tools/lint/no_host_paths.sh'

[ -f "$SUT" ] || {
	printf '\033[31mFAIL\033[0m — subject under test not found at %s\n' "$SUT" >&2
	exit 3
}

# THE SHARED MUTATION PRIMITIVE. Absence is a HARD failure, never a skip: every
# attribution proof below is a mutation, so a soft-failing source would leave this
# suite reporting cases it never ran — in a shape indistinguishable from green.
MUTATE_LIB="$SCRIPT_DIR/lib_mutate.sh"
[ -f "$MUTATE_LIB" ] || {
	printf '\033[31mFAIL\033[0m — missing mutation primitive at %s\n' "$MUTATE_LIB" >&2
	printf '  Every canary here proves a clause by breaking it alone, so without\n' >&2
	printf '  this file the suite cannot break anything and its green means nothing.\n' >&2
	exit 3
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/no-host-paths-test.XXXXXX")"
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

# ---------------------------------------------------------------------------
# The synthetic base tree. Deliberately tiny, so every expectation below is
# decidable by reading this function.
#
# It carries the one line the real ALLOWLIST excuses. That is load-bearing
# twice over: without it the stale-allowlist clause would fire on EVERY case
# below and mask whatever each was testing, and with it the base case doubles
# as the positive proof that the allowlist actually excuses.
# ---------------------------------------------------------------------------
build_repo() {
	local repo="$1"
	mkdir -p "$repo/tools/lint" "$repo/.github/actions/build-controls-wasm" "$repo/renderer"
	cp "$SUT" "$repo/$SUT_REL"
	cat > "$repo/.github/actions/build-controls-wasm/action.yml" <<'YML'
name: build controls wasm
runs:
  using: composite
  steps:
  - uses: actions/cache@v5
    with:
      path: ~/wasi-sdk
YML
	printf '# a clean config comment\nChecks: bugprone-*\n' > "$repo/renderer/.clang-tidy"
	printf 'root="$(git rev-parse --show-toplevel)"\n' > "$repo/tools/build.sh"
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
	if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
		bad "$label — exit $code was right but the diagnosis never named the clause"
		printf '       | wanted substring: %s\n' "$needle" >&2
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	ok "$label"
}

# mutate <repo> <old> <new> — silence ONE clause, proving the edit landed.
#
# The primitive lives in lib_mutate.sh, sourced above: ONE implementation carrying
# its own self-test, shared by every canary suite here. A per-suite copy of a
# subtle replacement is how one copy quietly stops refusing — and a primitive that
# cannot refuse turns every attribution proof in this file into a tautology while
# the suite still prints exactly the green it prints now.
#
# Two properties this file DEPENDS on, both pinned by that self-test: multi-line
# anchors compare as exact bytes (the non-vacuity mutant's anchor spans four lines,
# and `grep -F` would match on any surviving fragment of it), and a replacement
# that CONTAINS its anchor is accepted (the allowlist mutant rebinds `ALLOWLIST=(`
# to `ALLOWLIST=(); _parked=(`, so "the anchor is gone afterwards" is inapplicable
# there and asserting it would be a false red).
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

# ---------------------------------------------------------------------------
banner 'THE MUTATION PRIMITIVE ITSELF — it must be able to REFUSE'
# First, because every attribution proof below depends on it.
if mutate_selftest "$WORK/_selftest"; then
	PASS=$((PASS + 7))
else
	bad 'the mutation primitive failed its own self-test — every proof below is void'
fi

# ---------------------------------------------------------------------------
banner 'base tree is GREEN (and the allowlist really does excuse ~/wasi-sdk)'
# If the allowlist were not applied, the action.yml line would be reported here.
# So this case is the positive control for the allowlist, not mere setup.
R="$(fresh base)"
expect "$R" 0 'no operator-host paths' 'clean tree passes with the allowlisted line present'

# ---------------------------------------------------------------------------
banner 'CLAUSE 1 — /home/<user> detection'
R="$(fresh home)"
printf '# see /home/someoperator/git/thing for the source\n' >> "$R/renderer/.clang-tidy"
git_at "$R" commit -qam offender
expect "$R" 1 '/home/<user>/ references' 'an operator /home path is a FINDING (exit 1)'

# Attribution: silence ONLY the arm that turns /home hits into a finding. The
# tilde clause, the stale clause and the vacuity guard are untouched, so a red
# here would prove this canary was never measuring clause 1.
#
# WHY THE ARM AND NOT THE GREP. Silencing the DETECTION would be the stronger
# mutation and it is unavailable here, for a reason worth stating once and
# obeying in both clauses: the gate's ALLOWLIST is applied to what the greps
# found, so a grep that finds nothing makes every entry depending on it STALE —
# and the stale clause then refuses the mutant. That is a NEIGHBOUR firing, i.e.
# the exact confusion this suite exists to rule out, and it is not hypothetical:
# the live allowlist entry (`path: ~/wasi-sdk`) is a TILDE line, so silencing the
# tilde grep made clause 4 fire and the canary read as a failure of clause 2.
#
# Silencing the reporting arm keeps both scans — and therefore allowlist usage —
# exactly as they were, and narrows the mutation to precisely the clause under
# test: "a /home hit is a FINDING". Both clauses use this form so the next
# allowlist entry, whichever pattern it belongs to, cannot re-open the trap.
R2="$(fresh home_mut)"
printf '# see /home/someoperator/git/thing for the source\n' >> "$R2/renderer/.clang-tidy"
git_at "$R2" commit -qam offender
if mutate "$R2" '[ -n "$home_offenders" ] ||' 'false ||'; then
	expect "$R2" 0 'no operator-host paths' 'MUTANT: /home clause silenced -> green (red was clause 1 alone)'
fi

# CONTROL: on that same mutant, a TILDE offender must still be caught. This is
# what separates "clause 1 silenced" from "the whole scanner broke".
printf '# and ~/git/cc/other too\n' >> "$R2/renderer/.clang-tidy"
git_at "$R2" commit -qam tilde-too
expect "$R2" 1 '~/<dir> references' 'CONTROL: tilde clause still red on the /home-silenced mutant'

# ---------------------------------------------------------------------------
banner 'CLAUSE 2 — ~/<dir> detection'
R="$(fresh tilde)"
printf '# adopted from ~/git/cc/elsewhere/.clang-tidy\n' >> "$R/renderer/.clang-tidy"
git_at "$R" commit -qam offender
expect "$R" 1 '~/<dir> references' 'a ~/<dir> path is a FINDING (exit 1)'

# Same arm-level mutation as clause 1, for the reason recorded there — and THIS
# is the clause that forced it: the live allowlist entry is a tilde line.
R2="$(fresh tilde_mut)"
printf '# adopted from ~/git/cc/elsewhere/.clang-tidy\n' >> "$R2/renderer/.clang-tidy"
git_at "$R2" commit -qam offender
if mutate "$R2" '|| [ -n "$tilde_offenders" ]' '|| false'; then
	expect "$R2" 0 'no operator-host paths' 'MUTANT: tilde clause silenced -> green (red was clause 2 alone)'
fi
printf '# and /home/someoperator/x too\n' >> "$R2/renderer/.clang-tidy"
git_at "$R2" commit -qam home-too
expect "$R2" 1 '/home/<user>/ references' 'CONTROL: /home clause still red on the tilde-silenced mutant'

# ---------------------------------------------------------------------------
banner 'CLAUSE 2b — dot-prefixed tilde paths are DELIBERATELY not matched'
# The measured reason: ~/.m2, ~/.ssh and ~/.gitlibs are the GitHub Actions
# runner-home idiom and resolve against an ephemeral HOME. Pinning this as a
# canary means widening the pattern can no longer happen silently — whoever
# widens it must come here and decide to.
R="$(fresh dotted)"
{
	printf '          ~/.m2/repository\n'
	printf '          mkdir -p ~/.ssh\n'
} >> "$R/.github/actions/build-controls-wasm/action.yml"
git_at "$R" commit -qam dotted
expect "$R" 0 'no operator-host paths' 'CI runner-home cache paths (~/.m2, ~/.ssh) are not findings'

# ---------------------------------------------------------------------------
banner 'CLAUSE 3 — non-vacuity guard (empty discovery must not read as clean)'
# An empty repo has NO offenders, so without the guard this run would exit 0 and
# report a clean gate over zero files. That is the whole failure the guard
# exists to prevent, and it is why this case is not merely "an error path".
R="$WORK/empty"
rm -rf -- "$R"
mkdir -p "$R/tools/lint"
cp "$SUT" "$R/$SUT_REL"
git_at "$R" init -q
git_at "$R" config user.email t@example.invalid
git_at "$R" config user.name t
# Nothing committed, and the copied gate excludes ITSELF by path, so discovery
# is empty even once it is added.
expect "$R" 3 'discovered ZERO files' 'zero discovery is CANNOT RUN (exit 3), never a green'

# THE TWO CLAUSES ARE STRUCTURALLY ENTANGLED HERE, and pretending otherwise is
# how this canary was wrong. An empty discovery means the allowlisted line is
# absent too, so an empty repo makes every allowlist entry STALE — clause 4
# therefore refuses the guard-neutered mutant as well, and "silence the guard,
# watch it go green" is simply unavailable while the allowlist is non-empty.
#
# That is not a nuisance to route around; it is a fact about the gate, so it is
# pinned in two halves.
#
# 3a — guard neutered, allowlist intact. The mutant must still be RED, but for
# clause 4's reason and NOT with clause 3's diagnosis. This is what attributes
# the exit-3 CANNOT-RUN + "discovery broke" message in the case above to the
# guard alone: remove the guard and that specific verdict disappears.
create_empty_repo() {
	local repo="$1"
	rm -rf -- "$repo"
	mkdir -p "$repo/tools/lint"
	cp "$SUT" "$repo/$SUT_REL"
	git_at "$repo" init -q
	git_at "$repo" config user.email t@example.invalid
	git_at "$repo" config user.name t
}

# Silence the guard AT ITS CONDITION, not at its `exit 3`. Replacing only the exit
# leaves the guard's whole printf block in place, so the mutant still prints
# "discovered ZERO files" — a mutant that half-fired, which cannot attribute the
# message to the clause. `if false` removes the verdict AND the diagnosis together.
NEUTER_GUARD_OLD='if [ -z "$files" ]; then'
NEUTER_GUARD_NEW='if false; then'

R2="$WORK/empty_mut"
create_empty_repo "$R2"
if mutate "$R2" "$NEUTER_GUARD_OLD" "$NEUTER_GUARD_NEW"; then
	expect "$R2" 1 'STALE allowlist' 'MUTANT: guard neutered -> the CANNOT-RUN verdict is gone (clause 3 owned it)'
	out="$(run_gate "$R2")" || true
	if printf '%s' "$out" | grep -qF 'discovered ZERO files'; then
		bad 'clause 3 canary: the guard was neutered yet still produced its own diagnosis'
	else
		ok 'MUTANT: and the neutered guard no longer prints "discovered ZERO files"'
	fi
fi

# 3b — guard neutered AND the allowlist emptied, which removes clause 4's ability
# to backstop. NOW the vacuous green is reachable, and it is the whole point of
# the guard: zero files scanned, reported as a clean gate.
#
# The second mutation is deliberately NOT a second clause under test — it removes
# a BACKSTOP so the clause under test can be observed alone. Emptying the array by
# rebinding its opener avoids having to embed the entry's literal TAB in a bash
# literal, and leaves the original entry parked in an unread array.
#
# CONSEQUENCE WORTH KNOWING: if the ALLOWLIST were ever emptied for real, clause 4
# would stop covering empty discovery and this guard becomes the SOLE barrier
# between a broken checkout and a green tick over nothing.
R3="$WORK/empty_mut_noallow"
create_empty_repo "$R3"
if mutate "$R3" "$NEUTER_GUARD_OLD" "$NEUTER_GUARD_NEW" &&
	mutate "$R3" 'ALLOWLIST=(' 'ALLOWLIST=(); _parked=('; then
	expect "$R3" 0 'no operator-host paths' 'MUTANT: guard neutered + allowlist emptied -> vacuous GREEN over zero files'
fi

# ---------------------------------------------------------------------------
banner 'CLAUSE 4 — a stale allowlist entry is itself a finding'
# Down-only ratchet: an offender cannot be fixed while its excuse stays behind.
R="$(fresh stale)"
rm -f "$R/.github/actions/build-controls-wasm/action.yml"
git_at "$R" add -A
git_at "$R" commit -qam 'remove the allowlisted line'
expect "$R" 1 'STALE allowlist' 'an entry matching nothing is a FINDING (exit 1)'

R2="$(fresh stale_mut)"
rm -f "$R2/.github/actions/build-controls-wasm/action.yml"
git_at "$R2" add -A
git_at "$R2" commit -qam 'remove the allowlisted line'
if mutate "$R2" 'stale+=("$path -> $sub")' ':'; then
	expect "$R2" 0 'no operator-host paths' 'MUTANT: stale clause silenced -> green (red was clause 4 alone)'
fi

# ---------------------------------------------------------------------------
banner 'CLAUSE 5 — an allowlist entry is scoped to ITS OWN file'
# Regression test for a real defect in this gate's first draft: the entry was
# applied by substring alone, so `path: ~/wasi-sdk` appearing in ANY file was
# excused. One per-file entry silently became a whole-repo skip.
R="$(fresh scope)"
printf '# path: ~/wasi-sdk\n' >> "$R/renderer/.clang-tidy"
git_at "$R" commit -qam 'same substring, different file'
expect "$R" 1 '~/<dir> references' 'the allowlisted substring is NOT excused in another file'

# And prove the finding is the OTHER file, not the allowlisted one.
out="$(run_gate "$R")" || true
if printf '%s' "$out" | grep -qF 'renderer/.clang-tidy' &&
	! printf '%s' "$out" | grep -qF 'action.yml'; then
	ok 'the report names the unexcused file and still excuses the allowlisted one'
else
	bad 'clause 5 fired but named the wrong file(s)'
	printf '%s\n' "$out" | sed 's/^/       | /' >&2
fi

# ---------------------------------------------------------------------------
banner 'SCOPE EXCLUSIONS — generated, vendored and donation-record trees'
R="$(fresh excluded)"
mkdir -p "$R/output/go" "$R/renderer/lvgl/src"
printf 'const p = "/home/someoperator/x"\n' > "$R/output/go/x.go"
printf '/* /home/someoperator/y */\n' > "$R/renderer/lvgl/src/y.c"
printf 'Use /home/someoperator/scratch for scratch.\n' > "$R/DONATION_BRIEF.md"
git_at "$R" add -A
git_at "$R" commit -qam excluded
expect "$R" 0 'no operator-host paths' 'output/, renderer/lvgl/ and DONATION_*.md are out of scope'

# ---------------------------------------------------------------------------
banner 'DISCOVERY — an UNTRACKED file is scanned, not silently skipped'
# Discovery is `--cached --others --exclude-standard`, and the widening past the
# index is the point: a leak arrives in a NEW file far more often than it is
# added to an old one, so an index-only scan is blind in the common case. This
# canary is what stops the flags being "simplified" back later.
R="$(fresh untracked)"
printf '# adopted from ~/git/cc/elsewhere/x\n' > "$R/renderer/newfile.h"
# Deliberately NOT added to the index.
expect "$R" 1 '~/<dir> references' 'a never-staged file is still scanned'

# And an IGNORED path stays out — that is what keeps the widening cheap.
R="$(fresh ignored)"
printf 'scratchdir/\n' > "$R/.gitignore"
mkdir -p "$R/scratchdir"
printf 'notes: /home/someoperator/x\n' > "$R/scratchdir/notes.txt"
git_at "$R" add .gitignore
git_at "$R" commit -qm ignore
expect "$R" 0 'no operator-host paths' 'a gitignored path is out of scope'

# ---------------------------------------------------------------------------
banner 'SELF-EXCLUSION is scoped to ONE named file, never to its directory'
# The gate excludes its own source and its own canary suite, because both must
# contain the patterns verbatim. It must NOT exclude tools/lint/test/ wholesale:
# later gates will put their suites there and have no reason to carry an
# operator path, so a directory-wide carve-out would silently stop scanning
# files that ought to be scanned.
R="$(fresh sibling_suite)"
mkdir -p "$R/tools/lint/test"
printf '# see /home/someoperator/x\n' > "$R/tools/lint/test/some_other_gate_test.sh"
git_at "$R" add -A
git_at "$R" commit -qm sibling
expect "$R" 1 '/home/<user>/ references' 'a DIFFERENT suite under tools/lint/test/ is still scanned'

# ---------------------------------------------------------------------------
printf '\n== summary\n'
printf '  passed: %d\n' "$PASS"
printf '  failed: %d\n' "$FAILED"
if [ "$FAILED" -gt 0 ]; then
	printf '\033[31m[no-host-paths-test] RED\033[0m\n' >&2
	exit 1
fi
printf '\033[32m[no-host-paths-test]\033[0m ALL GREEN (%d cases)\n' "$PASS"
