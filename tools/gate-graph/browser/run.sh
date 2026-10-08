#!/usr/bin/env bash
# run.sh — the ONE entry point for the offline viewer's browser acceptance.
#
#   tools/gate-graph/browser/run.sh EVIDENCE_DIR [--fixtures DIR]
#
# Builds the public synthetic fixtures (unless --fixtures names prebuilt ones),
# installs the lockfile-pinned Playwright, then runs acceptance.mjs in the
# digest-pinned Playwright image with the network REMOVED: Chromium with
# rotation, and WebKit in --fixed-viewports mode (its rotation is not accepted,
# see README.md). EVIDENCE_DIR receives one directory per engine, each with
# manifest.json, the screenshots, a JSON sidecar per screenshot (VLM review
# brief: expectations + DOM summary) and vlm-index.json.
#
# HOST-ONLY: it drives docker. lint.mk's `gate-viewer-acceptance` and the
# gate-viewer workflow both call this script, so the two cannot drift.
#
# Exit: 0 green; 1 FAIL (an assertion about the viewer did not hold);
# 2 ERROR (no assertion recorded a failure: a crash, a launch failure or a
#   timeout — not a verdict either way; a timeout can be a removed control);
# 3 CANNOT RUN (docker or python3 missing, bad arguments, evidence dir already
#   exists, a prebuilt fixture missing). A fixture BUILD that fails is ERROR.
set -euo pipefail

PLAYWRIGHT_IMAGE="mcr.microsoft.com/playwright:v1.64.0-noble@sha256:06a9939e57531807f8d5fd76ce44b53165ffb7d7501d87ab10e285c20b1e971f"
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
root=$(cd -- "$here/../../.." && pwd -P)
cannot_run() { printf '\033[31m[gate-viewer-acceptance] CANNOT RUN\033[0m — %s\n' "$*" >&2; exit 3; }

evidence="${1:-}"; shift || true
fixtures=""
while [ $# -gt 0 ]; do
  case "$1" in
    --fixtures) fixtures="${2:?--fixtures needs a directory}"; shift 2 ;;
    *) cannot_run "unknown argument $1 (usage: run.sh EVIDENCE_DIR [--fixtures DIR])" ;;
  esac
done
[ -n "$evidence" ] || cannot_run "usage: run.sh EVIDENCE_DIR [--fixtures DIR]"
command -v docker >/dev/null 2>&1 || cannot_run "docker is not on PATH (this lane runs browsers in the pinned Playwright image)"
command -v python3 >/dev/null 2>&1 || cannot_run "python3 is not on PATH (it reads each run's manifest to tell FAIL from ERROR)"
[ -e "$evidence" ] && cannot_run "$evidence already exists; evidence directories are never overwritten"
mkdir -p "$evidence"; evidence=$(cd -- "$evidence" && pwd -P)
case "$evidence" in "$root"/*) ;; *) cannot_run "$evidence must sit inside the checkout (it is bind-mounted)";; esac

if [ -z "$fixtures" ]; then
  fixtures="$evidence/fixtures"
  rel="${fixtures#"$root"/}"
  "$root/tools/uber.sh" "cd tools/gate-graph && clojure -Sdeps '{:aliases {:fx {:extra-paths [\"test\"]}}}' -M:fx -m gate.view.fixtures '/workspace/$rel'" \
    || { printf '[gate-viewer-acceptance] ERROR — fixture build failed\n' >&2; exit 2; }
fi
fixtures=$(cd -- "$fixtures" && pwd -P)
for f in branch dense signals incomplete malformed leaf open-signals; do
  [ -s "$fixtures/$f/index.html" ] || cannot_run "fixture $f/index.html missing under $fixtures"
done

uid="$(id -u):$(id -g)"
mount=(-v "$root:/w" -w /w/tools/gate-graph/browser --user "$uid" -e HOME=/tmp -e npm_config_cache=/tmp/.npm)
docker run --rm "${mount[@]}" "$PLAYWRIGHT_IMAGE" npm ci --no-audit --no-fund --loglevel=error \
  || { printf '[gate-viewer-acceptance] ERROR — npm ci failed\n' >&2; exit 2; }

in_container() { printf '/w/%s' "${1#"$root"/}"; }
worst=0
for engine in chromium "webkit --fixed-viewports"; do
  set -- $engine
  out="$evidence/$1"
  rc=0
  docker run --rm --network none "${mount[@]}" "$PLAYWRIGHT_IMAGE" \
    node acceptance.mjs "$(in_container "$fixtures")" "$(in_container "$out")" "$@" || rc=$?
  # Node exits 1 on ANY uncaught exception — a syntax error, a crash before the
  # harness starts — which is also the harness's FAIL code. So a 1 is only a FAIL
  # when the manifest exists and records an assertion failure; otherwise ERROR.
  # Parsed, never grepped: a FAIL verdict must not depend on the writer's whitespace.
  if [ "$rc" -eq 1 ] && ! python3 -c 'import json,sys; sys.exit(0 if (json.load(open(sys.argv[1])).get("failure") or {}).get("kind") == "FAIL" else 1)' "$out/manifest.json" 2>/dev/null; then rc=2; fi
  case "$rc" in
    0) printf '\033[32m[gate-viewer-acceptance]\033[0m %s green\n' "$1" ;;
    1) printf '\033[31m[gate-viewer-acceptance] FAIL\033[0m %s — see %s/manifest.json\n' "$1" "$out" >&2 ;;
    *) printf '\033[31m[gate-viewer-acceptance] ERROR\033[0m %s exited %s with no recorded assertion failure — a crash, a timeout or a missing control; see %s\n' "$1" "$rc" "$out" >&2; rc=2 ;;
  esac
  [ "$rc" -gt "$worst" ] && worst=$rc
done
exit "$worst"
