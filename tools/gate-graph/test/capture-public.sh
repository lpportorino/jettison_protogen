#!/usr/bin/env bash
# Scoped public dogfood chain, executed through tools/uber.sh from the repo root.
# Both independent suites run concurrently; gate exit codes and validation survive.
set -euo pipefail
assignments=$(tools/gate-trace/bin/trace-start graph-dogfood --root .fork-scratch/graph-captures --toolchain-file image=Dockerfile.base)
eval "$assignments"
log_dir=".fork-scratch/graph-logs/${TELEMETRY_DIR##*/}"
mkdir -p "$log_dir"
tools/gate-trace/bin/trace-run graph-tests --kind gate -- bash -c 'cd tools/gate-graph && clojure -M:test' >"$log_dir/graph.log" 2>&1 &
graph_pid=$!
tools/gate-trace/bin/trace-run capture-tests --kind gate -- bash tools/gate-trace/test/run_tests.sh --suite-only >"$log_dir/capture.log" 2>&1 &
capture_pid=$!
graph_rc=0
capture_rc=0
wait "$graph_pid" || graph_rc=$?
wait "$capture_pid" || capture_rc=$?
finish_rc=0
tools/gate-trace/bin/trace-finish || finish_rc=$?
printf '%s\n' "$TELEMETRY_DIR" >.fork-scratch/graph-logs/journal-path.txt
printf 'graph=%s capture=%s validation=%s journal=%s\n' "$graph_rc" "$capture_rc" "$finish_rc" "$TELEMETRY_DIR"
[[ $graph_rc == 0 && $capture_rc == 0 && $finish_rc == 0 ]]
