#!/usr/bin/env bash
# tools/devcards/dev/readback_mutate.sh — the mutation proof for
# devcards.readback-strings-test.
#
# A RED IS NOT EVIDENCE UNLESS IT NAMES ITS CLAUSE. This script breaks ONE
# production expression in tools/devcards/dev/readback_strings.clj at a time,
# runs the suite, and records which deftests went red. Its output is
# tools/devcards/dev/readback_mutation_evidence.txt, which is what the
# instrument's mutation claims cite — a number with no re-runnable probe is
# recall, not evidence.
#
# THREE THINGS IT DOES THAT A DEMONSTRATION DOES NOT:
#   - ASSERTS THE MUTATION LANDED (grep -c, non-zero required) before believing
#     any result. A mutation that silently did not apply looks exactly like a
#     canary that did not fire.
#   - Separates FAIL from ERROR in the attribution. A mutation that breaks the
#     namespace load reds the file having executed nothing, and that red carries
#     no information about any clause.
#   - Reports the ASSERTION COUNT per run. A run whose count dropped executed
#     fewer bodies than the baseline, which is a different event from a run whose
#     assertions failed.
#
# CONTAINER-ONLY, like every other lane that runs the pinned Clojure:
#
#   tools/uber.sh 'bash tools/devcards/dev/readback_mutate.sh'
#
# It restores the pristine source on every exit path, including a signal.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SRC="$ROOT/tools/devcards/dev/readback_strings.clj"
WORK="$(mktemp -d)"
PRISTINE="$WORK/pristine.clj"
cp "$SRC" "$PRISTINE"

restore() { cp "$PRISTINE" "$SRC"; }
cleanup() { restore; rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

MUTATIONS="M1-exactly-k M2-space-size-binomial M3a-distance-filter M3b-lev-cost
           M4-pass-message-overclaim M5-entropy-floor M6-prompt-leak
           M7-seed-separator M8-alphabet-closure M9-sentinel-spelling
           M10-ladder-string M11-parser-normalises M12-empty-message"

# apply <id>  — rewrites SRC in place; exits non-zero if the target is not unique.
apply() {
  python3 - "$SRC" "$1" <<'PY'
import sys
path, mid = sys.argv[1], sys.argv[2]
# Raw strings throughout, and explicit chr() where a raw string still would not
# be literal enough: the Clojure source is full of backslash char literals and
# carries a backslash-u escape inside a string literal (the cell-seed
# separator). None of it may be interpreted by Python on the way through, and
# none of it may be interpreted by bash either, which is why the heredoc is
# quoted.
MUT = {
    "M1-exactly-k": (
        r"(nth singleton-chars (.nextInt r n-single))",
        r"(nth alphabet (.nextInt r (count alphabet)))",
        "nth alphabet"),
    "M2-space-size-binomial": (
        r"(.multiply (.multiply (binomial length confusables)",
        r"(.multiply (.multiply (if true BigInteger/ONE (binomial length confusables))",
        "if true BigInteger/ONE"),
    "M3a-distance-filter": (
        r"  (count (remove #(= :match (:op %)) ops)))",
        r"  (count (remove #(#{:match :sub} (:op %)) ops)))",
        "remove #(#{:match :sub}"),
    "M3b-lev-cost": (
        r"(let [cost (if (= (nth expected (dec i)) (nth observed (dec j))) 0 1)]",
        r"(let [cost (if (= (nth expected (dec i)) (nth observed (dec j))) 0 2)]",
        "(dec j))) 0 2)"),
    "M4-pass-message-overclaim": (
        r'(str "a machine reader recovered the string at level " (pr-str level)))',
        r'(str "a human reader recovered the string at level " (pr-str level)))',
        "a human reader recovered"),
    "M5-entropy-floor": (
        r"      (when (< bits min-entropy-bits)",
        r"      (when (< bits -1.0)",
        "when (< bits -1.0)"),
    "M6-prompt-leak": (
        "  [_cell]\n  read-back-prompt)",
        '  [_cell]\n  (str read-back-prompt " (hint: " (:string _cell) ")"))',
        "(hint:"),
    "M7-seed-separator": (
        "(bytes->seed (sha256 (str master-seed " + chr(34) + chr(92) + "u0000" + chr(34) + " sid))))",
        r"(bytes->seed (sha256 (str master-seed sid))))",
        "sha256 (str master-seed sid)"),
    "M8-alphabet-closure": (
        "(def singleton-chars\n  [" + chr(92) + "4 " + chr(92) + "9",
        "(def singleton-chars\n  [" + chr(92) + "u2603 " + chr(92) + "4 " + chr(92) + "9",
        "u2603"),
    "M9-sentinel-spelling": (
        r'  "NORECOVERY")',
        r'  "UNREADABLE")',
        "UNREADABLE"),
    "M10-ladder-string": (
        r":let [sid (stimulus-id c dr)]",
        r":let [sid (cell-id c l dr)]",
        "sid (cell-id c l dr)"),
    "M11-parser-normalises": (
        r":else {:outcome :recovered :value t :raw reply}",
        r":else {:outcome :recovered :value (str/upper-case t) :raw reply}",
        "(str/upper-case t)"),
    "M12-empty-message": (
        r"(str/blank? (str message)) [{:clause :empty-message :detail message}]",
        r"(str/blank? (str message)) []",
        "(str/blank? (str message)) []"),
}

old, new, _landing = MUT[mid]
s = open(path, encoding="utf-8").read()
n = s.count(old)
if n != 1:
    sys.stderr.write("%s: mutation target matched %d times, need exactly 1\n" % (mid, n))
    sys.exit(9)
open(path, "w", encoding="utf-8").write(s.replace(old, new))
PY
}

# landing-pattern <id> — the grep the landing assertion uses.
landing_pattern() {
  case "$1" in
    M1-exactly-k)              echo 'nth alphabet' ;;
    M2-space-size-binomial)    echo 'if true BigInteger/ONE' ;;
    M3a-distance-filter)       echo 'remove #(#{:match :sub}' ;;
    M3b-lev-cost)              echo '(dec j))) 0 2)' ;;
    M4-pass-message-overclaim) echo 'a human reader recovered' ;;
    M5-entropy-floor)          echo 'when (< bits -1.0)' ;;
    M6-prompt-leak)            echo '(hint:' ;;
    M7-seed-separator)         echo 'sha256 (str master-seed sid)' ;;
    M8-alphabet-closure)       echo 'u2603' ;;
    M9-sentinel-spelling)      echo 'UNREADABLE' ;;
    M10-ladder-string)         echo 'sid (cell-id c l dr)' ;;
    M11-parser-normalises)     echo '(str/upper-case t)' ;;
    M12-empty-message)         echo '(str/blank? (str message)) []' ;;
    *) echo "NO-SUCH-MUTATION" ;;
  esac
}

