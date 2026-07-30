#!/usr/bin/env bash
# Canary mutation harness for devcards.border + the :framebuffer registry seam.
#
# A SIBLING of the tracked mutate.sh, not a replacement: that one is the
# devcards.palette harness and its evidence file is the palette record. Same
# convention, same discipline, different subject.
#
# For each REVERT-TO-BREAK marker: apply the exact mutation it names, ASSERT
# THE MUTATION LANDED (a mutation that silently failed to apply produces a
# green that proves nothing), then run THREE things —
#   1. the CANARY var alone   — must report failures>0 and errors==0
#   2. the CONTROL var alone  — must report failures==0 and errors==0
#   3. the whole namespace    — recorded verbatim, so collateral is visible
# — restore, and print the tally. A canary is credited only on a FAIL: an
# ERROR is a broken harness wearing the right colour, not a caught defect.
#
# Runs INSIDE the pinned container (tools/uber.sh 'bash .fork-scratch/…') and
# restores by `cp` rather than by git. NOT because git is unavailable there —
# uber.sh declares safe.directory, so it works — but because a `cp` restore of
# two named files needs no index, no clean tree and no assumptions about what
# else the checkout has in flight, which is what a mutation harness wants.
set -uo pipefail
# tools/devcards/dev/ -> repo root is three levels up; the depth is part of the path.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BORDER="$ROOT/tools/devcards/src/devcards/border.clj"
REGISTRY="$ROOT/tools/devcards/src/devcards/findings.clj"
NS=devcards.border-test

cp "$BORDER" "$BORDER.orig"; cp "$REGISTRY" "$REGISTRY.orig"
restore() { cp "$BORDER.orig" "$BORDER"; cp "$REGISTRY.orig" "$REGISTRY"; }
trap 'restore; rm -f "$BORDER.orig" "$REGISTRY.orig"' EXIT

run_ns() {
  (cd "$ROOT/tools/devcards" && clojure -M:test -n "$NS" 2>&1) \
    | grep -E "^(FAIL|ERROR) in|^Ran [0-9]+ tests"
}

run_var() {
  (cd "$ROOT/tools/devcards" && clojure -M:test -v "$NS/$1" 2>&1) \
    | grep -E "^(FAIL|ERROR) in|^Ran [0-9]+ tests|^expected:|^  actual:"
}

# $1 name  $2 file  $3 literal-from  $4 literal-to  $5 canary-var  $6 control-var
mutate() {
  local name="$1" file="$2" from="$3" to="$4" canary="$5" control="$6"
  restore
  echo "### $name"
  # Replace, then re-read from disk and count. python, not grep -F: a
  # multi-line pattern is split by grep into independent line patterns, which
  # counts unrelated lines and reports a landed mutation as un-landed.
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
  echo "    CANARY  $canary"
  run_var "$canary" | sed 's/^/      /'
  echo "    CONTROL $control"
  run_var "$control" | sed 's/^/      /'
  echo "    whole namespace:"
  run_ns | sed 's/^/      /'
}

echo "=== BASELINE (unmutated) ==="
restore; run_ns | sed 's/^/      /'
echo

mutate "A · the CONTINUITY clause (border/longest-linear-gap)" "$BORDER" \
  '(if v [0 best]' '(if v [run best]' \
  equal-edge-counts-do-not-imply-equal-continuity \
  no-edge-signal-is-one-whole-contour-gap
echo

mutate "B · :framebuffer in the CLOSED context set (findings/context-keys)" "$REGISTRY" \
  ':declaration :proxy-rects :expect :framebuffer})' \
  ':declaration :proxy-rects :expect})' \
  the-registry-ADMITS-the-declaration-this-rule-has-to-make \
  equal-edge-counts-do-not-imply-equal-continuity
echo

mutate "C · the :bytes clause (findings/framebuffer-problem)" "$REGISTRY" \
  '(not (bytes? px))' 'false' \
  a-HALF-supplied-framebuffer-is-refused-at-the-seam \
  the-rule-judges-through-the-real-registry
echo

mutate "D · the byte-LENGTH clause (findings/framebuffer-problem)" "$REGISTRY" \
  '(not= (* (long w) (long h) (long framebuffer-bytes-per-pixel))
              (alength ^bytes px))' 'false' \
  a-HALF-supplied-framebuffer-is-refused-at-the-seam \
  the-rule-judges-through-the-real-registry
echo

mutate "E · the absent-:borders refusal (border/declared-targets)" "$BORDER" \
  '(when-not (vector? targets)' '(when-not true' \
  an-absent-borders-DECLARATION-is-an-oversight \
  the-rule-judges-through-the-real-registry
echo

mutate "F · :framebuffer in border/required-context" "$BORDER" \
  '#{:framebuffer :declaration})' '#{:declaration})' \
  a-caller-that-cannot-supply-pixels-is-REFUSED \
  the-rule-judges-through-the-real-registry
echo

echo "=== RESTORED ==="
restore; run_ns | sed 's/^/      /'
