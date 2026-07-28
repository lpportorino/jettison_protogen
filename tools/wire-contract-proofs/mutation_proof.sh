#!/usr/bin/env bash
# MUTATION PROOF for the wire-contract gate's §9 golden-vector clause.
#
# Corrupts exactly ONE byte of ONE golden vector in the TRACKED doc, shows the
# gate going red and NAMING that vector, shows every neighbouring clause staying
# green (so the red is attributable to the clause under test and not to a
# neighbour that would have refused the same doc anyway), then reverts and
# proves the revert with `git status`.
#
# Requirements this satisfies:
#   - the mutation is asserted to have LANDED (grep -c, non-zero) before any
#     result is believed;
#   - the red is a FAIL (an assertion that ran and returned false), not an
#     ERROR (a traceback that executed nothing) -- the assertion COUNT is
#     printed on both sides so a suppressed-body run cannot masquerade as a pass;
#   - a CONTROL clause is named and shown green.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOC="$ROOT/docs/INTERFACE-CONTRACTS.md"
CHECK=(python3 "$ROOT/tools/wire_contract_check.py")

cleanup() { git -C "$ROOT" checkout -- docs/INTERFACE-CONTRACTS.md 2>/dev/null || true; }
trap cleanup EXIT

banner() { printf '\n=== %s ===\n' "$1"; }

banner "0. BASELINE (tracked doc, unmodified)"
"${CHECK[@]}" --quiet | tail -1
base_pass=$("${CHECK[@]}" | grep -c '^  ok   ')
echo "baseline assertions that PASSED: $base_pass"

banner "1. MUTATE — one byte of §9 G1: client_app 50 03 -> 50 04"
# Anchored to the `= <hex>` line of the G1 spec block only. The same byte pair
# appears in §6's inline copy and in the CW-framed copy; neither is touched.
perl -0pi -e 's/^= 08 01 28 02 50 03 e2 01 00(\s+\(9 bytes\))$/= 08 01 28 02 50 04 e2 01 00$1/m' "$DOC"

landed=$(grep -c '^= 08 01 28 02 50 04 e2 01 00' "$DOC" || true)
echo "mutation landed (occurrences of the corrupted line): $landed"
if [ "$landed" -eq 0 ]; then
  echo "ABORT: mutation did not land — any result below would be meaningless."
  exit 3
fi
echo "git diff --stat:"; git -C "$ROOT" diff --stat -- docs/INTERFACE-CONTRACTS.md

banner "2. THE RED, AND THE CLAUSE IT NAMES"
set +e
out=$("${CHECK[@]}"); rc=$?
set -e
echo "$out" | grep '^  FAIL'
echo "checker exit code: $rc"
mut_pass=$(echo "$out" | grep -c '^  ok   ')
mut_fail=$(echo "$out" | grep -c '^  FAIL ')
echo "assertions passed: $mut_pass   failed: $mut_fail"
if [ "$mut_fail" -eq 0 ]; then echo "ABORT: no FAIL — the clause did not fire."; exit 3; fi
if [ "$mut_pass" -eq 0 ]; then
  echo "ABORT: 0 assertions passed — this is an ERROR (nothing executed), not a FAIL."
  exit 3
fi

banner "3. CONTROL — neighbouring clauses that must stay GREEN"
for control in \
  '§9 cmd.Root{protocol_version=1, client_type=2, client_app=1, ping={}} bytes' \
  '§6 byte gloss: `50 03` encodes cmd.Root.client_app'"'"'s tag' \
  'G1 inline copy reproduces a derived vector' \
  '§6 cmd.Root.client_app number' \
  '§6 identity table and §9 vectors describe the same clients'
do
  if echo "$out" | grep -Fq "  ok   $control"; then
    echo "  CONTROL GREEN : $control"
  else
    echo "  CONTROL NOT GREEN (attribution broken): $control"
  fi
done

banner "4. REVERT, AND PROVE IT"
cleanup
trap - EXIT
echo "git status --porcelain for the doc:"
git -C "$ROOT" status --porcelain -- docs/INTERFACE-CONTRACTS.md
echo "(empty above = the tracked doc is byte-identical to HEAD)"
echo "grep for the corrupted line after revert: $(grep -c '50 04 e2 01 00' "$DOC" || true)"
"${CHECK[@]}" --quiet | tail -1
