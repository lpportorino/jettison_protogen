#!/usr/bin/env bash
# lane_canary.sh — prove a `scratchcard-lane` clause fails for ITS OWN reason.
#
# A GATE NOBODY HAS WATCHED FAIL IS A PRESCRIPTION, NOT A GATE. `scratchcard-lane`
# is green, and green is uninformative until failure has been demonstrated —
# the commonest way a gate dies is by silently checking nothing, and that state
# emits exactly the output a clean run does.
#
# A RED IS NOT ENOUGH; IT MUST NAME ITS CLAUSE. The lane has four clauses and
# several of them would refuse some of the same inputs, so a bare red is
# compatible with the clause under test being DEAD. Each case below therefore:
#
#   1. asserts the mutation LANDED — new text present AND old text absent, on
#      the exact bytes. A mutation that matched nothing yields a mutant
#      identical to the original, whose green then reads as attribution while
#      proving the opposite.
#   2. requires a FAIL (exit 1), never an ERROR (exit 3). A mutant that breaks
#      the namespace load reddens the file while executing nothing.
#   3. requires the EXPECTED clause's own message in the output.
#   4. requires a NAMED NEIGHBOUR clause to stay green on the same mutant, so
#      the red is attributed rather than merely observed.
#   5. restores, and asserts the restore.
#
# HOST-ONLY: it drives `tools/uber.sh`, which drives docker.
#
#   0  every case attributed        1  a case FAILED        3  CANNOT RUN

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PASS=0
FAIL=0

ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
bad() {
	FAIL=$((FAIL + 1))
	printf '  \033[31mFAIL\033[0m %s\n' "$1"
	[ $# -gt 1 ] && printf '%s\n' "$2" | sed 's/^/       | /'
	return 0
}
cannot_run() {
	printf '\033[31m[lane-canary] CANNOT RUN\033[0m — %s\n' "$1"
	printf '  %s\n' "$2"
	exit 3
}

command -v docker >/dev/null 2>&1 ||
	cannot_run "docker is not on PATH" "This suite runs the lane inside the pinned image."
[ -f "$ROOT/renderer/output/controls.wasm" ] ||
	cannot_run "renderer/output/controls.wasm is missing" "Build it: make -f renderer.mk wasm"

run_lane() {
	(cd "$ROOT" && timeout 900 tools/uber.sh 'cd tools/scratchcard && clojure -M:render-lane' 2>&1)
}

# mutate FILE OLD NEW — replaces exactly one occurrence, asserting it landed.
mutate() {
	local file="$1" old="$2" new="$3"
	cp -- "$file" "$file.canary-bak"
	python3 - "$file" "$old" "$new" <<'PY' || return 1
import sys, io
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(p, encoding='utf-8').read()
if s.count(old) != 1:
    sys.stderr.write(f"anchor appears {s.count(old)} times, expected 1\n")
    sys.exit(1)
io.open(p, 'w', encoding='utf-8').write(s.replace(old, new))
PY
	# Assert the mutation LANDED, both directions, on the exact bytes.
	grep -qF -- "$new" "$file" || { printf '   mutation did NOT land (new text absent)\n' >&2; return 1; }
	grep -qF -- "$old" "$file" && { printf '   mutation did NOT land (old text still present)\n' >&2; return 1; }
	return 0
}

restore() {
	local file="$1"
	mv -f -- "$file.canary-bak" "$file"
	# `env -u GIT_DIR -u GIT_WORK_TREE` before `git -C`, the form uber.sh uses:
	# a bare `git -C` is overridden by a stray GIT_DIR in the environment, so the
	# restore check could silently interrogate another repository. lint-sh bans
	# the bare form for exactly this reason.
	env -u GIT_DIR -u GIT_WORK_TREE git -C "$ROOT" diff --quiet -- "$file" ||
		bad "restore FAILED for $file — the tree is dirty"
}

# case NAME FILE OLD NEW EXPECT_RE NEIGHBOUR_RE
case_attributed() {
	local name="$1" file="$2" old="$3" new="$4" expect="$5" neighbour="$6"
	if ! mutate "$file" "$old" "$new"; then
		bad "$name: could not apply the mutation"
		[ -f "$file.canary-bak" ] && mv -f -- "$file.canary-bak" "$file"
		return 0
	fi
	local out rc
	out="$(run_lane)"
	rc=$?
	restore "$file"

	if [ "$rc" -eq 3 ]; then
		bad "$name: the lane reported CANNOT RUN (3) — an ERROR wearing a verdict's place" "$out"
		return 0
	fi
	if [ "$rc" -ne 1 ]; then
		bad "$name: expected exit 1 (FAIL), got $rc" "$out"
		return 0
	fi
	if ! printf '%s' "$out" | grep -qE "$expect"; then
		bad "$name: red, but the expected clause never fired (/$expect/)" "$out"
		return 0
	fi
	if ! printf '%s' "$out" | grep -qE "^  ok.*$neighbour"; then
		bad "$name: the NEIGHBOUR clause (/$neighbour/) did not stay green — the red is unattributed" "$out"
		return 0
	fi
	ok "$name: FAIL(1), its own clause fired, neighbour stayed green"
}

printf '[lane-canary] proving each clause fails for its own reason\n'

# ── THE MATRIX-SIZE CLAUSE IS NOT ATTRIBUTABLE, and that is a finding ─────
#
# It has no case here, deliberately, and the reason belongs on the record
# rather than as a silent omission.
#
# Two mutations were tried. SHRINKING `default-resolutions` leaves the lane
# GREEN, because `expand` and `expected-count` both derive from that list — the
# clause checks internal consistency, not that the default set has any
# particular size, so its wording ("matrix is EXACTLY 42 cells") implies more
# than it can see. Making `expand` diverge from `expected-count` instead does
# turn the lane red, but NOT via this clause: `run/regenerate!` refuses a
# mismatched matrix first and throws EMPTY_MATRIX, so the whole run fails and
# EVERY clause degrades together. The neighbour check caught that — the red was
# unattributed.
#
# That upstream guard is defence in depth and is correct. The consequence is
# that this clause is SHADOWED: no input reaches it that the orchestrator has
# not already rejected. A canary here would be a red observed rather than
# attributed, which `.claude/rules/gate-enforcement.md` §2 refuses.
#
# Naming the gap is the requirement; closing it would mean either removing the
# upstream guard (worse) or giving the clause a property the orchestrator does
# not already enforce.

# ── CASE 2: the vanilla==stock clause ──────────────────────────────────────
# Make family 1 render as family 0. vanilla != stock must fire; the matrix-size
# clause must stay green, because the cell COUNT is untouched.
case_attributed \
	'vanilla-equals-stock' \
	"$ROOT/tools/scratchcard/src/scratchcard/run.clj" \
	'(when (pos? (long family)) (host/set-theme-family! h* family))' \
	'(when (> (long family) 1) (host/set-theme-family! h* family))' \
	'FAIL: vanilla != stock' \
	'matrix is EXACTLY'

printf '\n'
if [ "$FAIL" -eq 0 ]; then
	printf '\033[32m[lane-canary] GREEN — %s case(s) attributed\033[0m\n' "$PASS"
	exit 0
fi
printf '\033[31m[lane-canary] RED — %s of %s case(s) failed\033[0m\n' "$FAIL" "$((PASS + FAIL))"
exit 1
