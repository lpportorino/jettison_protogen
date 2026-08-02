#!/usr/bin/env bash
# e2e_canary.sh — prove `daemon_e2e.sh`'s EXIT-STATUS arms fail for their own
# reason.
#
# A GATE NOBODY HAS WATCHED FAIL IS A PRESCRIPTION, NOT A GATE — and this suite
# exists because that was measured here rather than assumed. Before those arms
# landed, a client mutated to exit 7 unconditionally left FOUR arms of
# `daemon_e2e.sh` green: each captures the client with `out="$("$CLIENT" …)"`,
# and command substitution discards the status into the assignment. The two arms
# that DID redden piped into `grep`, so `pipefail` propagated the code by
# accident, and both reported it as "the daemon did not answer ping" — a client
# exit-code defect wearing a transport failure's message.
#
# So a bare red from that suite proves nothing about the exit-status arms: the
# old arms would have reddened too. Each case below therefore:
#
#   1. asserts the mutation LANDED — new text present AND old text absent, on
#      the exact bytes. A mutation that matched nothing yields a mutant
#      identical to the original, whose green then reads as attribution while
#      proving the opposite.
#   2. requires a FAIL (exit 1), never an ERROR (exit 3). A mutant that broke the
#      client outright would redden the suite while executing no assertion.
#   3. requires the EXPECTED arm's own message, which names the code it wanted
#      and the code it got — never merely "something went red".
#   4. requires a NAMED NEIGHBOUR arm to stay green on the same mutant.
#   5. restores, and asserts the restore against HEAD.
#
# CASE 1 IS THE ATTRIBUTING ONE. It moves a code that ONLY an exit-status arm
# reads, so every pre-existing arm stays green and the red can have come from
# nowhere else. Case 2 moves the code every reply shares, which reddens older
# arms too — kept because it is the shape the original measurement took, and
# its neighbour check is what keeps it honest.
#
# IT DOES NOT RUN THE UNMUTATED SUITE, and that is stated rather than implied.
# The green direction is `make -f renderer.mk scratchcard-e2e` itself; what these
# cases add is that the arms can go red, and for their own reason. Reading this
# suite as covering both directions would credit it with a run it never makes.
#
# HOST-ONLY: it drives `daemon_e2e.sh`, which drives `docker` directly, and the
# toolchain image carries no docker CLI.
#
#   0  every case attributed        1  a case FAILED        3  CANNOT RUN

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SUITE="$ROOT/tools/scratchcard/test/daemon_e2e.sh"
CLIENT_SRC="$ROOT/tools/scratchcard/bin/scratchcard.bb"
PASS=0
FAIL=0

