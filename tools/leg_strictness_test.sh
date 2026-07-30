#!/usr/bin/env bash
# Canary for generate-protos.sh's LEG STRICTNESS PREAMBLE.
#
# WHAT IS GUARDED: run_generation() prepends `set -euo pipefail` to every language
# payload before handing it to `bash -c`. Each payload then re-arms a bare `set -e`,
# which clears NEITHER -u NOR -o pipefail — that is the whole mechanism, and it is
# the thing this suite refuses to take on faith.
#
# WHY IT EXISTS: without pipefail a leg cannot fail on the LEFT of a pipe, which is
# how `cargo build 2>&1 | tail -5` reported tail's status and a broken Rust build
# exited 0 while printing "completed successfully". A fix that restores a gate's
# ability to fail owes a demonstration that it fails (.claude/rules/gate-enforcement.md §2).
#
# HERMETIC: every case runs in a throw-away directory over synthetic payloads. The
# tracked tree is READ for one anchor assertion and never written.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBJECT="$ROOT/generate-protos.sh"
PASS=0
FAILED=0

ok() { printf '  \033[32mok\033[0m   %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2; FAILED=$((FAILED + 1)); }

# --- NON-VACUITY: the anchor must exist, or every case below is testing nothing ---
if [ ! -r "$SUBJECT" ]; then
  printf '\033[31m[leg-strictness] CANNOT RUN\033[0m — %s is unreadable.\n' "$SUBJECT" >&2
  exit 3
fi
anchor_count="$(grep -c '^    local full_script="set -euo pipefail$' "$SUBJECT")"
if [ "$anchor_count" -ne 1 ]; then
  printf '\033[31m[leg-strictness] FAIL\033[0m — the strictness preamble is not at its one home.\n' >&2
  printf '  expected exactly 1 `local full_script="set -euo pipefail` line, found %s.\n' \
    "$anchor_count" >&2
  printf '  If the prepend moved, move this canary with it — do not delete the assertion.\n' >&2
  exit 1
fi
ok "the strictness preamble is present exactly once in generate-protos.sh"

# --- Every leg payload still re-arms its own `set -e` (the mechanism's premise) ---
leg_count="$(grep -c "^set -e$" "$SUBJECT")"
if [ "$leg_count" -lt 11 ]; then
  bad "expected >= 11 payload 'set -e' preambles, found $leg_count — a leg may have been dropped"
else
  ok "all $leg_count leg payloads re-arm set -e (which does NOT clear -u/pipefail)"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# A payload shaped exactly like a real leg: bare `set -e`, then a pipeline whose
# LEFT side fails. This is the rust leg's shape reduced to its essentials.
PAYLOAD='
set -e
false | tail -5
echo "leg completed successfully"
'

# --- CASE 1: BASELINE — without the preamble the failure is SWALLOWED ---
set +e
bash -c "$PAYLOAD" >"$WORK/base.out" 2>&1
base_rc=$?
set -e
if [ "$base_rc" -eq 0 ] && grep -q "leg completed successfully" "$WORK/base.out"; then
  ok "baseline: a failing left-of-pipe exits 0 and prints success (the defect)"
else
  bad "baseline did not reproduce the defect (rc=$base_rc) — the fixture no longer models a leg"
fi

# --- CASE 2: THE CLAUSE UNDER TEST — with the preamble the leg REFUSES ---
set +e
bash -c "set -euo pipefail
$PAYLOAD" >"$WORK/strict.out" 2>&1
strict_rc=$?
set -e
if [ "$strict_rc" -ne 0 ] && ! grep -q "leg completed successfully" "$WORK/strict.out"; then
  ok "strict: the same leg fails (rc=$strict_rc) and never claims success"
else
  bad "strict: the preamble did NOT make the leg fail (rc=$strict_rc) — pipefail is not reaching it"
fi

# --- CASE 3: ATTRIBUTION. Silencing pipefail alone must turn case 2 green again,
# while a NEIGHBOURING clause STILL REFUSES on the same mutant — a control that
# merely "stays green" is satisfied by a dead check and proves nothing. ---
set +e
bash -c "set -euo pipefail
set +o pipefail
$PAYLOAD" >"$WORK/mutant.out" 2>&1
mutant_rc=$?
set -e
if [ "$mutant_rc" -eq 0 ]; then
  ok "attribution: silencing ONLY pipefail turns the red green — case 2's red was pipefail"
else
  bad "attribution: mutant still failed (rc=$mutant_rc) — case 2's red came from another clause"
fi

# The refusing neighbour, on the SAME mutant, in a DIFFERENT failure class:
# nounset is still armed, so an unset reference must still abort.
set +e
bash -c "set -euo pipefail
set +o pipefail
set -e
echo \"\$DEFINITELY_UNSET_VARIABLE\"
echo 'leg completed successfully'" >"$WORK/control.out" 2>&1
control_rc=$?
set -e
if [ "$control_rc" -ne 0 ] && ! grep -q "leg completed successfully" "$WORK/control.out"; then
  ok "control: with pipefail silenced, nounset STILL REFUSES (rc=$control_rc) — not a dead shell"
else
  bad "control: nounset did not refuse under the mutant (rc=$control_rc) — the mutant killed everything"
fi

# --- CASE 4: the payload's own `set -e` must NOT clear the prepended options.
# This is the single fact the whole shared-preamble design rests on. ---
opts="$(bash -c 'set -euo pipefail
set -e
echo "$SHELLOPTS"' 2>/dev/null)"
if [[ "$opts" == *pipefail* && "$opts" == *nounset* ]]; then
  ok "a payload's bare 'set -e' clears neither pipefail nor nounset"
else
  bad "a bare 'set -e' CLEARED the prepended options — the shared preamble cannot work: $opts"
fi

printf '\n  passed: %d\n  failed: %d\n' "$PASS" "$FAILED"
if [ "$FAILED" -gt 0 ]; then
  printf '\033[31m[leg-strictness]\033[0m %d case(s) failed\n' "$FAILED" >&2
  exit 1
fi
printf '\033[32m[leg-strictness]\033[0m ALL GREEN (%d cases)\n' "$PASS"
