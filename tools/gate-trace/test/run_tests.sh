#!/usr/bin/env bash
# run_tests.sh: gate-trace's test lane. The suite, then its fail canary.
#
#   tools/gate-trace/test/run_tests.sh [--suite-only]
#
# The suite (test_gate_trace.py) drives the shipped shims with real children,
# real signals and a real make jobserver. The canary (mutants.py) breaks each
# guarantee alone in a throw-away copy and requires its own test to FAIL, so a
# green here says the tests CAN go red, not merely that they did not.
# `--suite-only` skips the canary; the canary uses it to prove this entry point
# fails on a real fault.
#
# NOT a bare-host lane: it needs dash, bash, GNU make, git and coreutils, which
# the pinned image carries. Run it as tools/uber.sh 'bash
# tools/gate-trace/test/run_tests.sh'. A missing tool is refused with exit 2 by
# name, never skipped.
#
# Exit 0 green; 1 a test failed or a mutant survived; 2 cannot run.
set -euo pipefail

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)

suite_only=0
case "${1-}" in
  "") ;;
  --suite-only) suite_only=1 ;;
  *)
    printf 'usage: %s [--suite-only]\n' "$0" >&2
    exit 2
    ;;
esac

command -v python3 >/dev/null 2>&1 || {
  printf 'gate-trace tests: CANNOT RUN: python3 is not on PATH (it is in the pinned image)\n' >&2
  exit 2
}

python3 -I -S "$here/test_gate_trace.py"
if [[ $suite_only == 1 ]]; then
  exit 0
fi
python3 -I -S "$here/mutants.py"
