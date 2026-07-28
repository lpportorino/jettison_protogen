#!/usr/bin/env bash
# Mutation proof for tools/devcards/dev/proven_pairs.clj.
# Breaks ONE production clause at a time, asserts the mutation LANDED
# (grep -c, non-zero required), runs the self-test, and restores the file.
# A red that does not name the mutated clause proves nothing; a red from a
# broken namespace load proves less than nothing, so the assertion COUNT is
# printed alongside every result.
set -uo pipefail
F=tools/devcards/dev/proven_pairs.clj
run() { tools/uber.sh "cd tools/devcards && clojure -M:proven-pairs --self-test" 2>&1 \
        | grep -E "self-test:|FAIL \[|Syntax error|Execution error|Could not locate"; }
mutate() { # name  old  new
  python3 - "$2" "$3" <<'PY'
import sys, pathlib
p = pathlib.Path("tools/devcards/dev/proven_pairs.clj")
s = p.read_text()
old, new = sys.argv[1], sys.argv[2]
assert s.count(old) == 1, f"expected exactly 1 occurrence, found {s.count(old)}"
p.write_text(s.replace(old, new))
PY
}
for m in M1 M2 M3; do
  case $m in
    M1) DESC="winner's tie-break: HIGHEST matching breakpoint tier wins"
        OLD='       (sort-by (fn [d] (if-let [bp (:bp d)] (style-props/bp-min-index bp 0) 0)))
       last))'
        NEW='       (sort-by (fn [d] (if-let [bp (:bp d)] (style-props/bp-min-index bp 0) 0)))
       first))'
        LANDED='(sort-by (fn \[d\] (if-let \[bp (:bp d)\] (style-props/bp-min-index bp 0) 0)))$' ;;
    M2) DESC="luminance red coefficient (0.2126)"
        OLD='(* 0.2126 (lin r))'
        NEW='(* 0.2000 (lin r))'
        LANDED='0\.2000' ;;
    M3) DESC="transparent-fill skip: bg_opa 0 means the node paints no fill"
        OLD='  (= 0 (opa-at tokens decls :fill-opa idx st)))'
        NEW='  (= -1 (opa-at tokens decls :fill-opa idx st)))'
        LANDED='= -1 (opa-at' ;;
  esac
  echo "════════ $m — MUTATED CLAUSE: $DESC"
  git checkout -- "$F"
  mutate "$m" "$OLD" "$NEW" || { echo "$m: mutation did not apply"; continue; }
  n=$(grep -c -- "$LANDED" "$F")
  echo "mutation landed: grep -c = $n (must be non-zero)"
  [ "$n" -eq 0 ] && { echo "$m: MUTATION DID NOT LAND — result would be meaningless"; git checkout -- "$F"; continue; }
  run
done
echo "════════ RESTORE"
git checkout -- "$F"
git status --porcelain -- "$F"
echo "restored clean (no output above = identical to HEAD)"
echo "════════ CONTROL — unmutated baseline"
run
