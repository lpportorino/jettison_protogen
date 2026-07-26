#!/usr/bin/env bash
# Coverage dual-oracle matrix.
#
# For every covered (property, value) row, render the SAME fixed-geometry
# scene on BOTH paths and assert tree + framebuffer identity:
#   proto path      controls.wasm  + an EDN fixture            -> .pb
#   reference path  reference.wasm + a 2-byte [prop, value] selector
# The two paths share no codegen (plan R5). For geometry-moving props (align)
# the TREE is the sharp oracle; for geometry-invariant props (text-align) the
# FRAMEBUFFER is — both are always asserted, tolerance-0.
#
# Usage: bash coverage_matrix/run.sh
# Exit:  0 = every row matches; 1 = at least one diverged.
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$(pwd)"
# Root-anchored exec helpers (dev / dev_w / rgen), derived from $ROOT — the
# renderer tree root (WS=$ROOT). In-container only; fails loud on the host.
source "$ROOT/tools/in-container.sh"

# Build BOTH wasm paths + the harness here, not in the caller: a stale
# reference.wasm against a fresh controls.wasm makes every text-bearing row
# diverge on the tree oracle (the two dumps disagree on dump format/content),
# which reads as a rendering bug when it is only artifact drift.
echo "── building controls.wasm + reference.wasm + harness ──"
# -j$(nproc) to match renderer.mk's own wasm lane, which has always built these
# same two targets in parallel. This call site did not, so the identical build
# ran SERIALLY here: measured on a clean tree, 80.6s real / 73.3s user (0.91x)
# without the flag versus 22.9s real / 100.4s user (4.4x) with it — a 3.5x
# ratio, large enough to survive the CPU contention this box sees.
# Usually incremental in a battery run (the wasm lane has normally built it
# already), so the everyday saving is far smaller — but it is exactly when a
# renderer source DID change, which is when the matrix is worth running at all,
# that the full rebuild happens here.
dev make -f wasm.mk -j"$(nproc)" all reference
dev_w wasm_harness bash -c 'PATH=$HOME/.cargo/bin:$PATH cargo build --quiet --release'

MATRIX=coverage_matrix
FIX="$MATRIX/fixtures"
PB="$MATRIX/pb"
OUT="$MATRIX/out"
rm -rf "$ROOT/$FIX" "$ROOT/$PB" "$ROOT/$OUT"
mkdir -p "$ROOT/$FIX" "$ROOT/$PB" "$ROOT/$OUT"

# ── Row definitions ──────────────────────────────────────────────────────────
# Each row: "<case-id> <ref-prop-byte> <value>" plus a fixture writer below.

# lv_align_t (ref prop 0): EDN keyword per value 0..21 (lv_area.h order).
ALIGN_KWS=(default top-left top-mid top-right bottom-left bottom-mid bottom-right
  left-mid right-mid center out-top-left out-top-mid out-top-right
  out-bottom-left out-bottom-mid out-bottom-right out-left-top out-left-mid
  out-left-bottom out-right-top out-right-mid out-right-bottom)

# lv_text_align_t (ref prop 1): values 0..3 (AUTO LEFT CENTER RIGHT); the EDN
# style prop is uint pass-through, so the fixture carries the integer.
TEXT_ALIGN_N=4

# lv_text_decor_t (ref prop 2): bitmask — NONE, UNDERLINE, STRIKETHROUGH, both.
TEXT_DECOR_VALS=(0 1 2 3)

# lv_border_side_t (ref prop 3): sparse bitmask — NONE BOTTOM TOP LEFT RIGHT
# FULL INTERNAL (direct-cast: the EDN uint IS the LVGL value).
BORDER_SIDE_VALS=(0 1 2 4 8 15 16)

# lv_flex_flow_t (ref prop 4): the LUT enum — EDN layout keyword on the proto
# side, the LVGL bitmask value (extracted, NOT the wire number) on the
# reference side. This family render-verifies the generated flex_flow_lut.
FLEX_KWS=(flex-row flex-col flex-row-wrap flex-row-reverse
  flex-row-wrap-reverse flex-col-wrap flex-col-reverse flex-col-wrap-reverse)
FLEX_VALS=(0 1 4 8 12 5 9 13)

