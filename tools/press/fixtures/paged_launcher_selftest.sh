#!/usr/bin/env bash
# paged_launcher_selftest.sh <press> <pages-dir> <out-dir> -- `press pdf`'s layout pass and `press pdfcheck`
# driven through the host launcher itself (bin/press), both directions: a planted page fails the
# print, its twin prints clean, --allow-layout records instead of failing, bad input exits 6 with
# one JSON line and no traceback. <pages-dir> holds the pages paged_selftest.py wrote.
set -uo pipefail
press=$1
pages=$2
out=$3
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
    no "$name -- wanted exit $want, got $rc, $(wc -l <"$out/last.out") stdout line(s)$(grep -q Traceback "$out/last.err" && echo ', with a traceback')"
  fi
}
run 'pdf: an intro line stranded at the page foot fails the print' 1 pdf "$pages/intro_stranded.bad.html" "$out/bad" --no-pngs
if grep -q "\"report\": \"$out/bad/pdf_report.json\"" "$out/last.out"; then ok 'pdf: the stdout line carries host paths'; else no "pdf: the stdout line does not name the host report path: $(cat "$out/last.out")"; fi
want=$(sha256sum "$pages/intro_stranded.bad.html" | cut -c1-64)
if grep -q '"pdf": "/out/intro_stranded.bad.pdf"' "$out/bad/pdf_report.json" && grep -q '"severity": "blocking"' "$out/bad/pdf_report.json" &&
  grep -q '"allowed": false' "$out/bad/pdf_report.json" && grep -q "\"html_sha256\": \"$want\"" "$out/bad/pdf_report.json"; then
  ok 'pdf: the report keeps /out/<name>.pdf, contract severities with allowed, and the page hash'
else
  no 'pdf: the report lost /out/<name>.pdf, the contract-shape findings, allowed, or html_sha256'
fi
run 'pdf: its twin prints clean' 0 pdf "$pages/intro_stranded.good.html" "$out/good" --no-pngs
run 'pdf: a page whose only finding is a warn (a short split table) prints' 0 pdf "$pages/split_table.bad.html" "$out/warn" --no-pngs
if grep -q '"severity": "fix"' "$out/warn/pdf_report.json"; then ok 'pdf: the warn is reported as fix'; else no 'pdf: the split table was not reported as fix'; fi
run 'pdf: --allow-layout intro-stranded records instead of failing' 0 pdf "$pages/intro_stranded.bad.html" "$out/allow" --no-pngs --allow-layout intro-stranded
if grep -q '"allowed": true' "$out/allow/pdf_report.json"; then ok 'pdf: the allowed finding says so'; else no 'pdf: no allowed:true in the report'; fi
run 'pdf: an unknown detector name is refused' 6 pdf "$pages/intro_stranded.bad.html" "$out/x" --allow-layout nonesuch
run 'pdf: a missing page is refused' 6 pdf "$pages/nosuch.html" "$out/x"
run 'pdf: a missing out-dir argument is refused' 6 pdf "$pages/intro_stranded.bad.html"
run 'pdf: a page reaching outside itself fails, each request named' 1 pdf "$pages/external.bad.html" "$out/ext" --no-pngs
if grep -q '"external-resource:blocking"' "$out/last.out" && grep -q 'side.css' "$out/ext/pdf_report.json"; then
  ok 'pdf: the aborted requests are external-resource findings'
else
  no 'pdf: no external-resource finding for the outside requests'
fi
odd="$out/a:b c"
mkdir -p "$odd"
cp "$pages/intro_stranded.good.html" "$odd/"
run 'pdf: a directory named "a:b c" mounts like any other' 0 pdf "$odd/intro_stranded.good.html" "$odd/out" --no-pngs
ln -s "$pages/intro_stranded.good.html" "$out/link.html"
run 'pdf: a symlinked page is refused on the host' 6 pdf "$out/link.html" "$out/x"
ln -s "$out/good" "$out/linkdir"
run 'pdf: a symlinked output directory is refused on the host' 6 pdf "$pages/intro_stranded.good.html" "$out/linkdir"
run 'pdf: --allow-layout given twice is refused' 6 pdf "$pages/intro_stranded.bad.html" "$out/x" --allow-layout intro-stranded --allow-layout runt-page
ln -s "$out/bad/intro_stranded.bad.pdf" "$out/linkpdf.pdf"
run 'pdfcheck: a symlinked PDF is refused on the host' 6 pdfcheck "$out/linkpdf.pdf" --margins 22,21,24,21
run 'pdfcheck: the planted PDF fails' 1 pdfcheck "$out/bad/intro_stranded.bad.pdf" --margins 22,21,24,21 --json "$out/pc.json"
if [ -s "$out/pc.json" ] && grep -q '"not_run"' "$out/pc.json"; then ok 'pdfcheck: the report names the detectors that did not run'; else no 'pdfcheck: no report, or no not_run'; fi
run 'pdfcheck: its twin passes' 0 pdfcheck "$out/good/intro_stranded.good.pdf" --margins 22,21,24,21
run 'pdfcheck: no --margins is refused' 6 pdfcheck "$out/bad/intro_stranded.bad.pdf"
run 'pdfcheck: a missing PDF is refused' 6 pdfcheck "$out/nosuch.pdf" --margins 22,21,24,21
echo "paged launcher selftest: $passes passed, $fails failed"
[ "$fails" -eq 0 ]
