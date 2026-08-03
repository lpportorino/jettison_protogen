#!/usr/bin/env bash
# Canary mutation harness for devcards.state-mirror — the conventions/corpus
# mirror gate.
#
# A SIBLING of border_mutate.sh and palette_mutate.sh, same convention and same
# discipline, different subject. It exists because a green canary suite proves
# nothing on its own: the suite has to be shown FAILING for the clause under
# test while its neighbours keep behaving, or a dead clause and a satisfied one
# are the same colour.
#
# For each clause: apply the exact mutation named, ASSERT THE MUTATION LANDED
# (a mutation that silently matched nothing yields a mutant identical to the
# original, whose green reads as attribution while proving the opposite), then
# run THREE things —
#   1. the CANARY var alone   — must report failures>0 and errors==0
#   2. the CONTROL var alone  — must report failures==0 and errors==0
#   3. the whole namespace    — recorded verbatim, so collateral is visible
# — restore, and print the tally.
#
# A CANARY IS CREDITED ONLY ON A FAIL. An ERROR is a broken harness wearing the
# right colour, so every mutation below is chosen to leave the gate RUNNING and
# merely wrong — never to make it throw.
#
# THE CONTROLS ARE CHOSEN TO STILL REFUSE, not merely to stay green. A neighbour
# that passes is compatible with that neighbour being dead too; a neighbour that
# still REJECTS its own planted input on the same mutant is not. Case D's control
# refuses in a DIFFERENT EXIT CLASS (3, CANNOT RUN) from the clause it guards
# (1, FINDINGS), which is what makes the exit-code split itself attributable.
#
# Runs INSIDE the pinned container:
#   tools/uber.sh 'bash tools/devcards/dev/state_mirror_mutate.sh'
# and restores by `cp` rather than by git — a `cp` restore of one named file
# needs no index, no clean tree, and no assumption about what else the checkout
# has in flight, which is what a mutation harness wants. The gate file's sha256
# is printed before and after so the restore is proven, not assumed.
set -uo pipefail
# tools/devcards/dev/ -> repo root is three levels up; the depth is part of the path.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GATE="$ROOT/tools/devcards/src/devcards/state_mirror.clj"
NS=devcards.state-mirror-test

cp "$GATE" "$GATE.orig"
restore() { cp "$GATE.orig" "$GATE"; }
trap 'restore; rm -f "$GATE.orig"' EXIT

run_ns() {
  (cd "$ROOT/tools/devcards" && clojure -M:test -n "$NS" 2>&1) \
    | grep -E "^(FAIL|ERROR) in|^Ran [0-9]+ tests"
}

run_var() {
  (cd "$ROOT/tools/devcards" && clojure -M:test -v "$NS/$1" 2>&1) \
    | grep -E "^(FAIL|ERROR) in|^Ran [0-9]+ tests|^expected:|^  actual:"
}

# $1 name  $2 literal-from  $3 literal-to  $4 canary-var  $5 control-var
mutate() {
  local name="$1" from="$2" to="$3" canary="$4" control="$5"
  restore
  echo "### $name"
  # python, not grep -F: a multi-line pattern is split by grep into independent
  # line patterns, which counts unrelated lines and reports a landed mutation as
  # un-landed.
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

mutate "A · the MEMBERSHIP clause (state-mirror/pair-finding)" \
  '(not= (set conv-states) (set corpus-states))' \
  '(not= (set conv-states) (set conv-states))' \
  a-membership-difference-is-attributed-to-its-own-clause \
  an-order-only-difference-is-its-own-clause-not-a-membership-one
echo

# `(nil? states)` rather than the obvious `(and false (empty? states))`: a
# replacement that CONTAINS its target leaves the original-occurrence count at 1,
# so the landed-proof line reads as a failure on a mutation that landed
# perfectly. A disjoint replacement keeps that check meaning what it says. The
# clause is equally dead either way — a nil `states` is already caught by the
# `(not (map? states))` arm above it, so this arm can never fire.
mutate "B · the EMPTY-conventions refusal (state-mirror/conventions-side)" \
  '(empty? states)' '(nil? states)' \
  an-empty-conventions-side-is-a-refusal-never-a-pass \
  an-empty-corpus-side-is-a-refusal-never-a-pass
echo

mutate "C · the conventions->corpus DIRECTION arm (state-mirror/findings)" \
  '(set/difference ck sk)' '(set/difference ck ck)' \
  a-class-only-in-the-conventions-file-names-that-direction \
  a-class-only-in-the-corpus-names-the-other-direction
echo

mutate "D · the FINDINGS exit code (state-mirror/verdict)" \
  '(if (seq fs) exit-findings exit-ok)' '(if (seq fs) exit-ok exit-ok)' \
  a-membership-difference-is-attributed-to-its-own-clause \
  an-empty-conventions-side-is-a-refusal-never-a-pass
echo

mutate "E · the COMPARED count (state-mirror/verdict)" \
  '(count (set/intersection (set (keys conv)) (set (keys corpus))))' '0' \
  an-agreeing-pair-passes-and-reports-what-it-compared \
  a-membership-difference-is-attributed-to-its-own-clause
echo

mutate "F · the UNDECLARED sentinel (state-mirror/corpus-side)" \
  '(get w :committed-states ::absent)' '(get w :committed-states [])' \
  a-corpus-class-declaring-no-list-is-a-finding-not-a-skip \
  a-membership-difference-is-attributed-to-its-own-clause
echo

mutate "G · the DUPLICATE-type refusal (state-mirror/corpus-side)" \
  '(not= (count types) (count (distinct types)))' 'false' \
  a-duplicated-corpus-type-is-a-refusal-never-a-silent-collapse \
  an-untyped-corpus-class-is-a-refusal-never-a-dropped-row
echo

mutate "H · the CLI's exit code (state-mirror/-main)" \
  '(System/exit exit)' '(System/exit 0)' \
  main-prints-and-exits-with-exactly-what-run-returned \
  the-committed-tree-agrees
echo

echo "=== RESTORED ==="
restore; run_ns | sed 's/^/      /'
echo "=== GATE sha256 AFTER (must equal BEFORE) ==="
sha256sum "$GATE" | sed 's/^/      /'
