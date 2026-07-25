#!/usr/bin/env bash
# tools/gen_fonts.sh — regenerate the compiled-in LVGL fonts (renderer/src/font_*.c)
# from the vendored TTFs in renderer/assets/fonts/, using the version-pinned
# lv_font_conv in the uber image.
#
# CONTAINER-ONLY. font_*.c are committed artifacts, so they are generated in the
# pinned toolchain, never on the host (uber-container.md):
#
#   tools/uber.sh 'tools/gen_fonts.sh'
#
# REPRODUCIBILITY. lv_font_conv is pinned to 1.5.2 in Dockerfile.base — the
# newest release whose rasterizer reproduces the historically-committed glyph
# bitmaps byte-for-byte (1.5.3 changed the anti-aliasing, which would re-mint
# every font-bearing golden). The output is RAW lv_font_conv (no clang-format):
# .clang-format excludes renderer/src/font_*.c as "generated LVGL font data ...
# never handed to the tool", so raw generator output is the canonical form.
# Idempotent: re-running produces byte-identical files.
#
# The TTFs are copied to canonical-cased basenames in a scratch dir before
# invocation so each font's recorded `--font <name>` header is deterministic and
# independent of the on-disk asset filename.
#
# PER-FONT GLYPH RANGE. B612Mono-Bold carries the full target set; Orbitron-Bold
# (a display face) has no U+00B1 or U+2192 glyph, and lv_font_conv ABORTS on a
# range token that matches nothing — so Orbitron's range omits those two. Each
# face contributes exactly the glyphs it actually has; a missing glyph in one
# face is a property of that TTF, not a silent drop.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/renderer/assets/fonts"
OUT="$ROOT/renderer/src"

command -v lv_font_conv >/dev/null 2>&1 || {
  echo "gen_fonts.sh: lv_font_conv not found — run inside the uber image:" >&2
  echo "  tools/uber.sh 'tools/gen_fonts.sh'" >&2
  exit 1
}

# Shared ASCII base + the six added symbol glyphs live once, here.
# The original committed fonts recorded --range 0x20-0x7F; 0x7F (DEL) has no
# glyph in either face, so 0x20-0x7E is byte-equivalent for every existing glyph
# and drops the never-rasterized DEL token.
BASE_ASCII="0x20-0x7E"
DEGREE_DASHES_ELLIPSIS="0xB0,0x2013,0x2014,0x2026"   # ° – — …  (present in both faces)
PLUSMINUS_ARROW="0xB1,0x2192"                        # ± →      (B612 only)

B612_RANGE="${BASE_ASCII},${DEGREE_DASHES_ELLIPSIS},${PLUSMINUS_ARROW}"
ORBITRON_RANGE="${BASE_ASCII},${DEGREE_DASHES_ELLIPSIS}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$ASSETS/b612mono_bold.ttf" "$WORK/B612Mono-Bold.ttf"
cp "$ASSETS/Orbitron-Bold.ttf" "$WORK/Orbitron-Bold.ttf"

# one_font <canonical-ttf> <name> <size> <range>
one_font() {
  local ttf="$1" name="$2" size="$3" range="$4" out
  out="font_${name}_${size}.c"
  ( cd "$WORK" \
    && lv_font_conv --font "$ttf" --bpp 4 --size "$size" --range "$range" \
         --no-compress --format lvgl --lv-include lvgl.h -o "$out" )
  mv "$WORK/$out" "$OUT/$out"
  echo "  wrote renderer/src/$out"
}

echo "gen_fonts.sh: regenerating compiled-in fonts (lv_font_conv $(lv_font_conv --version 2>/dev/null || echo '?'))"
for size in 12 14 16 18 20; do
  one_font B612Mono-Bold.ttf b612mono_bold "$size" "$B612_RANGE"
done
for size in 22 28 32; do
  one_font Orbitron-Bold.ttf orbitron_bold "$size" "$ORBITRON_RANGE"
done
echo "gen_fonts.sh: done (8 fonts)"
