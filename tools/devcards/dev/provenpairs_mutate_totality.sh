#!/usr/bin/env bash
# The coverage clause is the one that cannot be proved by the self-test: it
# fires on REAL sources, and its clean value (an empty finding key) is exactly
# what a probe that never opened a file also prints. So it is proved the only
# way that distinguishes those — silently drop a source from the walk and show
# the clause name the file it lost.
set -uo pipefail
F=tools/devcards/dev/proven_pairs.clj
full() { tools/uber.sh "cd tools/devcards && clojure -M:proven-pairs" 2>&1 \
         | grep -E "token-pair rows|class-token-never-visited|findings \(could"; }
echo "════════ BASELINE (unmutated)"
full
echo "  (no class-token-never-visited line = the clause is silent, i.e. full coverage)"
echo "════════ MUTATED — the vr-fixtures source is dropped from the walk"
python3 - <<'PY'
import pathlib
p = pathlib.Path("tools/devcards/dev/proven_pairs.clj")
s = p.read_text()
old = "         (for [t (clj-trees clj-forms)] [:vr-fixtures t]))"
new = "         (for [t []] [:vr-fixtures t]))"
assert s.count(old) == 1, s.count(old)
p.write_text(s.replace(old, new))
PY
n=$(grep -c 'for \[t \[\]\]' "$F"); echo "mutation landed: grep -c = $n (must be non-zero)"
[ "$n" -eq 0 ] && { git checkout -- "$F"; exit 1; }
full
echo "════════ CONTROL — the pair table's other sources are untouched"
tools/uber.sh "cd tools/devcards && clojure -M:proven-pairs >/dev/null 2>&1; grep -c 'theme-style/panel\|kitchen_sink' ../../docs/PROVEN-PAIRS.md" 2>&1 | tail -1
echo "  (non-zero = theme.c + kitchen_sink pairs still derived; the red is the DROPPED source only)"
git checkout -- "$F" docs/PROVEN-PAIRS.md
echo "════════ RESTORED"; git status --porcelain -- "$F" docs/PROVEN-PAIRS.md