# lv_bar_mode_t (ref prop 5): the first widget_props-oneof family. A bar over
# range [-50,50] value 30 start 10 — NORMAL/SYMMETRICAL/RANGE draw from
# min/zero/start respectively, three distinct renders.
BAR_MODE_KWS=(normal symmetrical range)

# lv_arc_mode_t (ref prop 6) / lv_roller_mode_t (ref prop 7) /
# lv_scale_mode_t (ref prop 8, sparse bitmask) — widget_props mode families.
ARC_MODE_KWS=(normal symmetrical reverse)
ROLLER_MODE_KWS=(normal infinite)
SCALE_MODE_KWS=(horizontal-top horizontal-bottom
  vertical-left vertical-right
  round-inner round-outer)
SCALE_MODE_VALS=(0 1 2 4 8 16)

# Widget singles (ref prop 9): every renderer-buildable WidgetType (wire
# order). Each inside the standard box at 80x40; text on label/checkbox/
# textarea as the renderer applies it. lv_tabview (wire 19) is a separate
# explicit row below — the array index doubles as the wire value byte here,
# so the array must stay dense and in wire order. buttonmatrix (17) and
# table (18) carry no props in this family, so both paths keep LVGL's own
# defaults (default map / 1x1 grid); their PROPS are gated by
# wasm_harness/tests/visual_regression.rs widget_identity.
WIDGET_TAGS=(lv_obj lv_button lv_label lv_slider lv_image lv_arc lv_bar
  lv_switch lv_checkbox lv_dropdown lv_roller lv_textarea lv_spinbox
  lv_spinner lv_led lv_line lv_scale lv_buttonmatrix lv_table)

# Widget combos (ref prop 10): mixed-flex-row, nested boxes, styled button,
# and the all-widgets kitchen-sink mega scene.
COMBO_N=4

# Image-pipeline parity (ref prop 13, plan D4a). carray_* rows: the
# reference side renders the COMPILED demo C array while the proto side
# loads its byte-exact extracted PNG twin over P: (upstream image-extract step)
# — pinning runtime-lodepng-decode == compiled-array. file_* rows: BOTH
# sides load the same P: file (PNG lodepng / SVG ThorVG decoder parity);
# file_png_square and file_svg_square are the SAME visual authored as PNG
# and as SVG — each row gates its format against its own reference
# render, never PNG against SVG (vector AA is not raster decode).
IMAGE_IDS=(carray_avatar carray_logo carray_clothes carray_needle
  file_png_avatar file_png_square file_svg_square file_svg_shapes)
IMAGE_SRCS=("P:images/demo/avatar.png" "P:images/demo/lvgl_logo.png"
  "P:images/demo/clothes.png" "P:images/demo/needle.png"
  "P:images/demo/avatar.png" "P:images/test_square.png"
  "P:icons/test_square.svg" "P:icons/test_shapes.svg")

# ── 1. Fixtures ──────────────────────────────────────────────────────────────
for kw in "${ALIGN_KWS[@]}"; do
  cat >"$ROOT/$FIX/align_${kw}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :style {:width 100 :height 50 :align :${kw}}}}
EDN
done
for ((v = 0; v < TEXT_ALIGN_N; v++)); do
  cat >"$ROOT/$FIX/textalign_${v}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :style {:width 100 :height 50 :align :center}
        :children [{:tag :lv_label :text "Ag"
                    :style {:width 80 :text-align ${v}}}]}}
EDN
done
for v in "${TEXT_DECOR_VALS[@]}"; do
  cat >"$ROOT/$FIX/textdecor_${v}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :style {:width 100 :height 50 :align :center}
        :children [{:tag :lv_label :text "Ag"
                    :style {:width 80 :text-decor ${v}}}]}}
EDN
done
for v in "${BORDER_SIDE_VALS[@]}"; do
  cat >"$ROOT/$FIX/borderside_${v}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :style {:width 100 :height 50 :align :center
                             :border-side ${v}}}}
EDN
done
for i in "${!BAR_MODE_KWS[@]}"; do
  cat >"$ROOT/$FIX/barmode_${i}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_bar
        :bar_props {:min_value -50 :max_value 50 :value 30 :start_value 10
                    :mode :${BAR_MODE_KWS[$i]}}
        :style {:width 80 :height 10 :align :center}}}