# The CANARY is the deftest the mutation is aimed at; the CONTROL is a deftest
# that must stay GREEN, so a red is attributable to the clause and not to a
# neighbour that would have refused the same input anyway. Each control was
# chosen as one the whole-namespace run shows surviving that mutation.
canary_of() {
  case "$1" in
    M1-exactly-k)              echo exactly-k-confusables ;;
    M2-space-size-binomial)    echo space-is-exactly-what-the-formula-claims ;;
    M3a-distance-filter)       echo distance-agrees-with-an-independent-dp ;;
    M3b-lev-cost)              echo distance-agrees-with-an-independent-dp ;;
    M4-pass-message-overclaim) echo claims-stay-inside-the-measurement ;;
    M5-entropy-floor)          echo entropy-floor-is-enforced ;;
    M6-prompt-leak)            echo prompt-is-constant ;;
    M7-seed-separator)         echo determinism ;;
    M8-alphabet-closure)       echo alphabet-closure ;;
    M9-sentinel-spelling)      echo prompt-is-constant ;;
    M10-ladder-string)         echo the-ladder-sweeps-one-variable ;;
    M11-parser-normalises)     echo parser-is-total-and-verbatim ;;
    M12-empty-message)         echo claims-stay-inside-the-measurement ;;
  esac
}

control_of() {
  case "$1" in
    M1-exactly-k)              echo claims-stay-inside-the-measurement ;;
    M2-space-size-binomial)    echo exactly-k-confusables ;;
    M3a-distance-filter)       echo alphabet-closure ;;
    M3b-lev-cost)              echo alphabet-closure ;;
    M4-pass-message-overclaim) echo prompt-is-constant ;;
    M5-entropy-floor)          echo determinism ;;
    M6-prompt-leak)            echo determinism ;;
    M7-seed-separator)         echo prompt-is-constant ;;
    M8-alphabet-closure)       echo scorer-basics ;;
    M9-sentinel-spelling)      echo determinism ;;
    M10-ladder-string)         echo scorer-basics ;;
    M11-parser-normalises)     echo determinism ;;
    M12-empty-message)         echo determinism ;;
  esac
}

suite() {
  ( cd "$ROOT/tools/devcards" && clojure -M:test -n devcards.readback-strings-test ) \
      > "$WORK/$1.txt" 2>&1
}

one_test() {
  ( cd "$ROOT/tools/devcards" \
      && clojure -M:test -v "devcards.readback-strings-test/$2" ) \
      > "$WORK/$1-$2.txt" 2>&1
}

attribute() {
  grep -oE '^(FAIL|ERROR) in \([a-z0-9-]+\)' "$WORK/$1.txt" | sort | uniq -c \
    | sed 's/^/      /'
  grep -E '^Ran |^[0-9]+ failures' "$WORK/$1.txt" | sed 's/^/      /'
}

echo "readback_mutate.sh — mutation proof for devcards.readback-strings-test"
echo "generated by: tools/uber.sh 'bash tools/devcards/dev/readback_mutate.sh'"
echo
echo "=== BASELINE (pristine source)"
suite baseline
attribute baseline
echo

rc=0
for id in $MUTATIONS; do
  restore
  if ! apply "$id"; then
    echo "=== $id: FAILED TO APPLY — the target expression moved. Fix the table."
    rc=1
    continue
  fi
  landed=$(grep -c -F -- "$(landing_pattern "$id")" "$SRC")
  echo "=== $id"
  echo "    mutation landed: $landed occurrence(s) of $(landing_pattern "$id") [must be > 0]"
  if [ "$landed" -eq 0 ]; then
    echo "    ABORT: mutation did not land; any red below would be unattributable."
    rc=1
    restore
    continue
  fi
  c=$(canary_of "$id"); k=$(control_of "$id")
  one_test "$id" "$c"
  echo "    CANARY  $c"
  sed -n '/^\(FAIL\|ERROR\) in/,/^$/p' "$WORK/$id-$c.txt" | head -12 | sed 's/^/      /'
  grep -E '^Ran ' "$WORK/$id-$c.txt" | sed 's/^/      /'
  one_test "$id" "$k"
  echo "    CONTROL $k  [must be 0 failures, 0 errors]"
  grep -E '^Ran |^[0-9]+ failures' "$WORK/$id-$k.txt" | sed 's/^/      /'
  echo "    whole namespace:"
  suite "$id"
  attribute "$id"
  echo
  restore
done

echo "=== DONE (rc=$rc)"
exit "$rc"
