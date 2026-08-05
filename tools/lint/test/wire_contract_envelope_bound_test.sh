#!/usr/bin/env bash
# wire_contract_envelope_bound_test.sh — canaries for the §9-G5 clauses that hold
# the PUBLISHED envelope schema's `tag` length bounds against the descriptor.
#
# SCOPE, stated so nobody reads a green here as covering more than it does: the
# `tag` bound clauses ONLY. The codec-header clauses have their own suite
# (wire_contract_codec_header_test.sh) and the rest of the checker — the derived
# §9 vectors, the §6 ping table, the §5 field sets — is covered by neither. A
# green here says the tag-bound clauses can still refuse; it says nothing about
# any other clause in the same script.
#
# WHY THESE CLAUSES EXIST AT ALL. The schema declared `maxLength: 63` while
# `ui.EventBinding.name` had been carried to 127, so the published validator that
# consumers are told to validate against REJECTED conformant envelopes — a real
# 73-character command id among them — for the whole window between those two
# commits. Nothing in this repository validates an INSTANCE against that schema,
# so the drift was structurally unobservable. These clauses are what make the
# next divergence red.
#
# THE DISCIPLINE, inherited from the sibling suite and worth restating:
#   Every case asserts the exact EXIT CODE and a substring NAMING the clause, so
#   a traceback can never be mistaken for a clause firing.
#   A NEIGHBOUR MUST STILL PASS. Each case names a CONTROL clause required to
#   stay green in the same run, or a mutation that broke the whole script would
#   satisfy "the named clause failed" by failing everything.
#
# HERMETIC. The checker takes --schema, so every mutation is applied to a COPY in
# a scratch directory and the tracked schema is never written. There is no
# restore to prove and no window in which an interrupted run leaves the tree
# modified.
#
# Usage: bash tools/lint/test/wire_contract_envelope_bound_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
SUT="$ROOT/tools/wire_contract_check.py"
SCHEMA="$ROOT/ui-event-envelope.schema.json"

for f in "$SUT" "$SCHEMA"; do
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

# The clause names this suite attributes to. Held in one place: a case that
# asserted a name inline would drift from the script silently.
MAX_CLAUSE='G5 tag maxLength'
MIN_CLAUSE='G5 tag minLength'
# The CONTROL. It reads the same schema file and is untouched by a bounds
# mutation, so its survival is what proves the damage is confined.
CONTROL_CLAUSE='G5 schema is a closed map'

# run <schema-path> -> sets RUN_RC / RUN_OUT / RUN_TOTAL.
# The checker is invoked BARE and its own status is read on the next line. Piping
# it into anything would report the FILTER status, which is the exact defect this
# repo has paid for more than once.
run() {
	set +e
	RUN_OUT="$(python3 "$SUT" --schema "$1" 2>&1)"
	RUN_RC=$?
	set -e
	RUN_TOTAL="$(grep -cE '^  (ok|FAIL) ' <<<"$RUN_OUT" || true)"
}

# assert_refusal <mutant-schema> <clause-substring> <case-name>
# One case: the mutant must exit 1, NAME the clause, and leave the control green.
assert_refusal() {
	local mutant="$1" clause="$2" name="$3"
	run "$mutant"
	if [ "$RUN_RC" -ne 1 ]; then
		bad "$name: expected exit 1, got $RUN_RC"
		return
	fi
	if ! contains "$RUN_OUT" "$clause"; then
		bad "$name: exit 1 but the output never named /$clause/ — a non-zero exit
  attributed to nothing is indistinguishable from a broken script"
		return
	fi
	if ! contains "$RUN_OUT" "  ok   $CONTROL_CLAUSE"; then
		bad "$name: the CONTROL clause did not survive — this mutation broke more
  than the clause under test, so the refusal attributes nothing"
		return
	fi
	ok "$name (exit 1, named the clause, control survived)"
}

printf '\n\033[1m[wire-contract-envelope-bound] baseline — the tracked schema must PASS\033[0m\n'
run "$SCHEMA"
if [ "$RUN_RC" -eq 0 ]; then
	ok "tracked schema: exit 0 ($RUN_TOTAL assertions)"
else
	bad "tracked schema: expected exit 0, got $RUN_RC — nothing below can attribute
  anything while the unmutated tree is already red"
	printf '%s\n' "$RUN_OUT" >&2
fi

printf '\n\033[1m[wire-contract-envelope-bound] maxLength\033[0m\n'
M="$WORK/max-narrowed.json"
cp "$SCHEMA" "$M"
if mutate_file "$M" '"maxLength": 127' '"maxLength": 63'; then
	assert_refusal "$M" "$MAX_CLAUSE" "a schema narrower than the proto is refused"
else
	bad "maxLength mutation did not land — the anchor is absent or ambiguous, so
  the case below would have proved nothing"
fi

M="$WORK/max-widened.json"
cp "$SCHEMA" "$M"
if mutate_file "$M" '"maxLength": 127' '"maxLength": 255'; then
	assert_refusal "$M" "$MAX_CLAUSE" "a schema WIDER than the proto is refused too"
else
	bad "maxLength widening mutation did not land"
fi

printf '\n\033[1m[wire-contract-envelope-bound] minLength\033[0m\n'
M="$WORK/min-moved.json"
cp "$SCHEMA" "$M"
if mutate_file "$M" '"minLength": 1' '"minLength": 2'; then
	assert_refusal "$M" "$MIN_CLAUSE" "a moved minLength is refused"
else
	bad "minLength mutation did not land"
fi

printf '\n'
if [ "$fails" -eq 0 ]; then
	printf '\033[32m[wire-contract-envelope-bound]\033[0m all cases held\n'
	exit 0
fi
printf '\033[31m[wire-contract-envelope-bound]\033[0m %d case(s) failed\n' "$fails" >&2
exit 1
