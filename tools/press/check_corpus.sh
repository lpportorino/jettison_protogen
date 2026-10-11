#!/usr/bin/env bash
# Generate in isolation, never rewrite a caller's fixtures during a gate.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
cd "$root/tools/press"
if ! env -u PRESS_ROOT clojure "-J-Dpress.root=$root" -M:corpus "$stage"; then
  printf '[press-corpus] CANNOT RUN: corpus generator failed\n' >&2
  exit 2
fi
python3 test/corpus_compare.py "$root" "$stage"