ok() {
	PASS=$((PASS + 1))
	printf '  ok   %s\n' "$1"
}
bad() {
	FAIL=$((FAIL + 1))
	printf '  \033[31mFAIL\033[0m %s\n' "$1"
	[ $# -gt 1 ] && printf '%s\n' "$2" | sed 's/^/       | /'
	return 0
}
cannot_run() {
	printf '\033[31m[e2e-canary] CANNOT RUN\033[0m — %s\n' "$1"
	printf '  %s\n' "$2"
	exit 3
}

command -v docker >/dev/null 2>&1 ||
	cannot_run "docker is not on PATH" "The suite under test spawns the daemon container directly."
[ -f "$ROOT/renderer/output/controls.wasm" ] ||
	cannot_run "renderer/output/controls.wasm is missing" "Build it: make -f renderer.mk wasm"
[ -f "$SUITE" ] ||
	cannot_run "the suite under test is missing: $SUITE" "A moved suite must not read as a clean canary."

# THE MUTATED FILE MUST BE CLEAN vs HEAD, because `restore` verifies the put-back
# with `git diff --quiet`. On a dirty checkout that check reports a FAIL for a
# file this suite restored PERFECTLY — a precondition failure wearing a verdict's
# colour, which is what the exit codes above exist to prevent.
env -u GIT_DIR -u GIT_WORK_TREE git -C "$ROOT" diff --quiet -- "$CLIENT_SRC" ||
	cannot_run "$CLIENT_SRC has uncommitted changes" \
		"This suite mutates it and checks the restore against HEAD. Commit or stash first."

# mutate OLD NEW — replaces exactly one occurrence, asserting it landed.
#
# THE LANDED-ASSERTION IS DONE ON EXACT BYTES, NOT WITH `grep -F`, and that is a
# measured correction rather than a preference. `grep -F` is LINE-ORIENTED: a
# multi-line pattern is treated as a set of alternatives, so "the old text is
# gone" succeeds on any surviving line of it. Driven with a two-line anchor, the
# grep form reported `mutation did NOT land` for a replacement that had landed
# perfectly — a false FAIL, which is the same class of wrong colour the exit
# codes here exist to separate. Substring containment in python has no line
# grain and cannot make that mistake.
mutate() {
	local old="$1" new="$2"
	cp -- "$CLIENT_SRC" "$CLIENT_SRC.canary-bak"
	python3 - "$CLIENT_SRC" "$old" "$new" <<'PY' || return 1
import sys, io
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(p, encoding='utf-8').read()
if s.count(old) != 1:
    sys.stderr.write(f"   anchor appears {s.count(old)} times, expected 1\n")
    sys.exit(1)
io.open(p, 'w', encoding='utf-8').write(s.replace(old, new))
after = io.open(p, encoding='utf-8').read()
if new not in after:
    sys.stderr.write("   mutation did NOT land (new text absent)\n")
    sys.exit(1)
if old in after:
    sys.stderr.write("   mutation did NOT land (old text still present)\n")
    sys.exit(1)
PY
	return 0
}

restore() {
	mv -f -- "$CLIENT_SRC.canary-bak" "$CLIENT_SRC"
	# `env -u GIT_DIR -u GIT_WORK_TREE` before `git -C`, the form uber.sh uses:
	# a bare `git -C` is overridden by a stray GIT_DIR in the environment, so the
	# restore check could silently interrogate another repository. lint-sh bans
	# the bare form for exactly this reason.
	env -u GIT_DIR -u GIT_WORK_TREE git -C "$ROOT" diff --quiet -- "$CLIENT_SRC" ||
		bad "restore FAILED for $CLIENT_SRC — the tree is dirty"
}

# case NAME OLD NEW EXPECT_RE NEIGHBOUR_RE
case_attributed() {
	local name="$1" old="$2" new="$3" expect="$4" neighbour="$5"
	if ! mutate "$old" "$new"; then
		bad "$name: could not apply the mutation"
		[ -f "$CLIENT_SRC.canary-bak" ] && mv -f -- "$CLIENT_SRC.canary-bak" "$CLIENT_SRC"
		return 0
	fi
	local out rc
	out="$(bash "$SUITE" 2>&1)"
	rc=$?
	restore "$CLIENT_SRC"

	if [ "$rc" -eq 3 ]; then
		bad "$name: the suite reported CANNOT RUN (3) — an ERROR wearing a verdict's place" "$out"
		return 0
	fi
	if [ "$rc" -ne 1 ]; then
		bad "$name: expected exit 1 (FAIL), got $rc" "$out"
		return 0
	fi
	if ! printf '%s' "$out" | grep -qE "$expect"; then
		bad "$name: red, but the expected arm never fired (/$expect/)" "$out"
		return 0
	fi
	if ! printf '%s' "$out" | grep -qE "^  ok.*$neighbour"; then
		bad "$name: the NEIGHBOUR arm (/$neighbour/) did not stay green — the red is unattributed" "$out"
		return 0
	fi
	ok "$name: FAIL(1), its own arm fired, neighbour stayed green"
}

printf '[e2e-canary] proving each exit-status arm fails for its own reason\n'

# ── CASE 1: the usage exit code ────────────────────────────────────────────
# The usage branch's status is read by NOTHING except the arm under test — the
# suite's own `HASH="$("$CLIENT" 2>&1 | awk …)"` reads its STDOUT and discards
# the code. So every pre-existing arm stays green and this red is attributable
# to the exit-status arm alone. `(System/exit 2)` appears twice in the client, so
# the anchor carries the usage form's trailing parens — the only spelling of it
# that is unique.
case_attributed \
	'usage-exits-2' \
	'(System/exit 2)))))' \
	'(System/exit 0)))))' \
	'no subcommand prints usage and exits 2: expected exit 2, got 0' \
	'a successful ping exits 0'

# ── CASE 2: the reply status ───────────────────────────────────────────────
# Every command that reaches the daemon emits through `emit!`, so this also
# reddens the two older arms that propagate a status accidentally through
# `pipefail`. The neighbour named below is one that reads STDOUT only, and it
# must stay green — otherwise the red says nothing about the arm under test.
case_attributed \
	'ping-exits-0' \
	'(System/exit (if (and (map? resp) (false? (:ok resp))) 1 0))' \
	'(System/exit 7)' \
	'a successful ping exits 0: expected exit 0, got 7' \
	'a traversing card slug is refused over the wire'

printf '\n'
if [ "$FAIL" -eq 0 ]; then
	printf '\033[32m[e2e-canary] GREEN — %s case(s) attributed\033[0m\n' "$PASS"
	exit 0
fi
printf '\033[31m[e2e-canary] RED — %s of %s case(s) failed\033[0m\n' "$FAIL" "$((PASS + FAIL))"
exit 1
