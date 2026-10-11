#!/usr/bin/env bash
# Enrolled maintained Python gates: native renderer drivers, the manual mutation
# campaign drivers, wire contracts, the gate-trace tool (its library, its test
# suite and its fail canary), the viewer visual-review batch resolver, and the
# mutation campaign driver, and the document press (tools/press: the tool package
# and its selftest fixtures; judged as Python 3.10, the interpreter of its own
# image — .ruff.toml per-file-target-version).
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
  files+=(tools/lint/kondo/rebuild.py renderer/tools/lvgl-reactive-mutations.py renderer/tools/renderer-gen-schema-mutations.py tools/wire_contract_check.py .claude/skills/viewer-visual-review/batch.py tools/mutation/campaign.py)
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
  # press: the whole package tree and the selftest drivers, discovered as REGULAR
  # files, with one named module per directory asserted AMONG what was discovered
  # (the same membership rule as gate-trace, for the same reason).
  mapfile -d '' found < <(find tools/press/press tools/press/fixtures tools/press/test -name '*.py' -type f -print0)
  for required in tools/press/press/deck_qa.py tools/press/press/deck_pdf.py \
    tools/press/press/fig/cli.py tools/press/press/chart/cli.py \
    tools/press/press/paged/detect.py tools/press/fixtures/fig_selftest.py \
    tools/press/test/corpus_compare.py tools/press/test/corpus_compare_test.py; do
    discovered=0
    for path in "${found[@]}"; do
      [[ $path != "$required" ]] || discovered=1
    done
    ((discovered)) || {
      printf 'Python gate: press module %s is missing or not a regular file\n' "$required" >&2
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