EDN
done
for i in "${!ARC_MODE_KWS[@]}"; do
  cat >"$ROOT/$FIX/arcmode_${i}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_arc
        :arc_props {:mode :${ARC_MODE_KWS[$i]} :min_value 0 :max_value 100
                    :value 60 :start_angle 135 :end_angle 45
                    :bg_start_angle 135 :bg_end_angle 45}
        :style {:width 60 :height 60 :align :center}}}
EDN
done
for i in "${!ROLLER_MODE_KWS[@]}"; do
  cat >"$ROOT/$FIX/rollermode_${i}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_roller
        :roller_props {:options "a\nb\nc" :selected 1 :visible_row_count 2
                       :mode :${ROLLER_MODE_KWS[$i]}}
        :style {:width 80 :align :center}}}
EDN
done
for i in "${!SCALE_MODE_KWS[@]}"; do
  cat >"$ROOT/$FIX/scalemode_${i}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_scale
        :scale_props {:mode :${SCALE_MODE_KWS[$i]} :min_value 0 :max_value 100
                      :total_tick_count 5 :major_tick_every 2
                      :label_show true}
        :style {:width 60 :height 60 :align :center}}}
EDN
done
for kw in "${FLEX_KWS[@]}"; do
  cat >"$ROOT/$FIX/flexflow_${kw}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :layout :${kw}
        :style {:width 100 :height 50 :align :center}
        :children [{:tag :lv_obj :style {:width 20 :height 10}}
                   {:tag :lv_obj :style {:width 20 :height 10}}
                   {:tag :lv_obj :style {:width 20 :height 10}}]}}
EDN
done

for i in "${!WIDGET_TAGS[@]}"; do
  tag="${WIDGET_TAGS[$i]}"
  text=""
  case "$tag" in
    lv_label | lv_checkbox) text=' :text "Ag"' ;;
    lv_textarea) text=' :text "Ag" :id "ta"' ;;
    lv_spinbox) text=' :id "sb"' ;;
  esac
  cat >"$ROOT/$FIX/widget_${i}_${tag}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :style {:width 100 :height 50 :align :center}
        :children [{:tag :${tag}${text}
                    :style {:width 80 :height 40}}]}}
EDN
done

# lv_tabview single (wire 19): bar size + 3 named tabs (one plain obj of
# page content each, zipped from children) + active tab 1 — mirrors
# reference_ui.c make_widget kind 19 exactly (names are constants on both
# sides, same convention as the "Ag" text rows). NO explicit size on the
# tabview: its class defaults (PCT(100)) are LOCAL styles that outrank
# style-group w/h on the proto path, so both paths let pct-fill rule
# inside a larger box (the spinbox sizing note in reference_ui.c is the
# same local-vs-added-style class).
cat >"$ROOT/$FIX/widget_19_lv_tabview.edn" <<'EDN'
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :style {:width 300 :height 200 :align :center}
        :children [{:tag :lv_tabview
                    :tabview_props {:tab_names ["A" "B" "C"]
                                    :tab_bar_size 20
                                    :active_index 1}
                    :children [{:tag :lv_obj}
                               {:tag :lv_obj}
                               {:tag :lv_obj}]}]}}
EDN

# lv_chart single (wire 20): series DATA cannot ride the 2-byte selector,
# so — per the tabview precedent — the scene constants (LINE type, 8
# points, div lines 0,12, two fixed-color PRIMARY_Y series) are hardcoded
# identically on both sides: here in the EDN and in reference_ui.c
# make_widget kind 20. The 0 hdiv pins the explicit-zero div-count path.
cat >"$ROOT/$FIX/widget_20_lv_chart.edn" <<'EDN'
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :style {:width 300 :height 200 :align :center}
        :children [{:tag :lv_chart :id "chart"
                    :style {:width 280 :height 160}
                    :chart_props {:type :line
                                  :point_count 8
                                  :div_lines [0 12]
                                  :series [{:color {:r 239 :g 68 :b 68}
                                            :axis :primary-y
                                            :values [10 25 18 40 32 48 27 35]}
                                           {:color {:r 59 :g 130 :b 246}
                                            :axis :primary-y
                                            :values [45 12 30 8 38 22 41 15]}]}}]}}
