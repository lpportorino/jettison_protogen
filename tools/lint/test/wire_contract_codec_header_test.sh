#!/usr/bin/env bash
# wire_contract_codec_header_test.sh — canaries for the §2 / §9-G2 clauses of
# tools/wire_contract_check.py (`check_codec_header`).
#
# SCOPE, STATED SO A GREEN IS NOT READ AS MORE THAN IT IS. This suite covers the
# codec-header clauses ONLY. The rest of the wire-contract checker — the §9
# golden vectors derived from the descriptor set, §5, §6, §8 — is not driven
# here; tools/wire-contract-proofs/mutation_proof.sh is the probe for the §9
# derivation. A green here says the codec-header clauses can still refuse, and
# says nothing about the other assertions in that script.
#
# WHY THESE CLAUSES NEED A CANARY MORE THAN MOST. Every other check in that
# script DERIVES its expectation from the descriptor set, so a broken extractor
# surfaces as a derivation error. These clauses compare two pieces of PROSE
# against each other, and prose has no compiler: a regex that stops matching, a
# renamed column, a fenced block that moved — each yields "nothing to compare",
# which without the script's own found() floors would be a silent pass.
#
# WHAT EACH CASE PROVES, and it is more than that it went red:
#
#   THE MUTATION LANDED. Every case goes through mutate_file, which refuses an
#   absent or ambiguous anchor. A mutation that silently matched nothing yields a
#   mutant identical to the original, whose unchanged verdict would then read as
#   attribution while proving the exact opposite.
#
#   FAIL, NOT ERROR. The checker exits 1 for findings and 2 for unreadable
#   inputs, so a traceback can never be mistaken for a clause firing. Every case
#   asserts the exact exit code AND a substring naming the clause.
#
#   THE ASSERTION COUNT SEPARATES A FIRED CLAUSE FROM A DEAD BODY. A case marked
#   `same` requires the mutant to execute exactly as many assertions as the
#   baseline, with one flipped from ok to FAIL; an early return or a traceback
#   changes that total. A case marked `fewer` is one whose mutation REMOVES a
#   clause's subject — a column, a name suffix — so running fewer is the
#   behaviour under test, and the case asserts the drop plus a floor that keeps
#   the rest of the body from having collapsed unnoticed.
#
#   A NEIGHBOUR MUST STILL PASS. Each case names a CONTROL clause required to
#   remain in the ok list. Without it, a mutant that broke the whole section
#   would satisfy "the named clause failed" by failing everything. Where a
#   mutation necessarily trips a coupled pair — a width and the concatenation it
#   feeds are one fact checked twice — the case says so and takes its control
#   from the OTHER half of the check, which is what proves the damage is
#   confined.
#
# HERMETIC. The checker takes --doc, so every mutation is applied to a COPY in a
# scratch directory and the tracked doc is never written. There is no restore to
# prove and no window in which an interrupted run leaves the tree modified.
#
# Usage: bash tools/lint/test/wire_contract_codec_header_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
SUT="$ROOT/tools/wire_contract_check.py"
DOC="$ROOT/docs/INTERFACE-CONTRACTS.md"

for f in "$SUT" "$DOC"; do
	[ -f "$f" ] || {
		printf '\033[31mFAIL\033[0m — not found: %s\n' "$f" >&2
		exit 3
	}
done

