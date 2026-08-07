#!/usr/bin/env bash
# Canary mutation harness for the DESIGNED-OVERLAY clause of devcards.overlap.
#
# A SIBLING of state_mirror_mutate.sh, border_mutate.sh and palette_mutate.sh —
# same convention, same discipline, different subject. It exists because
# `devcards.overlap-test` being green proves nothing on its own: the overlap
# namespace has several clauses that would refuse many of the same inputs, so a
# red is compatible with the clause under test being dead. Only breaking THAT
# clause alone and watching ITS cases name themselves attributes anything
# (.claude/rules/gate-enforcement.md §2, .claude/rules/review-discipline.md).
#
# THREE DECISIONS ARE MUTATED, one per case, because each was a choice that
# could have gone the other way and each fails in its own direction:
#
#   A · THE DECLARATION. Make the clause never exclude. The exclusion case must
#       FAIL; the base overlap clause must still refuse the SAME geometry with
#       the key removed — which is what says the rule is awake at all.
#   B · THE CONTAINMENT GATE, widened to mere intersection. This is the exact
#       defect the gate exists to prevent: a PARTIAL cover silenced by a
#       declaration, which is the silent dead-zone class. The partial-cover case
#       must FAIL while the plain exclusion keeps passing.
#   C · THE BOX CHOICE, swapped from the REACHABLE box to raw :coords. Confined
#       to this clause so no other case moves. The reachable-box case must FAIL
#       while the partial-cover case still refuses.
#
# For each: apply the exact mutation, ASSERT IT LANDED (a mutation that silently
# matched nothing yields a mutant identical to the original, whose green reads
# as attribution while proving the opposite), then run THREE things —
#   1. the CANARY var alone   — must report failures>0 and errors==0
#   2. the CONTROL var alone  — must report failures==0 and errors==0
#   3. the whole namespace    — recorded verbatim, so collateral is visible
# — restore, and print the sha256 so the restore is proven rather than assumed.
#
# A CANARY IS CREDITED ONLY ON A FAIL. An ERROR is a broken harness wearing the
# right colour, so every mutation below is chosen to leave the rule RUNNING and
# merely wrong — never to make it throw.
#
# THE CONTROLS ARE CHOSEN TO STILL REFUSE, not merely to stay green. A
# neighbour that passes is compatible with that neighbour being dead too; a
# neighbour that still REPORTS its own planted overlap on the same mutant is
# not. Every control here asserts a FINDING is produced, never an absence.
#
# Runs INSIDE the pinned container:
#   tools/uber.sh 'bash tools/devcards/dev/overlap_overlay_mutate.sh'
# and restores by `cp` rather than by git — a `cp` restore of one named file
# needs no index, no clean tree, and no assumption about what else the checkout
# has in flight, which is what a mutation harness wants.
set -uo pipefail
# tools/devcards/dev/ -> repo root is three levels up; the depth is part of the path.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GATE="$ROOT/tools/devcards/src/devcards/overlap.clj"
NS=devcards.overlap-test

cp "$GATE" "$GATE.orig"
restore() { cp "$GATE.orig" "$GATE"; }
trap 'restore; rm -f "$GATE.orig"' EXIT

run_ns() {
  (cd "$ROOT/tools/devcards" && clojure -M:test -n "$NS" 2>&1) \
    | grep -E "^(FAIL|ERROR) in|^Ran [0-9]+ tests|^[0-9]+ failures"
}

run_var() {
  (cd "$ROOT/tools/devcards" && clojure -M:test -v "$NS/$1" 2>&1) \
    | grep -E "^(FAIL|ERROR) in|^Ran [0-9]+ tests|^[0-9]+ failures|^expected:|^  actual:"
}

# $1 name  $2 literal-from  $3 literal-to  $4 canary-var  $5 control-var
mutate() {
  local name="$1" from="$2" to="$3" canary="$4" control="$5"
  restore
  echo "### $name"
  # python, not grep -F: a multi-line pattern is split by grep into independent
  # line patterns, which counts unrelated lines and reports a landed mutation as
  # un-landed. Every replacement below is DISJOINT from its target for the same
  # reason — one that CONTAINED its target would leave the original-occurrence
  # count at 1 and the landed-proof line would read as a failure on a mutation
  # that applied perfectly.
  if ! python3 - "$GATE" "$from" "$to" <<'PY'
import sys
path, frm, to = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
if s.count(frm) != 1:
    sys.exit("MUTATION TARGET NOT UNIQUE (%d hits): %r" % (s.count(frm), frm))
open(path, "w").write(s.replace(frm, to))
after = open(path).read()
print("    mutation landed: original-occurrences=%d (want 0)  "
      "replacement-occurrences=%d (want >=1)"
      % (after.count(frm), after.count(to)))
PY
  then echo "    MUTATION DID NOT APPLY"; return; fi
  echo "    CANARY  $canary"
  run_var "$canary" | sed 's/^/      /'
  echo "    CONTROL $control"
  run_var "$control" | sed 's/^/      /'
  echo "    whole namespace:"
  run_ns | sed 's/^/      /'
}

echo "=== GATE sha256 BEFORE ==="
sha256sum "$GATE" | sed 's/^/      /'
echo "=== BASELINE (unmutated) ==="
restore; run_ns | sed 's/^/      /'
echo

mutate "A · the DECLARATION (overlap/designed-overlay-cover?)" \
'  (or (and (:designed_overlay (:node a)) (contains-box? box-a box-b))
      (and (:designed_overlay (:node b)) (contains-box? box-b box-a))))' \
'  (or (and (nil? (:node a)) (contains-box? box-a box-b))
      (and (nil? (:node b)) (contains-box? box-b box-a))))' \
  a-declared-overlay-that-CONTAINS-a-control-does-not-fire \
  the-same-geometry-fires-without-the-declaration
echo

mutate "B · the CONTAINMENT GATE, widened to mere intersection (overlap/contains-box?)" \
'  [outer inner]
  (= inner (geometry/intersection outer inner)))' \
'  [outer inner]
  (some? (geometry/intersection outer inner)))' \
  a-PARTIAL-cover-fires-however-it-is-declared \
  the-same-geometry-fires-without-the-declaration
echo

mutate "C · the BOX CHOICE, reachable -> raw :coords (overlap/designed-overlay-cover?)" \
'  (or (and (:designed_overlay (:node a)) (contains-box? box-a box-b))
      (and (:designed_overlay (:node b)) (contains-box? box-b box-a))))' \
'  (or (and (:designed_overlay (:node a))
           (contains-box? (:coords (:node a)) (:coords (:node b))))
      (and (:designed_overlay (:node b))
           (contains-box? (:coords (:node b)) (:coords (:node a))))))' \
  containment-is-judged-on-the-REACHABLE-box-not-on-coords \
  a-PARTIAL-cover-fires-however-it-is-declared
echo

echo "=== RESTORED ==="
restore; run_ns | sed 's/^/      /'
echo "=== GATE sha256 AFTER (must equal BEFORE) ==="
sha256sum "$GATE" | sed 's/^/      /'