EDN

for i in "${!IMAGE_IDS[@]}"; do
  cat >"$ROOT/$FIX/image_${IMAGE_IDS[$i]}.edn" <<EDN
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :style {:width 220 :height 220 :align :center}
        :children [{:tag :lv_image
                    :image_props {:src "${IMAGE_SRCS[$i]}"}}]}}
EDN
done

cat >"$ROOT/$FIX/combo_0.edn" <<'EDN'
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :layout :flex-row
        :style {:width 300 :height 60 :align :center}
        :children [{:tag :lv_button :style {:width 60 :height 20}}
                   {:tag :lv_slider :style {:width 60 :height 20}}
                   {:tag :lv_bar :style {:width 60 :height 20}}]}}
EDN
cat >"$ROOT/$FIX/combo_1.edn" <<'EDN'
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :style {:width 200 :height 120 :align :center}
        :children [{:tag :lv_obj :style {:width 140 :height 80 :align :center}
                    :children [{:tag :lv_obj
                                :style {:width 80 :height 40
                                        :align :center}}]}]}}
EDN
cat >"$ROOT/$FIX/combo_2.edn" <<'EDN'
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_button
        :style {:width 120 :height 50 :align :center
                :radius 10 :border-width 3 :pad-all 8}
        :children [{:tag :lv_label :text "OK"}]}}
EDN
cat >"$ROOT/$FIX/combo_3.edn" <<'EDN'
{:type :screen
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :events {}
 :tree {:tag :lv_obj :layout :flex-col
        :style {:width 700 :height 400 :align :center}
        :children
        [{:tag :lv_obj :layout :flex-row :style {:width 660 :height 110}
          :children [{:tag :lv_obj :style {:width 90 :height 70}}
                     {:tag :lv_button :style {:width 90 :height 70}}
                     {:tag :lv_label :text "Ag" :style {:width 90 :height 70}}
                     {:tag :lv_slider :style {:width 90 :height 70}}
                     {:tag :lv_image :style {:width 90 :height 70}}
                     {:tag :lv_arc :style {:width 90 :height 70}}]}
         {:tag :lv_obj :layout :flex-row :style {:width 660 :height 110}
          :children [{:tag :lv_bar :style {:width 90 :height 70}}
                     {:tag :lv_switch :style {:width 90 :height 70}}
                     {:tag :lv_checkbox :text "Ag"
                      :style {:width 90 :height 70}}
                     {:tag :lv_dropdown :style {:width 90 :height 70}}
                     {:tag :lv_roller :style {:width 90 :height 70}}
                     {:tag :lv_textarea :text "Ag" :id "ta"
                      :style {:width 90 :height 70}}]}
         {:tag :lv_obj :layout :flex-row :style {:width 660 :height 110}
          :children [{:tag :lv_spinbox :id "sb" :style {:width 90 :height 70}}
                     {:tag :lv_spinner :style {:width 90 :height 70}}
                     {:tag :lv_led :style {:width 90 :height 70}}
                     {:tag :lv_line :style {:width 90 :height 70}}
                     {:tag :lv_scale :style {:width 90 :height 70}}
                     {:tag :lv_scale :style {:width 90 :height 70}}]}]}}
EDN

# ── 2. Codegen every fixture to a .pb in one pass ────────────────────────────
echo "── codegen $(ls "$ROOT/$FIX" | wc -l) matrix fixtures ──"
rgen clojure -M:codegen --tokens ../../output/manifests/design-tokens.json --input "$ROOT/$FIX" --output "$ROOT/$PB" \
  >/dev/null

# ── 3. Render both paths per row; byte-compare trees + framebuffers ──────────
pass=0
fail=0

