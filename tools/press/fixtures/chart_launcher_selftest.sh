#!/usr/bin/env bash
# chart_launcher_selftest.sh <press> <chart-selftest-out> <out-dir> -- `press chart` and the chart half of
# `press fig rebuild` driven through the host launcher itself (bin/press), both directions. Reuses the
# planted over-wide label chart_selftest.py wrote under <chart-selftest-out>.
set -uo pipefail
press=$1
cst=$2
out=$3
charts=$(cd "$(dirname "${BASH_SOURCE[0]}")/charts" && pwd)
rm -rf "$out"
mkdir -p "$out"
fails=0
passes=0
ok() {
  passes=$((passes + 1))
  echo "PASS  launcher: $1"
}
no() {
  fails=$((fails + 1))
  echo "FAIL  launcher: $1"
}
run() { # <name> <wanted rc> <press args...>
  local name=$1 want=$2 rc=0
  shift 2
  "$press" "$@" >"$out/last.out" 2>"$out/last.err" || rc=$?
  if [ "$rc" -eq "$want" ] && ! grep -q Traceback "$out/last.err" && [ "$(wc -l <"$out/last.out")" -eq 1 ]; then
    ok "$name (exit $rc, one JSON line)"
  else
    no "$name -- wanted exit $want, got $rc$(grep -q Traceback "$out/last.err" && echo ', with a traceback')"
  fi
}
odd="$out/a:b c"
mkdir -p "$odd"
cp "$charts/lending-timeline.fig.json" "$odd/"
run 'chart: the timeline from a directory named "a:b c"' 0 chart "$odd/lending-timeline.fig.json" "$odd/lending-timeline.pick.json" "$odd/lending-timeline.svg" --json "$odd/check.json"
run 'fig rebuild: the chart from its record' 0 fig rebuild "$odd/lending-timeline.fig.json" "$odd/lending-timeline.pick.json" "$odd/rebuilt.svg"
if cmp -s "$odd/lending-timeline.svg" "$odd/rebuilt.svg"; then ok 'the rebuilt chart is byte-identical'; else no 'the rebuilt chart differs'; fi
run 'fig check: the chart has no defects' 0 fig check "$odd/lending-timeline.svg"
run 'chart: a planted over-wide label is refused' 4 chart "$cst/unfit-widelabel/widelabel.fig.json" "$out/w.pick.json" "$out/w.svg"
if [ ! -e "$out/w.svg" ] && [ ! -e "$out/w.pick.json" ]; then ok 'the refused chart wrote nothing'; else no 'the refused chart left files'; fi
ln -s "$odd/lending-timeline.fig.json" "$out/link.fig.json"
run 'chart: a symlinked description is refused on the host' 6 chart "$out/link.fig.json" "$out/p.json" "$out/o.svg"
run 'fig check: a symlinked SVG is refused on the host' 6 fig check "$out/link.fig.json"
run 'chart: a missing description is refused' 6 chart "$out/nosuch.fig.json" "$out/p.json" "$out/o.svg"
run 'chart: a missing argument is refused' 6 chart "$odd/lending-timeline.fig.json" "$out/p.json"
echo "chart launcher selftest: $passes passed, $fails failed"
[ "$fails" -eq 0 ]
