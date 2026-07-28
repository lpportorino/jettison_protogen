#!/usr/bin/env bash
# Canary mutation harness for devcards.palette.
#
# For each REVERT-TO-BREAK marker: apply the exact mutation it names, ASSERT
# THE MUTATION LANDED (a mutation that silently failed to apply produces a
# green that proves nothing), run the suite, restore, and print the tally line.
# A canary is only credited when the run reports failures>0 AND errors==0 — an
# ERROR is a broken harness wearing the right colour, not a caught defect.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/tools/devcards/src/devcards/palette.clj"
MAIN="$ROOT/renderer/src/main.c"

cp "$SRC" "$SRC.orig"; cp "$MAIN" "$MAIN.orig"
restore() { cp "$SRC.orig" "$SRC"; cp "$MAIN.orig" "$MAIN"; }
trap 'restore; rm -f "$SRC.orig" "$MAIN.orig"' EXIT

run_suite() {
  (cd "$ROOT/tools/devcards" && clojure -M:test -n devcards.palette-test 2>&1) \
    | grep -E "^(FAIL|ERROR) in|^Ran [0-9]+ tests"
}

# $1 name  $2 file  $3 literal-to-replace  $4 replacement
mutate() {
  local name="$1" file="$2" from="$3" to="$4"
  restore
  echo "### $name"
  # Replace, then re-read from disk and count. Done in python, not grep -F:
  # a multi-line pattern is split by grep into independent line patterns, which
  # counts unrelated lines and reports a mutation as un-landed when it landed.
  if ! python3 - "$file" "$from" "$to" <<'PY'
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
  echo "    failing tests reported:"
  run_suite | sed 's/^/      /'
}

echo "=== BASELINE (unmutated) ==="
restore; run_suite | sed 's/^/      /'
echo

mutate "A · the theme-recolor exemption clause" "$SRC" \
  '(not (:theme_recolor observation))' 'true'
echo
mutate "B · the zero-records third answer" "$SRC" \
  '(zero? (long (:records draw-palette)))' 'false'
echo
mutate "C · mode-specific table selection" "$SRC" \
  '(let [valid (get palette mode)]' '(let [valid (into #{} (mapcat val palette))]'
echo
mutate "D · the theme-family clear site" "$MAIN" \
  '  palette_observer_clear();
  apply_default_theme();' '  apply_default_theme();'
echo
echo "=== RESTORED ==="
restore; run_suite | sed 's/^/      /'
