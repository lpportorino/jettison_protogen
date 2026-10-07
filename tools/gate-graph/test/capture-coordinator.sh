#!/usr/bin/env bash
# Scope: two public suite units, coordinated in one JVM with real T1 child capture.
set -euo pipefail
if [[ $# != 1 || ! $1 =~ ^[0-9a-f]{64}$ ]]; then
  printf 'usage: %s IMAGE_SHA256_HEX (actual executing image, attested by caller)\n' "$0" >&2
  exit 2
fi
image_digest=$1
assignments=$(tools/gate-trace/bin/trace-start coordinator-dogfood --root .fork-scratch/graph-captures --toolchain-file image=Dockerfile.base)
eval "$assignments"
log_dir="$PWD/.fork-scratch/graph-logs/${TELEMETRY_DIR##*/}"
root="$PWD"
run_rc=0
(
  cd tools/gate-graph
  clojure -M:coordinator-dogfood "$root" "$log_dir" "$image_digest"
) || run_rc=$?
finish_rc=0
tools/gate-trace/bin/trace-finish || finish_rc=$?
printf '%s\n' "$TELEMETRY_DIR" >.fork-scratch/graph-logs/coordinator-journal-path.txt
printf 'coordinator=%s validation=%s journal=%s logs=%s\n' "$run_rc" "$finish_rc" "$TELEMETRY_DIR" "$log_dir"
[[ $run_rc == 0 && $finish_rc == 0 ]]
