#!/usr/bin/env bash
# Canary mutation harness for devcards.palette.
#
# For each REVERT-TO-BREAK marker: apply the exact mutation it names, ASSERT
# THE MUTATION LANDED (a mutation that silently failed to apply produces a
# green that proves nothing), run the suite, restore, and print the tally line.
# A canary is only credited when the run reports failures>0 AND errors==0 — an
# ERROR is a broken harness wearing the right colour, not a caught defect.
set -uo pipefail
# THE DEPTH IS PART OF THE PATH: this file sits at tools/devcards/dev/, so the
# repo root is three levels up. Move this script and every path below silently
# retargets — the legs then fail for a reason unrelated to the mutation under
# test, and that red is indistinguishable from a caught defect. Not resolved via
# `git rev-parse --show-toplevel`, which is the obvious robust form and is not
# reliable here: git refuses a container-mounted worktree as dubiously owned
# unless safe.directory is declared for it. `tools/uber.sh` does declare it, so
# the git form works under that wrapper — but not under a raw `docker run`, and
# a path-derived root needs no such precondition at all.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
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
