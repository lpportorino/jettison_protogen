#!/usr/bin/env bash
# Scoped public dogfood chain, executed through tools/uber.sh from the repo root.
# Both independent suites run concurrently; gate exit codes and validation survive.
set -euo pipefail
assignments=$(tools/gate-trace/bin/trace-start graph-dogfood --root .fork-scratch/graph-captures --toolchain-file image=Dockerfile.base)
eval "$assignments"
log_dir=".fork-scratch/graph-logs/${TELEMETRY_DIR##*/}"
mkdir -p "$log_dir"
# The two suites run as background jobs. A non-interactive shell starts an
# asynchronous job with SIGINT and SIGQUIT IGNORED (POSIX 2.11), trace-run
# faithfully hands the child the caller's dispositions, and a JVM that starts
# with SIGINT ignored never installs its own handler — so the gate-graph suite's
# `kill -INT` cancellation case silently cannot fire and the captured report is
# red for a reason that has nothing to do with the code under test. Job control
# gives each job default dispositions; the check below refuses to continue if a
# job ever inherits an ignored INT/QUIT again, because that red would otherwise
# look exactly like a real failure.
set -m
signals_default() {
  local mask
  mask=$(awk '/^SigIgn:/ { print $2 }' "/proc/$1/status" 2>/dev/null) || return 1
  [[ -n $mask ]] && (( (16#$mask & 0x6) == 0 ))
}
tools/gate-trace/bin/trace-run graph-tests --kind gate -- bash -c 'cd tools/gate-graph && clojure -M:test' >"$log_dir/graph.log" 2>&1 &
graph_pid=$!
tools/gate-trace/bin/trace-run capture-tests --kind gate -- bash tools/gate-trace/test/run_tests.sh --suite-only >"$log_dir/capture.log" 2>&1 &
capture_pid=$!
for pid in "$graph_pid" "$capture_pid"; do
  if ! signals_default "$pid"; then
    printf 'capture-public: job %s started with SIGINT/SIGQUIT ignored; the signal cases cannot run honestly\n' "$pid" >&2
    kill "$graph_pid" "$capture_pid" 2>/dev/null
    exit 3
  fi
done
graph_rc=0
capture_rc=0
wait "$graph_pid" || graph_rc=$?
wait "$capture_pid" || capture_rc=$?
finish_rc=0
tools/gate-trace/bin/trace-finish || finish_rc=$?
printf '%s\n' "$TELEMETRY_DIR" >.fork-scratch/graph-logs/journal-path.txt
printf 'graph=%s capture=%s validation=%s journal=%s\n' "$graph_rc" "$capture_rc" "$finish_rc" "$TELEMETRY_DIR"
[[ $graph_rc == 0 && $capture_rc == 0 && $finish_rc == 0 ]]
