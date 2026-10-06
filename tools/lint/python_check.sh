#!/usr/bin/env bash
# Enrolled maintained Python gates: native renderer drivers, the manual mutation
# campaign drivers and wire contracts.
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
  files+=(renderer/tools/lvgl-reactive-mutations.py renderer/tools/renderer-gen-schema-mutations.py tools/wire_contract_check.py)
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
