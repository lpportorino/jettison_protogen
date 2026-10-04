#!/usr/bin/env bash
# Real pinned analyzer, deliberate lint/parse/format failures and a clean control.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)
gate="$root/tools/lint/python_check.sh"
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
printf 'value = 1\nprint(value)\n' >"$scratch/clean.py"
printf 'print(undefined_canary)\n' >"$scratch/undefined.py"
printf 'def broken(:\n' >"$scratch/syntax.py"
printf 'value=1\nprint(value)\n' >"$scratch/format.py"
cases=0
check() {
  local name=$1 expected=$2 marker=$3 rc=0
  bash "$gate" "$scratch/$name.py" >"$scratch/$name.log" 2>&1 || rc=$?
  if [[ $rc != "$expected" ]] || ! grep -Fq -- "$marker" "$scratch/$name.log"; then
    cat "$scratch/$name.log" >&2
    printf 'Python canary %s: expected exit %s and marker %s; got %s.\n' \
      "$name" "$expected" "$marker" "$rc" >&2
    exit 1
  fi
  cases=$((cases + 1))
}
check clean 0 '1 file already formatted'
check undefined 1 F821
check syntax 1 invalid-syntax
check format 1 'File would be reformatted'
check absent 2 'missing file'
version=$(sed -n 's/^version=//p' "$root/tools/lint/ruff.sh")
[[ -n "$version" ]]
arch=$(uname -m)
[[ $arch != arm64 ]] || arch=aarch64
mkdir -p "$scratch/cache/$version/$arch"
printf '#!/bin/sh\nexit 0\n' >"$scratch/cache/$version/$arch/ruff"
chmod +x "$scratch/cache/$version/$arch/ruff"
rc=0
RUFF_CACHE_DIR="$scratch/cache" bash "$root/tools/lint/ruff.sh" --version \
  >"$scratch/cache.log" 2>&1 || rc=$?
if [[ $rc != 2 ]] || ! grep -Fq 'cached binary checksum mismatch' "$scratch/cache.log"; then
  cat "$scratch/cache.log" >&2
  printf 'Python canary: corrupt cached tool was not rejected.\n' >&2
  exit 1
fi
cases=$((cases + 1))
[[ $cases == 6 ]]
printf 'Python runner canaries: %s passed.\n' "$cases"