run_row() { # <case-id> <ref-prop> <value>
  local id=$1 prop=$2 val=$3
  printf "$(printf '\\x%02x\\x%02x' "$prop" "$val")" >"$ROOT/$OUT/sel_${id}.bin"

  # --assert-content: identical-but-BLANK screens would pass the equality
  # oracles; the dominant-color sanity rejects (near-)flat renders.
  # --wasi-root: the canonical assets/ tree, preopened identically for both
  # paths — the image rows read P:images/… and P:icons/… through it.
  dev_w wasm_harness ./target/release/lvgl_harness --wasm "$WS/output/controls.wasm" --wasi-root "$WS/assets" \
    --pb "$WS/$PB/${id}.pb" --output "$WS/$OUT" --dump-tree \
    --assert-content >/dev/null 2>&1 ||
    {
      echo "  ${id}: PROTO RENDER/CONTENT FAILED"
      fail=$((fail + 1))
      return
    }
  dev_w wasm_harness ./target/release/lvgl_harness --wasm "$WS/output/reference.wasm" --wasi-root "$WS/assets" \
    --pb "$WS/$OUT/sel_${id}.bin" --output "$WS/$OUT" --dump-tree \
    --assert-content >/dev/null 2>&1 ||
    {
      echo "  ${id}: REFERENCE RENDER/CONTENT FAILED"
      fail=$((fail + 1))
      return
    }

  local proto="$ROOT/$OUT/${id}_bp0_light.tree.json"
  local refr="$ROOT/$OUT/sel_${id}_bp0_light.tree.json"
  local proto_png="$ROOT/$OUT/${id}_bp0_light.png"
  local ref_png="$ROOT/$OUT/sel_${id}_bp0_light.png"
  local tree_ok=no px_ok=no
  # The proto path carries codegen-assigned "uid" identity (absent from the
  # reference path BY DESIGN — no codegen there); strip it before the
  # geometry byte-compare.
  sed 's/"uid":[0-9]*,//g' "$proto" | cmp -s - "$refr" && tree_ok=yes
  cmp -s "$proto_png" "$ref_png" && px_ok=yes
  if [ "$tree_ok" = yes ] && [ "$px_ok" = yes ]; then
    printf '  %-22s (prop=%d val=%2d)  tree=ok  px=ok\n' "$id" "$prop" "$val"
    pass=$((pass + 1))
  else
    printf '  %-22s (prop=%d val=%2d)  tree=%s px=%s  DIVERGE\n' \
      "$id" "$prop" "$val" "$tree_ok" "$px_ok"
    [ "$tree_ok" = no ] && {
      echo "      proto-tree: $(cat "$proto" 2>/dev/null || echo MISSING)"
      echo "      ref-tree:   $(cat "$refr" 2>/dev/null || echo MISSING)"
    }
    fail=$((fail + 1))
  fi
}

val=0
for kw in "${ALIGN_KWS[@]}"; do
  run_row "align_${kw}" 0 "$val"
  val=$((val + 1))
done
for ((v = 0; v < TEXT_ALIGN_N; v++)); do
  run_row "textalign_${v}" 1 "$v"
done
for v in "${TEXT_DECOR_VALS[@]}"; do
  run_row "textdecor_${v}" 2 "$v"
done
for v in "${BORDER_SIDE_VALS[@]}"; do
  run_row "borderside_${v}" 3 "$v"
done
for i in "${!FLEX_KWS[@]}"; do
  run_row "flexflow_${FLEX_KWS[$i]}" 4 "${FLEX_VALS[$i]}"
done
for i in "${!BAR_MODE_KWS[@]}"; do
  run_row "barmode_${i}" 5 "$i"
done
for i in "${!ARC_MODE_KWS[@]}"; do
  run_row "arcmode_${i}" 6 "$i"
done
for i in "${!ROLLER_MODE_KWS[@]}"; do
  run_row "rollermode_${i}" 7 "$i"
done
for i in "${!SCALE_MODE_KWS[@]}"; do
  run_row "scalemode_${i}" 8 "${SCALE_MODE_VALS[$i]}"
done
for i in "${!WIDGET_TAGS[@]}"; do
  run_row "widget_${i}_${WIDGET_TAGS[$i]}" 9 "$i"
done
run_row "widget_19_lv_tabview" 9 19
run_row "widget_20_lv_chart" 9 20
for ((v = 0; v < COMBO_N; v++)); do
  run_row "combo_${v}" 10 "$v"
done
for i in "${!IMAGE_IDS[@]}"; do
  run_row "image_${IMAGE_IDS[$i]}" 13 "$i"
done

total=$((pass + fail))
echo "── coverage matrix: $pass matched, $fail diverged (of $total) ──"
[ "$fail" -eq 0 ]
