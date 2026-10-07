#!/usr/bin/env bash
# Trusted test controller only: child gates never receive the Docker socket.
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 || ! $1 =~ ^[0-9a-f]{64}$ || ! ${2:-acceptance} =~ ^(acceptance|mutations)$ ]]; then
  printf 'usage: %s IMAGE_SHA256_HEX [acceptance|mutations]\n' "$0" >&2
  exit 2
fi
image_digest=$1
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
docker_binary=$(command -v docker)
mkdir -p "$root/.fork-scratch"
scratch=$(mktemp -d "$root/.fork-scratch/container-acceptance-XXXXXXXX")
printf 'container acceptance evidence: %s\n' "$scratch"
rc=0
docker run --rm --entrypoint bash \
  --mount "type=bind,source=$root,target=$root" \
  --mount "type=bind,source=$docker_binary,target=/usr/bin/docker,readonly" \
  --mount 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock' \
  -w "$root/tools/gate-graph" \
  -e GATE_CONTAINER_SCRATCH="$scratch" -e GATE_CONTAINER_IMAGE="$image_digest" -e GATE_CONTAINER_MODE="${2:-acceptance}" \
  "sha256:$image_digest" -lc \
  'if [[ $GATE_CONTAINER_MODE == mutations ]]; then
     clojure -M:test:container-campaign "$GATE_CONTAINER_SCRATCH"
   else
     clojure -M:test:container-test "$GATE_CONTAINER_SCRATCH" "$GATE_CONTAINER_IMAGE"
   fi' || rc=$?
# Only this fresh evidence directory is reassigned; no other worktree ownership changes.
docker run --rm --entrypoint chown --mount "type=bind,source=$scratch,target=/evidence" \
  "sha256:$image_digest" -R "$(id -u):$(id -g)" /evidence
printf '%s\n' "$scratch" >"$root/.fork-scratch/container-acceptance-path.txt"
exit "$rc"