MUTATE_LIB="$SCRIPT_DIR/lib_mutate.sh"
[ -f "$MUTATE_LIB" ] || {
	printf '\033[31mFAIL\033[0m — missing mutation primitive at %s\n' "$MUTATE_LIB" >&2
	printf '  Every proof here is a mutation, so without it this suite cannot break\n' >&2
	printf '  anything and its green would mean nothing.\n' >&2
	exit 3
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fails=0
ok() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
bad() {
	printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2
	fails=$((fails + 1))
}

# The primitives prove they can REFUSE before anything depends on them.
mutate_selftest "$WORK/mutate-selftest" || fails=$((fails + 1))
contains_selftest "$WORK/contains-selftest" || fails=$((fails + 1))

# run <doc-path> -> sets RUN_RC / RUN_OUT / RUN_TOTAL.
# The checker is invoked BARE and its own status is read on the next line. Piping
# it into anything would report the FILTER status, which is the exact defect this
# repo has paid for more than once.
run() {
	set +e
	RUN_OUT="$(python3 "$SUT" --doc "$1" 2>&1)"
	RUN_RC=$?
	set -e
	# Assertions the body actually executed, counted off the per-clause report
	# rather than off the summary line, which prints a number only on a run that
	# reached the end.
	RUN_TOTAL="$(grep -cE '^  (ok|FAIL) ' <<< "$RUN_OUT" || true)"
}

printf '\n\033[1m[wire-contract-codec] baseline — the tracked doc must PASS\033[0m\n'
run "$DOC"
if [ "$RUN_RC" -eq 0 ]; then
	ok "tracked doc: exit 0 ($RUN_TOTAL assertions)"
else
	bad "tracked doc: expected exit 0, got $RUN_RC — nothing below can attribute
  anything while the unmutated tree is already red"
fi
BASE_TOTAL="$RUN_TOTAL"
FLOOR=$((BASE_TOTAL / 2))
if [ "$BASE_TOTAL" -ge 150 ]; then
	ok "baseline is non-vacuous ($BASE_TOTAL assertions)"
else
	bad "baseline executed only $BASE_TOTAL assertions — the report shape changed
  and every count comparison below is meaningless"
fi
while IFS= read -r clause; do
	if contains "$RUN_OUT" "  ok   $clause"; then
		ok "baseline asserts: $clause"
	else
		bad "baseline does NOT assert [$clause] — a case below would then prove
  nothing, because the clause it targets never ran"
	fi
done <<'CLAUSES'
§2 codec-header table rows
§2 codec-header total width
§2 pts_ns name suffix matches its Unit column
§9 G2 field names and order match §2's table
§9 G2 flattened bytes equal the annotated lines concatenated
§9 G2 prose byte count
CLAUSES

# case_run <name> <same|fewer> <expected-finding> <control-clause> <anchor> <new>
case_run() {
	local name="$1" mode="$2" finding="$3" control="$4" old="$5" new="$6"
	local doc="$WORK/doc-$RANDOM$RANDOM.md"

	cp -- "$DOC" "$doc"
	if ! mutate_file "$doc" "$old" "$new"; then
		bad "$name: the mutation did not land — nothing was proven"
		return
	fi
	run "$doc"

	if [ "$RUN_RC" -ne 1 ]; then
		bad "$name: expected exit 1 (a FINDING), got $RUN_RC — exit 2 is an
  unreadable input and 0 is no refusal at all; neither is this clause firing"
		return
	fi
	if ! contains "$RUN_OUT" "  FAIL $finding"; then
		bad "$name: it went red, but not for [$finding] — an unattributed red is
  indistinguishable from a neighbouring clause refusing the same input"
		return
	fi
	if ! contains "$RUN_OUT" "  ok   $control"; then
		bad "$name: the control clause [$control] stopped passing — the mutant broke
  more than the clause under test, so the red is not attributable"
		return
	fi
	case "$mode" in
	same)
		if [ "$RUN_TOTAL" -ne "$BASE_TOTAL" ]; then
			bad "$name: executed $RUN_TOTAL assertions against a baseline of
  $BASE_TOTAL — the body did not complete, so a clause was not merely flipped"
			return
		fi
		;;
	fewer)
		if [ "$RUN_TOTAL" -ge "$BASE_TOTAL" ]; then
			bad "$name: expected FEWER assertions than the baseline $BASE_TOTAL,
  got $RUN_TOTAL — the mutation was supposed to remove a clause subject"
			return
		fi
		if [ "$RUN_TOTAL" -lt "$FLOOR" ]; then
			bad "$name: executed $RUN_TOTAL assertions, below the floor $FLOOR — the
  run collapsed rather than losing the one clause under test"
			return
		fi
		;;
	*)
		bad "$name: unknown count mode [$mode] (want same or fewer)"
		return
		;;
	esac
	ok "$name (exit 1, $RUN_TOTAL assertions, control held)"
}

printf '\n\033[1m[wire-contract-codec] the UNIT declaration cannot be dropped\033[0m\n'

# 1. Un-declaring the unit by renaming the field back to its bare form in §2
#    alone. This is exactly the regression §2.1 exists to prevent, and it is the
#    shape a well-meaning "the consumers call it pts" edit would take. FEWER by
#    one: a bare name has no unit suffix, so its suffix-vs-column clause has no
#    subject left to assert.
case_run 'a bare pts in §2 while G2 still says pts_ns' \
	fewer \
	"§9 G2 field names and order match §2's table" \
	'§2 codec-header total width' \
	'| 0  | `pts_ns`      | uint64 | LE | ns |' \
	'| 0  | `pts`         | uint64 | LE | ns |'

