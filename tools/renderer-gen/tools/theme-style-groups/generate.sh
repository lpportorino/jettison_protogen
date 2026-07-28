#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../../.." && pwd)"
emitter="$script_dir/emit.c"
theme_source="$repo_root/renderer/src/theme.c"
dpi=160
mode=
destination=

usage() {
  echo "usage: generate.sh (--output PATH | --check PATH) [--dpi N] [--theme-source PATH]" >&2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
  --output | --check)
    [[ -z "$mode" && $# -ge 2 ]] || {
      usage
      exit 2
    }
    mode="${1#--}"
    destination=$2
    shift 2
    ;;
  --dpi)
    [[ $# -ge 2 ]] || {
      usage
      exit 2
    }
    dpi=$2
    shift 2
    ;;
  --theme-source)
    [[ $# -ge 2 ]] || {
      usage
      exit 2
    }
    theme_source=$2
    shift 2
    ;;
  *)
    usage
    exit 2
    ;;
  esac
done

[[ -n "$mode" && -n "$destination" ]] || {
  usage
  exit 2
}
[[ "$dpi" =~ ^[0-9]+$ && "$dpi" -gt 0 ]] || {
  echo "FATAL: --dpi must be a positive integer" >&2
  exit 2
}
[[ -f "$theme_source" ]] || {
  echo "FATAL: theme source not found: $theme_source" >&2
  exit 2
}
theme_source="$(cd "$(dirname "$theme_source")" && pwd)/$(basename "$theme_source")"

# Scratch goes to TMPDIR, never into the checkout: this runs from a clean tree
# in CI and must not leave an untracked directory behind.
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/theme-style-groups.XXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT

obj_source="$repo_root/renderer/lvgl/src/core/lv_obj.c"
tree_source="$repo_root/renderer/lvgl/src/core/lv_obj_tree.c"

# The emitter's fake objects depend on these exact, tiny accessor semantics.
# Fail on an upstream LVGL change rather than emitting through a stale model.
python3 - "$obj_source" "$tree_source" <<'PY'
import pathlib
import re
import sys

obj = pathlib.Path(sys.argv[1]).read_text()
tree = pathlib.Path(sys.argv[2]).read_text()

def function_body(source, name):
    match = re.search(rf"\b{name}\s*\([^)]*\)\s*\{{", source)
    if match is None:
        raise SystemExit(f"FATAL: cannot find {name}; update and re-verify the emitter")
    start = source.find("{", match.start())
    depth = 0
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start + 1 : index]
    raise SystemExit(f"FATAL: cannot delimit {name}; update and re-verify the emitter")

checks = [
    (
        function_body(obj, "lv_obj_check_type"),
        r"if\s*\(\s*obj\s*==\s*NULL\s*\)\s*return\s+false\s*;"
        r"\s*return\s+obj->class_p\s*==\s*class_p\s*;",
        "lv_obj_check_type no longer performs an exact class-pointer comparison",
    ),
    (
        function_body(tree, "lv_obj_get_parent"),
        r"return\s+obj->parent\s*;",
        "lv_obj_get_parent no longer returns obj->parent",
    ),
    (
        function_body(tree, "lv_obj_get_child"),
        r"if\s*\(\s*obj->spec_attr\s*==\s*NULL\s*\)"
        r"\s*return\s+NULL\s*;"
        r".*return\s+obj->spec_attr->children\[(?:idx|idu)\]\s*;",
        "lv_obj_get_child no longer reads spec_attr->children by index",
    ),
]
for source, pattern, message in checks:
    if re.search(pattern, source, re.S) is None:
        raise SystemExit(f"FATAL: {message}; update and re-verify the emitter")
PY

hash_file() {
  sha256sum "$1" | awk '{print $1}'
}

theme_header="$repo_root/renderer/src/theme.h"
tokens_header="$repo_root/renderer/generated/theme_tokens.h"
renderer_config="$repo_root/renderer/lv_conf.h"
lvgl_pin="$repo_root/renderer/lvgl/.ported-from.edn"
native_cc="${CC:-gcc}"
binary="$scratch_dir/emit"
fresh="$scratch_dir/theme-style-groups.json"

"$native_cc" \
  -std=c2x -Wall -Wextra -Werror \
  -ffunction-sections -fdata-sections -Wl,--gc-sections \
  -DLV_CONF_INCLUDE_SIMPLE \
  "-DTHEME_SOURCE_PATH=\"$theme_source\"" \
  -I"$repo_root/renderer" \
  -I"$repo_root/renderer/lvgl" \
  -I"$repo_root/renderer/src" \
  -I"$repo_root/renderer/generated" \
  -o "$binary" \
  "$emitter" \
  "$repo_root/renderer/lvgl/src/misc/lv_color.c" \
  "$repo_root/renderer/lvgl/src/misc/lv_palette.c"

"$binary" \
  "$dpi" \
  "$(hash_file "$theme_source")" \
  "$(hash_file "$theme_header")" \
  "$(hash_file "$tokens_header")" \
  "$(hash_file "$renderer_config")" \
  "$(hash_file "$lvgl_pin")" \
  "$(hash_file "$emitter")" \
  >"$fresh"

case "$mode" in
output)
  mkdir -p "$(dirname "$destination")"
  cp "$fresh" "$destination"
  echo "theme-style-groups: emitted from $theme_source -> $destination"
  ;;
check)
  if [[ ! -f "$destination" ]] || ! cmp -s "$destination" "$fresh"; then
    echo "FATAL: $destination differs from compiled theme.c projection" >&2
    if [[ -f "$destination" ]]; then
      diff -u "$destination" "$fresh" | sed -n '1,160p' >&2 || true
    fi
    exit 1
  fi
  echo "theme-style-groups: fresh ($destination)"
  ;;
esac
