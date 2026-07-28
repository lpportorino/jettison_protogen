#!/usr/bin/env bash
# Mutation proofs for devcards.cascade.
#
# A red proves nothing unless it came from the clause under test. Each case
# below breaks ONE production expression, asserts the mutation LANDED (grep -c,
# non-zero) before believing any result, runs the suite, and records which
# deftests went red and which stayed green — the neighbours are the control.
#
# The suite must FAIL, not ERROR: a mutation that breaks the namespace load
# reds the file while executing nothing. The assertion COUNT is printed for
# every run for the same reason — a suite can print 0 assertions and exit 0.
#
# Restoration is proved with `git diff --exit-code`, not by trusting the cp.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/tools/devcards/src/devcards/cascade.clj"
BAK="$ROOT/.fork-scratch/cascade.clj.orig"
OUT="$ROOT/.fork-scratch/mutation-evidence-cascade.txt"

cp "$SRC" "$BAK"
: > "$OUT"

run_case() {
  local name="$1" landed_pat="$2"
  local n
  n=$(grep -c -- "$landed_pat" "$SRC")
  {
    echo "════════════════════════════════════════════════════════════════"
    echo "MUTATION: $name"
    echo "landed:   grep -c '$landed_pat' = $n"
  } >> "$OUT"
  if [ "$n" -eq 0 ]; then
    echo "MUTATION DID NOT LAND — aborting this case" >> "$OUT"
    cp "$BAK" "$SRC"
    return 1
  fi
  ( cd "$ROOT" && tools/uber.sh \
      'cd tools/devcards && clojure -M:test -n devcards.cascade-test' ) \
    > "$ROOT/.fork-scratch/mut.log" 2>&1
  {
    echo "--- deftests that FAILED (the red) ---"
    grep -oE '^(FAIL|ERROR) in \([a-zA-Z0-9_.-]+\)' "$ROOT/.fork-scratch/mut.log" \
      | sort -u
    echo "--- tally ---"
    grep -E '^Ran [0-9]+ tests|^[0-9]+ failures' "$ROOT/.fork-scratch/mut.log"
    echo "--- one failing assertion, verbatim ---"
    awk '/^FAIL in /{c++} c==1{print} c==1 && /^  actual:/{exit}' \
      "$ROOT/.fork-scratch/mut.log"
  } >> "$OUT"
  cp "$BAK" "$SRC"
}

# ── M1 ── the DERIVED backdrop, replaced by the naive flag read.
python3 - "$SRC" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "               (and owes-pair? (not (and (some? bg) (:covers? bg))))\n               (conj :backdrop-unresolved))"
new = "               (and owes-pair? (contains? node :backdrop_unresolved))\n               (conj :backdrop-unresolved))"
assert old in s, "M1 anchor missing"
open(p, "w").write(s.replace(old, new))
PY
run_case "M1 derived backdrop -> naive (contains? node :backdrop_unresolved)" \
         "(and owes-pair? (contains? node :backdrop_unresolved))"

# ── M2 ── the inheritance walk stops threading what is in force.
python3 - "$SRC" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "                   (walk child (conj path i) in-force glyphs text-free)))"
new = "                   (walk child (conj path i) self-hex glyphs text-free)))"
assert old in s, "M2 anchor missing"
open(p, "w").write(s.replace(old, new))
PY
run_case "M2 walk threads self-hex instead of in-force" \
         "(walk child (conj path i) self-hex glyphs text-free)"

# ── M3 ── a foreground is handed back for a node that draws no glyphs.
python3 - "$SRC" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "        fg (when owes-pair?\n"
new = "        fg (when true\n"
assert old in s, "M3 anchor missing"
open(p, "w").write(s.replace(old, new))
PY
run_case "M3 fg reported even when :glyphs is :no" "fg (when true"

# ── M4 ── the model check deleted.
python3 - "$SRC" <<'PY'
import sys, re
p = sys.argv[1]; s = open(p).read()
old = """    (when (and declared bg (:covers? bg))
      (throw (ex-info (str "node declares `backdrop_unresolved` while its "
                           "glyph fill fully covers — dump_obj cannot emit "
                           "both, so this resolver's model of the emission "
                           "conditions is stale")
                      {:type t :path path :bg bg})))"""
new = "    (when false (throw (ex-info \"MUTANT-M4-no-model-check\" {})))"
assert old in s, "M4 anchor missing"
open(p, "w").write(s.replace(old, new))
PY
run_case "M4 backdrop_unresolved/fill consistency throw deleted" \
         "MUTANT-M4-no-model-check"

# ── M5 ── an unclassified class is silently cleared.
python3 - "$SRC" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "      (contains? text-free t) :no\n      :else :unknown)))"
new = "      (contains? text-free t) :no\n      :else :no)))"
assert old in s, "M5 anchor missing"
open(p, "w").write(s.replace(old, new))
PY
run_case "M5 glyph-verdict :else -> :no (the silent skip)" \
         ":else :no)))"

# ── restore, and PROVE it ──
cp "$BAK" "$SRC"
{
  echo "════════════════════════════════════════════════════════════════"
  echo "RESTORE PROOF: git diff --exit-code on the mutated file"
  ( cd "$ROOT" && git diff --exit-code -- tools/devcards/src/devcards/cascade.clj ) \
    && echo "  clean — file is byte-identical to HEAD" \
    || echo "  DIRTY — RESTORE FAILED"
} >> "$OUT"
rm -f "$BAK" "$ROOT/.fork-scratch/mut.log"
cat "$OUT"
