#!/usr/bin/env bash
# Enrolled maintained Python gates: native renderer drivers, the manual mutation
# campaign drivers, wire contracts, and the gate-trace tool (its library, its test
# suite and its fail canary).
# Other experiment/data scripts are outside this deliberately bounded lane.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
cd "$root"
if (($#)); then
  files=("$@")
else
  for required in renderer/tools/lvgl-api-selftest.py renderer/tools/lvgl-global-subject-selftest.py renderer/tools/lvgl-reactive-mutations.py renderer/tools/renderer-gen-schema-mutations.py; do
    [[ -f "$required" ]] || {
      printf 'Python gate: missing driver %s\n' "$required" >&2
      exit 2
    }
  done
  mapfile -d '' files < <(find renderer/tools -maxdepth 1 -name '*selftest.py' -type f -print0)
  ((${#files[@]} >= 2)) || {
    printf 'Python gate: expected both native probe drivers; discovered %s.\n' "${#files[@]}" >&2
    exit 2
  }
  files+=(tools/lint/kondo/rebuild.py renderer/tools/lvgl-reactive-mutations.py renderer/tools/renderer-gen-schema-mutations.py tools/wire_contract_check.py)
  # gate-trace: every module in its two directories, discovered as REGULAR files,
  # and each named module asserted to be AMONG what was discovered. An existence
  # test would follow a symlink that discovery skips, and pass while the module
  # went unjudged; membership refuses a missing module, a symlinked one and an
  # empty discovery alike, in each directory.
  mapfile -d '' found < <(find tools/gate-trace/lib tools/gate-trace/test -maxdepth 1 \
    -name '*.py' -type f -print0)
  for required in tools/gate-trace/lib/trace_schema.py tools/gate-trace/lib/trace_run.py \
    tools/gate-trace/lib/trace_store.py tools/gate-trace/test/test_gate_trace.py \
    tools/gate-trace/test/mutants.py; do
    discovered=0
    for path in "${found[@]}"; do
      [[ $path != "$required" ]] || discovered=1
    done
    ((discovered)) || {
      printf 'Python gate: gate-trace module %s is missing or not a regular file\n' "$required" >&2
      exit 2
    }
  done
  files+=("${found[@]}")
fi
for file in "${files[@]}"; do
  [[ -f "$file" ]] || {
    printf 'Python gate: missing file %s\n' "$file" >&2
    exit 2
  }
done
printf 'Python gate: %s files\n' "${#files[@]}"
bash tools/lint/ruff.sh check --no-cache --config .ruff.toml "${files[@]}"
bash tools/lint/ruff.sh format --check --no-cache --config .ruff.toml "${files[@]}"