# 2. The name and the Unit column disagreeing. Two homes for one fact, and a
#    reader who consults only one of them is misled either way round.
case_run 'duration_ns declared as milliseconds in the Unit column' \
	same \
	'§2 duration_ns name suffix matches its Unit column' \
	'§2 duration_ns offset' \
	'| 8  | `duration_ns` | uint64 | LE | ns |' \
	'| 8  | `duration_ns` | uint64 | LE | ms |'

# 3. Blanking a unit outright. `unspecified` is a LEGAL value and a blank is not:
#    the difference between "nobody knows" and "nobody wrote it down" is the
#    whole subject of §2.1, so an empty cell must refuse.
case_run 'system_time left with an empty Unit cell' \
	same \
	'§2 system_time declares a unit' \
	'§2 system_time offset' \
	'| 16 | `system_time` | uint64 | LE | unspecified |' \
	'| 16 | `system_time` | uint64 | LE |  |'

# 4. Removing the Unit COLUMN. The table selector keys on that header, so this is
#    the reformat that would otherwise make the whole section unassertable — and
#    the floor must SAY it found nothing rather than pass over an empty set.
case_run 'the Unit column deleted from §2 entirely' \
	fewer \
	'§2 codec-header table rows' \
	'§9 G5 envelope vectors' \
	'| Offset | Field | Type | Endianness | Unit |' \
	'| Offset | Field | Type | Endianness | Note |'

printf '\n\033[1m[wire-contract-codec] the LAYOUT cannot drift between its two homes\033[0m\n'

# 5. An offset that no longer follows from the widths above it. The silent one: a
#    reader inserting a field renumbers by hand, and every byte on the wire stays
#    legal while the table stops describing them.
case_run 'system_time moved to a bogus offset in §2' \
	same \
	'§2 system_time offset' \
	'§2 duration_ns offset' \
	'| 16 | `system_time` | uint64 | LE | unspecified |' \
	'| 17 | `system_time` | uint64 | LE | unspecified |'

# 6. A field in G2 carrying fewer bytes than §2 declares. COUPLED BY
#    CONSTRUCTION: a width mismatch necessarily also breaks the concatenation,
#    because they are one fact checked twice. The control is therefore taken from
#    the §2 half, which must be untouched — that is what shows the damage is
#    confined to the vector rather than the table.
case_run 'G2 system_time truncated to four bytes under a uint64 row' \
	same \
	"§9 G2 system_time byte count matches §2's system_time width" \
	'§2 system_time offset' \
	'03 00 00 00 00 00 00 00   # system_time = 3   (u64 LE)' \
	'03 00 00 00   # system_time = 3   (u64 LE)'

# 7. A corrupted flattened line. The annotated block and the flattened block are
#    two spellings of one vector, and a consumer parity test copies the flattened
#    one — so a divergence there ships wrong bytes to the fleet.
case_run 'one byte flipped in G2 flattened but not in the annotated lines' \
	same \
	'§9 G2 flattened bytes equal the annotated lines concatenated' \
	'§2 codec-header total width' \
	'01 00 00 00 00 00 00 00 02 00 00 00 00 00 00 00 03 00 00 00 00 00 00 00 01' \
	'01 00 00 00 00 00 00 00 02 00 00 00 00 00 00 00 03 00 00 00 00 00 00 00 00'

# 8. A big-endian annotated line under an LE table. A byte-count check alone
#    passes this — it is caught only because the decode is asserted against the
#    STATED value, which is why that clause exists.
case_run 'pts_ns written big-endian in G2 under an LE table' \
	same \
	'§9 G2 pts_ns decodes little-endian to its stated value' \
	'§2 pts_ns endianness' \
	'01 00 00 00 00 00 00 00   # pts_ns      = 1   (u64 LE)' \
	'00 00 00 00 00 00 00 01   # pts_ns      = 1   (u64 LE)'

# 9. The sentence under G2 restating a header size the table no longer supports.
#    It reads as commentary, which is precisely why it rots unwatched.
case_run 'the sentence under G2 claiming the wrong header size' \
	same \
	'§9 G2 prose byte count' \
	'§2 codec-header total width' \
	'(25 bytes; byte 24 = ' \
	'(24 bytes; byte 24 = '

printf '\n'
if [ "$fails" -gt 0 ]; then
	printf '\033[31m[wire-contract-codec] %d case(s) FAILED\033[0m\n' "$fails" >&2
	exit 1
fi
printf '\033[32m[wire-contract-codec]\033[0m every codec-header clause refused for its own reason\n'
