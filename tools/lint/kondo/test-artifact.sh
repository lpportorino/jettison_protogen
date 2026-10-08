#!/usr/bin/env bash
# Attribute checksum refusals to artifact and patch bytes independently.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cp "$root/rebuild.py" "$root/artifact.json" "$root/map-return-types.patch" "$root"/*.jar "$work/"
python3 "$work/rebuild.py" --artifact-only >/dev/null
artifact=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["artifact"])' "$work/artifact.json")
printf 'altered' >>"$work/$artifact"
if python3 "$work/rebuild.py" --artifact-only >"$work/result" 2>&1; then
    echo 'FAIL: altered artifact accepted' >&2; exit 1
fi
grep -q 'patched artifact checksum mismatch' "$work/result"
cp "$root/$artifact" "$work/$artifact"
python3 "$work/rebuild.py" --artifact-only >/dev/null
printf 'altered' >>"$work/map-return-types.patch"
if python3 "$work/rebuild.py" --artifact-only >"$work/result" 2>&1; then
    echo 'FAIL: altered patch accepted' >&2; exit 1
fi
grep -q 'patch checksum mismatch' "$work/result"
echo 'PASS: artifact and patch corruption each refused; unmodified controls passed'
