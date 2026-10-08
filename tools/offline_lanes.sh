#!/usr/bin/env bash
# offline_lanes.sh — run every gate lane once with the NETWORK REMOVED and
# report which ones still reach for it.
#
#   tools/offline_lanes.sh OUT.tsv [--image TAG]
#
# The lane lists are DERIVED, never copied: `lint-lanes` from lint.mk, the
# docker-gated hook set from .githooks/pre-push, and `check-renderer-lanes` from
# renderer.mk. Each lane runs in its own container through tools/uber.sh with
# UBER_NETWORK=none, inside a fresh local clone of HEAD (lanes write build
# products, so the working tree is never touched). One TSV row per lane:
# makefile, lane, exit status, and the first matched network-failure fragment
# (empty when the log names none — a red lane with no fragment is not offline
# evidence either way; read its log).
#
# Exit: 0 every lane passed offline; 1 at least one did not; 3 CANNOT RUN.
# HOST-ONLY: it drives docker, which the toolchain image does not carry.
set -uo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
out="${1:-}"; shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --image) export PROTOGEN_IMAGE_TAG="${2:?--image needs a tag}"; shift 2 ;;
    *) echo "[offline-lanes] CANNOT RUN — unknown argument $1" >&2; exit 3 ;;
  esac
done
[ -n "$out" ] || { echo "[offline-lanes] CANNOT RUN — usage: offline_lanes.sh OUT.tsv [--image TAG]" >&2; exit 3; }
command -v docker >/dev/null 2>&1 || { echo "[offline-lanes] CANNOT RUN — docker is not on PATH" >&2; exit 3; }
# uber.sh BUILDS a missing image from HEAD's Dockerfile.base under the requested
# tag, which would silently measure the wrong image; so a named one must exist.
if [ -n "${PROTOGEN_IMAGE_TAG:-}" ] && ! docker image inspect "$PROTOGEN_IMAGE_TAG" >/dev/null 2>&1; then
  echo "[offline-lanes] CANNOT RUN — image $PROTOGEN_IMAGE_TAG does not exist; build it first" >&2; exit 3
fi

lanes_from() { sed -n "s/^$2: //p" "$root/$1" | head -1; }
lint_lanes=$(lanes_from lint.mk lint-lanes)
battery_lanes=$(lanes_from renderer.mk check-renderer-lanes)
hook_lanes=$(grep -oE "UBER_NETWORK=none tools/uber.sh 'make -f lint.mk [^']+'" "$root/.githooks/pre-push" | sed -n 's/.*make -f lint.mk //; s/.$//p' | head -1)
for set in lint_lanes battery_lanes hook_lanes; do
  [ -n "${!set}" ] || { echo "[offline-lanes] CANNOT RUN — no lanes discovered for $set" >&2; exit 3; }
done

clone=$(mktemp -d "$root/.fork-scratch/offline-lanes.XXXXXX" 2>/dev/null || mktemp -d)
trap 'rm -rf "$clone"' EXIT
git clone -q "$root" "$clone/repo" || { echo "[offline-lanes] CANNOT RUN — local clone failed" >&2; exit 3; }
: >"$out"
red=0 total=0
run() { # run <makefile> <lane>
  local rc log="$clone/$2.log"
  ( cd "$clone/repo" && UBER_NETWORK=none \
      tools/uber.sh "export LANG=C.UTF-8; make -f $1 $2" >"$log" 2>&1 ); rc=$?
  local hint
  hint=$(grep -aoiE 'Could not (transfer|resolve)[^"]{0,80}|Failed to read artifact[^"]{0,80}|no matching package named[^"]{0,60}|offline mode|not available offline|Unknown host|Temporary failure in name resolution' "$log" | head -1)
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$rc" "$hint" >>"$out"
  total=$((total + 1)); [ "$rc" -eq 0 ] || red=$((red + 1))
}
for l in $lint_lanes $hook_lanes; do run lint.mk "$l"; done
for l in $battery_lanes; do run renderer.mk "$l"; done
echo "[offline-lanes] $total lanes, $red not passing offline -> $out"
[ "$red" -eq 0 ]
