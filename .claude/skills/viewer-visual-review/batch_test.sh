#!/usr/bin/env bash
# batch_test.sh — the contract of batch.py, on synthetic evidence runs.
#
# Every outcome batch.py has is planted alone: identical, pixel-equivalent
# (Chromium only), changed, new, a changed brief, a vanished capture with and
# without its waiver, a stale waiver, a red run, a malformed sidecar, a missing
# brief key, and a refused run leaving no batch behind. Each case asserts the
# exact exit code and what was (or was not) batched. Needs python3 and Pillow,
# which the pinned image carries.
#
# Usage: bash .claude/skills/viewer-visual-review/batch_test.sh
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
BATCH="$here/batch.py"
command -v python3 >/dev/null 2>&1 || { echo 'CANNOT RUN — python3 is not on PATH' >&2; exit 3; }
python3 -c 'import PIL' 2>/dev/null || { echo 'CANNOT RUN — Pillow is not importable (the pinned image carries it)' >&2; exit 3; }
work="$(mktemp -d)"; trap 'rm -rf -- "$work"' EXIT
PASS=0 FAILED=0
ok() { PASS=$((PASS + 1)); printf '  \033[32mok\033[0m   %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; }

# png <path> <red> — a 4x4 image of one colour; a change of 1-2 is raster jitter.
png() { python3 -c 'import sys; from PIL import Image; Image.new("RGB",(4,4),(int(sys.argv[2]),0,0)).save(sys.argv[1])' "$1" "$2"; }
brief() { printf '{"expect": ["%s"], "tiles": []}\n' "${2:-looks right}" >"$1.json"; }
manifest() { printf '{"completed": %s, "failure": %s}\n' "$2" "$3" >"$1/manifest.json"; }
# run_dirs <name> — a green run with one capture per engine, unchanged across runs.
run_dirs() {
  local r="$work/$1"
  for e in chromium webkit; do mkdir -p "$r/$e"; manifest "$r/$e" true null
    png "$r/$e/$e-a.png" 100; brief "$r/$e/$e-a.png"; done
  printf '%s' "$r"
}
# batched <out-prefix> <engine> — the image basenames a batch lists.
# A missing batch file prints MISSING: "nothing batched" must never pass over a run that wrote nothing.
batched() {
  [ -f "$1-$2.json" ] || { echo MISSING; return 0; }
  python3 -c 'import json,sys,os; print(" ".join(sorted(os.path.basename(e["image"]) for e in json.load(open(sys.argv[1])))))' "$1-$2.json"
}
expect_rc() { # expect_rc <label> <want> <cmd...>
  local label="$1" want="$2" out code; shift 2
  out="$("$@" 2>&1)" && code=0 || code=$?
  if [ "$code" = "$want" ]; then ok "$label"; else bad "$label — expected exit $want, got $code"; printf '%s\n' "$out" | sed 's/^/       | /' >&2; fi
}

prior="$(run_dirs prior)"

printf '\n== WHAT IS BATCHED\n'
cur="$(run_dirs same)"
expect_rc 'identical bytes and brief: nothing to review' 0 python3 "$BATCH" "$cur" "$prior" "$work/o1"
[ -f "$work/o1-chromium.json" ] && [ -f "$work/o1-webkit.json" ] && ok 'a green run writes a batch file per engine, even an empty one' || bad 'a green run wrote no batch file'
[ "$(batched "$work/o1" chromium)" = "" ] && [ "$(batched "$work/o1" webkit)" = "" ] && ok '... and both batches are empty' || bad 'identical captures were batched'

cur="$(run_dirs jitter)"; png "$cur/chromium/chromium-a.png" 102; png "$cur/webkit/webkit-a.png" 102
expect_rc 'a 2/255 shift runs clean' 0 python3 "$BATCH" "$cur" "$prior" "$work/o2"
[ "$(batched "$work/o2" chromium)" = "" ] && ok 'Chromium: a 2/255 shift is pixel-equivalent, not batched' || bad 'Chromium jitter was batched'
[ "$(batched "$work/o2" webkit)" = "webkit-a.png" ] && ok 'WebKit: the same shift IS batched (jitter is Chromium-only)' || bad 'WebKit jitter was excused'

cur="$(run_dirs changed)"; png "$cur/chromium/chromium-a.png" 103
expect_rc 'a 3/255 shift runs clean' 0 python3 "$BATCH" "$cur" "$prior" "$work/o3"
[ "$(batched "$work/o3" chromium)" = "chromium-a.png" ] && ok 'Chromium: a shift beyond 2/255 is batched' || bad 'a real change was excused as jitter'

cur="$(run_dirs brief)"; brief "$cur/webkit/webkit-a.png" 'a new checklist item'
expect_rc 'a changed brief runs clean' 0 python3 "$BATCH" "$cur" "$prior" "$work/o4"
[ "$(batched "$work/o4" webkit)" = "webkit-a.png" ] && ok 'identical pixels with a changed expect list are batched' || bad 'a changed brief was not batched'

cur="$(run_dirs new)"; png "$cur/webkit/webkit-b.png" 50; brief "$cur/webkit/webkit-b.png"
expect_rc 'a new capture runs clean' 0 python3 "$BATCH" "$cur" "$prior" "$work/o5"
[ "$(batched "$work/o5" webkit)" = "webkit-b.png" ] && ok 'a capture with no prior is batched' || bad 'a new capture was not batched'

cur="$(run_dirs parts)"; for x in b c d; do png "$cur/webkit/webkit-$x.png" 7; brief "$cur/webkit/webkit-$x.png"; done
expect_rc '--parts 2 runs clean' 0 python3 "$BATCH" "$cur" "$prior" "$work/op" --parts 2
[ "$(batched "$work/op" webkit-a) $(batched "$work/op" webkit-b)" = "webkit-b.png webkit-c.png webkit-d.png" ] \
  && ok '--parts 2 splits a batch into two parts, in order' || bad "--parts split as: $(batched "$work/op" webkit-a) | $(batched "$work/op" webkit-b)"

printf '\n== REFUSALS (CANNOT RUN = 3) leave no batch behind\n'
cur="$(run_dirs gone)"; rm "$cur/webkit/webkit-a.png" "$cur/webkit/webkit-a.png.json"; png "$cur/webkit/webkit-c.png" 9; brief "$cur/webkit/webkit-c.png"
expect_rc 'a vanished capture is refused' 3 python3 "$BATCH" "$cur" "$prior" "$work/o6"
[ ! -e "$work/o6-chromium.json" ] && ok '... and the refused run wrote no batch' || bad 'a refused run left a batch behind'
expect_rc 'a vanished capture named by --vanished is accepted' 0 python3 "$BATCH" "$cur" "$prior" "$work/o7" --vanished webkit-a.png
expect_rc 'a --vanished naming nothing that vanished is refused' 3 python3 "$BATCH" "$(run_dirs same2)" "$prior" "$work/o8" --vanished webkit-a.png
cur="$(run_dirs red)"; manifest "$cur/webkit" false '{"kind": "FAIL", "message": "x"}'
expect_rc 'a red run is refused' 3 python3 "$BATCH" "$cur" "$prior" "$work/o9"
cur="$(run_dirs incomplete)"; manifest "$cur/chromium" false null
expect_rc 'an incomplete run is refused' 3 python3 "$BATCH" "$cur" "$prior" "$work/o10"
cur="$(run_dirs malformed)"; printf '{broken' >"$cur/chromium/chromium-a.png.json"
expect_rc 'a malformed sidecar is refused' 3 python3 "$BATCH" "$cur" "$prior" "$work/o11"
cur="$(run_dirs nokeys)"; printf '{"expect": []}\n' >"$cur/chromium/chromium-a.png.json"
expect_rc 'a sidecar without tiles is refused' 3 python3 "$BATCH" "$cur" "$prior" "$work/o12"
cur="$(run_dirs noexpect)"; printf '{"tiles": []}\n' >"$cur/chromium/chromium-a.png.json"
expect_rc 'a sidecar without expect is refused' 3 python3 "$BATCH" "$cur" "$prior" "$work/o12b"
cur="$(run_dirs nosidecar)"; rm "$cur/chromium/chromium-a.png.json"
expect_rc 'a missing sidecar is refused' 3 python3 "$BATCH" "$cur" "$prior" "$work/o13"
cur="$(run_dirs empty)"; rm "$cur/webkit/"*.png "$cur/webkit/"*.json; manifest "$cur/webkit" true null
expect_rc 'an engine directory with no captures is refused' 3 python3 "$BATCH" "$cur" "$prior" "$work/o15" --vanished webkit-a.png
expect_rc 'a missing run directory is refused' 3 python3 "$BATCH" "$work/absent" "$prior" "$work/o14"
expect_rc 'bad usage is refused' 3 python3 "$BATCH"
expect_rc '--help is not a refusal' 0 python3 "$BATCH" --help

printf '\n%s passed, %s failed\n' "$PASS" "$FAILED"
[ "$PASS" -gt 0 ] || { echo 'CANNOT RUN — no case executed' >&2; exit 3; }
[ "$FAILED" -eq 0 ]
