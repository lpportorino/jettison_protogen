#!/usr/bin/env bash
# canary.sh — prove the browser acceptance harness FAILS on known viewer defects.
#
#   bash tools/gate-graph/browser/canary.sh EVIDENCE_DIR --fixtures GREEN_FIXTURES
#
# GREEN_FIXTURES is the fixture root of a run that passed (run.sh writes it to
# <evidence>/fixtures). For each mutant below, the fixtures are copied, ONE
# literal or CSS defect is planted in the branch fixture's page — lib_mutate.sh
# proves the edit landed (new text present, old text gone) — and acceptance.mjs
# runs in Chromium. The canary passes only if that run is a FAIL (exit 1 and a
# manifest recording an assertion failure, never an ERROR) whose message names
# the clause the mutant targets. The planted defects mirror what visual review
# found: a light mark under 3:1, a lost cursor halo, an edge casing in the edge's
# own colour, ruler labels away from their times, and a call to action that no
# longer names the gate.
#
# Exit 0 every mutant FAILed for its own reason; 1 a mutant passed or failed for
# another reason; 3 CANNOT RUN (no docker or python3, bad arguments, an existing
# evidence directory, fixtures missing). HOST-ONLY: it drives docker.
set -uo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
root="$(cd -- "$here/../../.." && pwd -P)"
cannot() { printf '[gate-viewer-canary] CANNOT RUN — %s\n' "$*" >&2; exit 3; }
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$root/tools/lint/test/lib_mutate.sh"

evidence="${1:-}"; shift || true
fixtures=""
while [ $# -gt 0 ]; do
  case "$1" in
    --fixtures) fixtures="${2:?--fixtures needs a directory}"; shift 2 ;;
    *) cannot "unknown argument $1 (usage: canary.sh EVIDENCE_DIR --fixtures GREEN_FIXTURES)" ;;
  esac
done
[ -n "$evidence" ] && [ -n "$fixtures" ] || cannot "usage: canary.sh EVIDENCE_DIR --fixtures GREEN_FIXTURES"
command -v docker >/dev/null 2>&1 || cannot "docker is not on PATH"
command -v python3 >/dev/null 2>&1 || cannot "python3 is not on PATH (it reads each run's manifest)"
[ -f "$fixtures/branch/index.html" ] || cannot "$fixtures/branch/index.html missing; pass a green run's fixtures"
[ -e "$evidence" ] && cannot "$evidence already exists; evidence directories are never overwritten"
mkdir -p "$evidence"; evidence="$(cd -- "$evidence" && pwd -P)"; fixtures="$(cd -- "$fixtures" && pwd -P)"
case "$evidence" in "$root"/*) ;; *) cannot "$evidence must sit inside the checkout (it is bind-mounted)" ;; esac
image="$(sed -n 's/^PLAYWRIGHT_IMAGE="\(.*\)"$/\1/p' "$here/run.sh")"
[ -n "$image" ] || cannot "no PLAYWRIGHT_IMAGE line in run.sh"
[ -x "$here/node_modules/.bin/playwright" ] || cannot "node_modules is not installed; run run.sh first (it runs npm ci)"

passed=0 failed=0
# mutant <name> <old> <new> <message-substring>
mutant() {
  local name="$1" old="$2" new="$3" needle="$4" dir="$evidence/$1" err rc verdict
  mkdir -p "$dir"; cp -r "$fixtures" "$dir/fixtures"
  if ! err="$(mutate_file "$dir/fixtures/branch/index.html" "$old" "$new" 2>&1)"; then
    printf '  \033[31mFAIL\033[0m %s — the mutation did not land: %s\n' "$name" "$err" >&2; failed=$((failed + 1)); return
  fi
  docker run --rm --network none -v "$root:/w" -w /w/tools/gate-graph/browser --user "$(id -u):$(id -g)" -e HOME=/tmp \
    "$image" node acceptance.mjs "/w/${dir#"$root"/}/fixtures" "/w/${dir#"$root"/}/run" chromium >"$dir/log" 2>&1
  rc=$?
  verdict="$(python3 - "$dir/run/manifest.json" "$needle" <<'PY'
import json, sys
try:
    failure = json.load(open(sys.argv[1])).get("failure") or {}
except (OSError, ValueError) as error:
    print(f"no readable manifest ({error})"); sys.exit()
if failure.get("kind") != "FAIL":
    print(f"kind {failure.get('kind')!r}, not FAIL: {failure.get('message', '')[:200]}")
elif sys.argv[2] not in failure.get("message", ""):
    print(f"FAILed for another reason: {failure.get('message', '')[:300]}")
else:
    print("ok")
PY
)"
  if [ "$rc" = 1 ] && [ "$verdict" = ok ]; then
    printf '  \033[32mok\033[0m   %s — FAIL names: %s\n' "$name" "$needle"; passed=$((passed + 1))
  else
    printf '  \033[31mFAIL\033[0m %s — exit %s; %s (log: %s)\n' "$name" "$rc" "$verdict" "$dir/log" >&2; failed=$((failed + 1))
  fi
}

printf '[gate-viewer-canary] planting one viewer defect per mutant in a copy of %s\n' "$fixtures"
mutant light-mark '--mark:#2f72ad' '--mark:#c5d3e0' '"label":"mark '
mutant cursor-halo 'box-shadow:-1px 0 0 var(--bg),1px 0 0 var(--bg)' 'box-shadow:none' 'cursor halo missing'
mutant edge-casing '.edge-casing{fill:none;stroke:var(--bg)' '.edge-casing{fill:none;stroke:var(--accent)' 'edge vs casing'
mutant ruler-offset '.ruler span{position:absolute;top:0;transform:translateX(-50%)' '.ruler span{position:absolute;top:0;transform:none' 'each ruler label sits at the time it names'
mutant call-to-action 'Longest gate: ' 'Slowest gate: ' 'names the planted longest gate'

printf '[gate-viewer-canary] %s of %s mutants FAILed for their own reason\n' "$passed" "$((passed + failed))"
[ "$passed" -gt 0 ] || cannot "no mutant ran"
[ "$failed" -eq 0 ]
