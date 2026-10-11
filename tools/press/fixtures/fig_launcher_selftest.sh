#!/usr/bin/env bash
# fig_launcher_selftest.sh <press> <out-dir> -- every `press fig` subcommand driven through the host
# launcher itself (bin/press), both directions: what must work exits 0, what must be refused exits
# with the documented code, and no refusal leaks a traceback. Exit 1 on any failure.
set -uo pipefail
press=$1
out=$2
figs=$(cd "$(dirname "${BASH_SOURCE[0]}")/figs" && pwd)
rm -rf "$out"
mkdir -p "$out"
fails=0
passes=0
expect() { # <name> <wanted rc> <got rc> <stderr file>
  local name=$1 want=$2 got=$3 err=$4
  if [ "$got" -eq "$want" ] && ! grep -q Traceback "$err"; then
    passes=$((passes + 1))
    echo "PASS  launcher: $name (exit $got)"
  else
    fails=$((fails + 1))
    echo "FAIL  launcher: $name -- wanted exit $want, got $got$(grep -q Traceback "$err" && echo ', with a traceback')"
  fi
}
run() { # <name> <wanted rc> <press args...>
  local name=$1 want=$2 rc=0
  shift 2
  "$press" fig "$@" >"$out/last.out" 2>"$out/last.err" || rc=$?
  expect "$name" "$want" "$rc" "$out/last.err"
}
# a directory whose name holds a colon and a space must mount like any other
odd="$out/a:b c"
mkdir -p "$odd"
cp "$figs/repair-flow.fig.json" "$odd/"
run 'solve repair-flow from a directory named "a:b c"' 0 solve "$odd/repair-flow.fig.json" "$odd/out"
run 'pick tile 1' 0 pick "$odd/repair-flow.fig.json" "$odd/out" 1 "$odd/repair-flow.pick.json" "$odd/repair-flow.svg"
run 'rebuild the pick' 0 rebuild "$odd/repair-flow.fig.json" "$odd/repair-flow.pick.json" "$odd/rebuilt.svg"
if cmp -s "$odd/repair-flow.svg" "$odd/rebuilt.svg"; then
  passes=$((passes + 1))
  echo "PASS  launcher: rebuild is byte-identical to the pick"
else
  fails=$((fails + 1))
  echo "FAIL  launcher: rebuild differs from the pick"
fi
run 'check the pick' 0 check "$odd/repair-flow.svg" --json "$odd/check.json"
run 'shoot the pick' 0 shoot "$odd/repair-flow.svg" "$odd/repair-flow.png"
if [ -s "$odd/repair-flow.png" ]; then
  passes=$((passes + 1))
  echo "PASS  launcher: shoot wrote a PNG"
else
  fails=$((fails + 1))
  echo "FAIL  launcher: shoot wrote no PNG"
fi
printf '%s\n' '<svg viewBox="0 0 476 100" xmlns="http://www.w3.org/2000/svg"><image href="http://203.0.113.7/b.png" x="0" y="0" width="10" height="10"/><text x="20" y="50" font-size="8">hello</text></svg>' >"$out/net.svg"
run 'check an SVG that reaches outside itself' 1 check "$out/net.svg"
run 'solve a missing file' 6 solve "$out/nosuch.fig.json" "$out/x"
run 'pick a nonexistent tile' 6 pick "$odd/repair-flow.fig.json" "$odd/out" 99 "$out/p.json" "$out/p.svg"
run 'rebuild with a missing record' 6 rebuild "$odd/repair-flow.fig.json" "$out/nosuch.pick.json" "$out/r.svg"
run 'an unknown fig command' 6 frobnicate "$odd/repair-flow.fig.json"
echo "fig launcher selftest: $passes passed, $fails failed"
[ "$fails" -eq 0 ]
