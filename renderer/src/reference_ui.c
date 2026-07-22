/**
 * Reference UI path — the INDEPENDENT oracle for the visual differential.
 *
 * This file implements the `renderer.h` interface with LITERAL `lv_*` calls,
 * built into `reference.wasm` INSTEAD of `renderer.c`. It deliberately shares
 * NO codegen with the proto path (`emit_proto.clj` / `renderer.c`): the
 * reference is compiler-checked C against LVGL's real API, so when the harness
 * tree-diffs the two paths a divergence means OUR proto representation is
 * wrong, not that both sides drifted together (plan R5 — independence).
 *
 * The harness drives the coverage matrix by passing a 2-byte selector through
 * `controls_load_ui(ptr, len)`:
 *   data[0]  the PROPERTY case (ref_prop below)
 *   data[1]  the LVGL enum value under test
 * Each case builds a small fixed-geometry scene whose ONLY variable is the
 * selected value; the proto path builds the equivalent scene from EDN, and the
 * two must tree- and pixel-match.
 */
/* The vendored demo (lvgl/demos — 3rd-party, opaque to gates): declare
 * the one entry point instead of including its header chain, which
 * carries an upstream include cycle clang-tidy would flag. The demo's
 * tabview handle (lv_demo_widgets.c global) is exposed so the selector
 * can pick the rendered tab. */
void lv_demo_widgets(void);
#include "lvgl.h"
#include "renderer.h"
#include <stdint.h>
/* The demo's tabview handle (lv_demo_widgets.c global) — exposed so the
 * selector's second byte can pick the rendered tab. */
extern lv_obj_t *tv;
/* Frozen lv_rand seed for the demo-parity differential: the demo feeds
 * its three charts from lv_rand, so the reference render seeds the PRNG
 * to a fixed value and the EDN recreation carries the resulting series
 * vectors verbatim (gap-matrix Dynamism row 1). The xorshift32 stream
 * from this seed is the contract; edn/screens/demo_widgets.edn records
 * the derived vectors. */
#define DEMO_RAND_SEED 0x5EED0001u
/* Fixed reference geometry (px). The matched proto fixtures use the same
 * numbers, so the only variable across a matrix case is the value under
 * test. */
#define REF_BOX_W 100
#define REF_BOX_H 50
#define REF_LABEL_W 80

enum ref_prop {
  REF_PROP_ALIGN = 0,
  /* lv_obj box aligned data[1] on the parent */
  REF_PROP_TEXT_ALIGN = 1,
  /* label inside a centered box; text align
                               varies. Geometry is value-invariant here, so
                               the FRAMEBUFFER is the primary oracle (the
                               tree backstops type/coords). */
  REF_PROP_TEXT_DECOR = 2,
  /* label; text decoration bitmask varies */
  REF_PROP_BORDER_SIDE = 3,
  /* box; border-side bitmask varies */
  REF_PROP_FLEX_FLOW = 4,
  /* box with 3 children; the flex flow varies —
                               data[1] is the LVGL lv_flex_flow_t bitmask
                               value. The proto path routes this through the
                               factory-generated flex_flow_lut, so this family
                               render-verifies the LUT itself. */
  REF_PROP_BAR_MODE = 5,
  /* lv_bar over a negative-spanning range; the mode
                               varies (NORMAL draws from min, SYMMETRICAL from
                               zero, RANGE from start_value) — the first
                               widget_props-oneof family. */
  REF_PROP_ARC_MODE = 6,
  /* lv_arc; mode varies (NORMAL/SYMMETRICAL/
                               REVERSE) */
  REF_PROP_ROLLER_MODE = 7,
  /* lv_roller; mode rides set_options
                               (NORMAL/INFINITE) */
  REF_PROP_SCALE_MODE = 8,
  /* lv_scale; sparse-bitmask mode varies */
  REF_PROP_WIDGET = 9,
  /* widget singles: data[1] = the WidgetType wire
                               number; the widget (text set for label/
                               checkbox/textarea, as renderer.c does) inside
                               the standard box. buttonmatrix/table carry no
                               props here, so both paths keep LVGL's own
                               defaults (default map / 1x1 grid). */
  REF_PROP_COMBO = 10,
  /* widget combos: data[1] selects a scene —
                               0 flex row of mixed widgets, 1 nested boxes,
                               2 styled button + label, 3 the all-widgets
                               kitchen-sink mega scene. */
  REF_PROP_PRESSED_STYLE = 11,
  /* button with a red PRESSED-selector style,
                                  state FORCED via lv_obj_add_state — pins
                                  that a state-selector style applies
                                  without any indev involvement. */
  REF_PROP_DEMO_WIDGETS = 12,
  /* run the REAL lv_demo_widgets (vendored
                                  v9.5.0) — the demo-parity oracle. The
                                  PRNG is seeded (DEMO_RAND_SEED) so the
                                  chart data is frozen; data[1] selects
                                  the active tab (0..2). */
  REF_PROP_IMAGE = 13, /* image-pipeline parity (D4a): data[1] picks a
                                  src from image_srcs[] — compiled demo C
                                  arrays (the proto path loads the extracted
                                  P:images/demo PNG twin: runtime lodepng
                                  decode == compiled array) or a P: file path
                                  loaded by BOTH wasm builds (PNG lodepng /
                                  SVG ThorVG decoder parity). */
};

/* The vendored demo's compiled-in image descriptors (lvgl/demos/widgets/
 * assets/img_*.c — linked into reference.wasm only). The P:images/demo/
 * PNG twins are extracted from these arrays by pocs/08-image-extract. */
extern const lv_image_dsc_t img_demo_widgets_avatar;
extern const lv_image_dsc_t img_lvgl_logo;
extern const lv_image_dsc_t img_clothes;
extern const lv_image_dsc_t img_demo_widgets_needle;
/* REF_PROP_IMAGE src table: indices 0..3 are the compiled C arrays
 * (decode-parity rows); 4..7 are P: file paths rendered from the SAME
 * file by both wasm builds (decoder-parity rows, PNG and SVG). */
static const void *const image_srcs[] = {
    &img_demo_widgets_avatar,
    /* 0 vs P:images/demo/avatar.png */
    &img_lvgl_logo,
    /* 1 vs P:images/demo/lvgl_logo.png */
    &img_clothes,
    /* 2 vs P:images/demo/clothes.png */
    &img_demo_widgets_needle,
    /* 3 vs P:images/demo/needle.png */
    "P:images/demo/avatar.png",
    /* 4 file PNG, both sides */
    "P:images/test_square.png",
    /* 5 file PNG, both sides */
    "P:icons/test_square.svg",
    /* 6 file SVG twin of 5, both sides */
    "P:icons/test_shapes.svg", /* 7 file SVG multi-shape, both sides */
};

/* One widget of WidgetType wire number `kind` (renderer.c's creation
 * switch, mirrored literally), with text applied exactly where the
 * renderer applies it. Returns NULL for kinds this family does not build
 * (HOST_PROXY, whose assembly is proxy-specific, and any out-of-range
 * wire number). */
static lv_obj_t *make_widget(lv_obj_t *parent, uint8_t kind) {
  lv_obj_t *w = NULL;
  switch (kind) {
  case 0:
    w = lv_obj_create(parent);
    break;
  case 1:
    w = lv_button_create(parent);
    break;
  case 2:
    w = lv_label_create(parent);
    break;
  case 3:
    w = lv_slider_create(parent);
    break;
  case 4:
    w = lv_image_create(parent);
    break;
  case 5:
    w = lv_arc_create(parent);
    break;
  case 6:
    w = lv_bar_create(parent);
    break;
  case 7:
    w = lv_switch_create(parent);
    break;
  case 8:
    w = lv_checkbox_create(parent);
    break;
  case 9:
    w = lv_dropdown_create(parent);
    break;
  case 10:
    w = lv_roller_create(parent);
    break;
  case 11:
    w = lv_textarea_create(parent);
    break;
  case 12:
    w = lv_spinbox_create(parent);
    break;
  case 13:
    w = lv_spinner_create(parent);
    break;
  case 14:
    w = lv_led_create(parent);
    break;
  case 15:
    w = lv_line_create(parent);
    break;
  case 16:
    w = lv_scale_create(parent);
    break;
  case 17: /* WIDGET_BUTTONMATRIX */
    w = lv_buttonmatrix_create(parent);
    break;
  case 18: /* WIDGET_TABLE */
    w = lv_table_create(parent);
    break;
  case 19: /* WIDGET_TABVIEW */
    w = lv_tabview_create(parent);
    break;
  case 20: /* WIDGET_CHART */
    w = lv_chart_create(parent);
    break;
  default:
    return NULL;
  }
  if (w == NULL)
    return NULL;
  if (kind == 2) {
    lv_label_set_text(w, "Ag");
  } else if (kind == 8) {
    lv_checkbox_set_text(w, "Ag");
  } else if (kind == 11) {
    lv_textarea_set_text(w, "Ag");
  } else if (kind == 19) {
    /* Mirror of the proto fixture: bar size + three named tabs, one
       * plain obj of page content each. Tab 1 is activated by the
       * caller AFTER sizing — activation against non-final geometry
       * lets the scroll-snap machinery re-snap to tab 0. */
    lv_tabview_set_tab_bar_size(w, 20);
    static const char *const tab_names[] = {"A", "B", "C"};
    for (unsigned i = 0; i < 3u; i++) {
      lv_obj_t *page = lv_tabview_add_tab(w, tab_names[i]);
      if (page == NULL || lv_obj_create(page) == NULL)
        return NULL;
    }
  } else if (kind == 20) {
    /* Mirror of the proto fixture (widget_20_lv_chart.edn): LINE type,
       * 8 points, explicit div lines — 0 hdiv pins the explicit-zero
       * path, 12 vdiv — and two fixed-color PRIMARY_Y series written by
       * index (the same setter ORDER as renderer.c's apply_chart_props). */
    lv_chart_set_type(w, LV_CHART_TYPE_LINE);
    lv_chart_set_point_count(w, 8);
    lv_chart_set_div_line_count(w, 0, 12);
    static const int32_t ser_a_vals[8] = {10, 25, 18, 40, 32, 48, 27, 35};
    static const int32_t ser_b_vals[8] = {45, 12, 30, 8, 38, 22, 41, 15};
    lv_chart_series_t *ser_a = lv_chart_add_series(
        w, lv_color_make(239, 68, 68), LV_CHART_AXIS_PRIMARY_Y);
    if (ser_a == NULL)
      return NULL;
    for (uint32_t i = 0; i < 8u; i++) {
      lv_chart_set_series_value_by_id(w, ser_a, i, ser_a_vals[i]);
    }
    lv_chart_series_t *ser_b = lv_chart_add_series(
        w, lv_color_make(59, 130, 246), LV_CHART_AXIS_PRIMARY_Y);
    if (ser_b == NULL)
      return NULL;
    for (uint32_t i = 0; i < 8u; i++) {
      lv_chart_set_series_value_by_id(w, ser_b, i, ser_b_vals[i]);
    }
    lv_chart_refresh(w);
  }
  return w;
}

/* Size a widget the way the PROTO path can: the renderer applies w/h via
 * lv_obj_add_style (style-group), which a widget's own LOCAL style outranks
 * — so a spinbox (one-line textarea, content-driven local height) cannot be
 * given a height through the proto. Mirror that: width only for spinbox. */
static void size_widget(lv_obj_t *w, uint8_t kind, int32_t width,
                        int32_t height) {
  if (kind == 12)
    lv_obj_set_width(w, width);
  else
    lv_obj_set_size(w, width, height);
}

/* Centered fixed-size box — the shared scaffold of every case. */
static lv_obj_t *make_box(lv_obj_t *parent) {
  lv_obj_t *box = lv_obj_create(parent);
  if (box == NULL)
    return NULL;
  lv_obj_set_size(box, REF_BOX_W, REF_BOX_H);
  lv_obj_align(box, LV_ALIGN_CENTER, 0, 0);
  return box;
}

/* Label child of `box` — the shared scaffold of the text-prop cases. */
static lv_obj_t *make_label(lv_obj_t *box) {
  lv_obj_t *label = lv_label_create(box);
  if (label == NULL)
    return NULL;
  lv_label_set_text(label, "Ag");
  lv_obj_set_width(label, REF_LABEL_W);
  return label;
}

int build_ui_from_proto_raw(const uint8_t *data, uint32_t len,
                            lv_obj_t *parent) {
  /* The selector protocol is exactly two bytes — fail loud on anything
   * else rather than render a half-selected scene. */
  if (data == NULL || len < 2u)
    return -1;
  uint8_t value = data[1];
  switch (data[0]) {
  case REF_PROP_ALIGN: {
    lv_obj_t *box = make_box(parent);
    if (box == NULL)
      return -1;
    lv_obj_align(box, (lv_align_t)value, 0, 0);
    return 0;
  }
  case REF_PROP_TEXT_ALIGN: {
    lv_obj_t *box = make_box(parent);
    lv_obj_t *label = box ? make_label(box) : NULL;
    if (label == NULL)
      return -1;
    lv_obj_set_style_text_align(label, (lv_text_align_t)value, 0);
    return 0;
  }
  case REF_PROP_TEXT_DECOR: {
    lv_obj_t *box = make_box(parent);
    lv_obj_t *label = box ? make_label(box) : NULL;
    if (label == NULL)
      return -1;
    lv_obj_set_style_text_decor(label, (lv_text_decor_t)value, 0);
    return 0;
  }
  case REF_PROP_DEMO_WIDGETS: {
    /* data[1] = the tab to render (0 Profile / 1 Analytics / 2 Shop)
         * — the demo boots on tab 0; per-tab parity rows need the others
         * visible. Activation runs ANIM_OFF after the demo built, the
         * same deferred-activation shape as the proto path. */
    if (value > 2u)
      return -1;
    lv_rand_set_seed(DEMO_RAND_SEED);
    lv_demo_widgets();
    if (value != 0u)
      lv_tabview_set_active(tv, value, LV_ANIM_OFF);
    return 0;
  }
  case REF_PROP_IMAGE: {
    if (value >= sizeof(image_srcs) / sizeof(image_srcs[0]))
      return -1;
    lv_obj_t *box = make_box(parent);
    if (box == NULL)
      return -1;
    /* Room for the largest source (avatar 160x154) inside the box
         * padding; the image keeps its content-driven default size. */
    lv_obj_set_size(box, 220, 220);
    lv_obj_t *img = lv_image_create(box);
    if (img == NULL)
      return -1;
    lv_image_set_src(img, image_srcs[value]);
    return 0;
  }
  case REF_PROP_PRESSED_STYLE: {
    lv_obj_t *btn = lv_button_create(parent);
    if (btn == NULL)
      return -1;
    lv_obj_set_size(btn, 160, 64);
    static lv_style_t pressed_red;
    lv_style_init(&pressed_red);
    lv_style_set_bg_color(&pressed_red, lv_color_make(239, 68, 68));
    lv_obj_add_style(btn, &pressed_red, LV_STATE_PRESSED);
    if (value != 0)
      lv_obj_add_state(btn, LV_STATE_PRESSED);
    return 0;
  }
  case REF_PROP_BORDER_SIDE: {
    lv_obj_t *box = make_box(parent);
    if (box == NULL)
      return -1;
    lv_obj_set_style_border_side(box, (lv_border_side_t)value, 0);
    return 0;
  }
  case REF_PROP_FLEX_FLOW: {
    lv_obj_t *box = make_box(parent);
    if (box == NULL)
      return -1;
    for (int i = 0; i < 3; i++) {
      lv_obj_t *child = lv_obj_create(box);
      if (child == NULL)
        return -1;
      lv_obj_set_size(child, 20, 10);
    }
    lv_obj_set_flex_flow(box, (lv_flex_flow_t)value);
    return 0;
  }
  case REF_PROP_WIDGET: {
    lv_obj_t *box = make_box(parent);
    if (box == NULL)
      return -1;
    if (value == 19 || value == 20) {
      /* Tabview fills its parent (class PCT(100) size defaults are
             * LOCAL styles — un-overridable from the proto's style
             * groups, so neither path sizes it); the chart needs room for
             * its div lines + series — give both a roomier box. */
      lv_obj_set_size(box, 300, 200);
    }
    lv_obj_t *w = make_widget(box, value);
    if (w == NULL)
      return -1;
    if (value == 19) {
      /* Activate AFTER the box is sized (mirrors the proto path's
             * deferred activation — see renderer.c pending_tabview). */
      lv_tabview_set_active(w, 1, LV_ANIM_OFF);
    } else if (value == 20) {
      lv_obj_set_size(w, 280, 160);
    } else {
      size_widget(w, value, 80, 40);
    }
    return 0;
  }
  case REF_PROP_COMBO:
    switch (value) {
    case 0: /* flex row of mixed widgets */ {
      lv_obj_t *box = make_box(parent);
      if (box == NULL)
        return -1;
      lv_obj_set_size(box, 300, 60);
      static const uint8_t kinds[] = {1, 3, 6}; /* button slider bar */
      for (unsigned i = 0; i < sizeof(kinds); i++) {
        lv_obj_t *w = make_widget(box, kinds[i]);
        if (w == NULL)
          return -1;
        lv_obj_set_size(w, 60, 20);
      }
      lv_obj_set_flex_flow(box, LV_FLEX_FLOW_ROW);
      return 0;
    }
    case 1: /* nested boxes, centered at each level */ {
      lv_obj_t *outer = make_box(parent);
      if (outer == NULL)
        return -1;
      lv_obj_set_size(outer, 200, 120);
      lv_obj_t *mid = lv_obj_create(outer);
      if (mid == NULL)
        return -1;
      lv_obj_set_size(mid, 140, 80);
      lv_obj_align(mid, LV_ALIGN_CENTER, 0, 0);
      lv_obj_t *inner = lv_obj_create(mid);
      if (inner == NULL)
        return -1;
      lv_obj_set_size(inner, 80, 40);
      lv_obj_align(inner, LV_ALIGN_CENTER, 0, 0);
      return 0;
    }
    case 2: /* styled button with a label child */ {
      lv_obj_t *btn = lv_button_create(parent);
      if (btn == NULL)
        return -1;
      lv_obj_set_size(btn, 120, 50);
      lv_obj_align(btn, LV_ALIGN_CENTER, 0, 0);
      lv_obj_set_style_radius(btn, 10, 0);
      lv_obj_set_style_border_width(btn, 3, 0);
      lv_obj_set_style_pad_all(btn, 8, 0);
      lv_obj_t *label = lv_label_create(btn);
      if (label == NULL)
        return -1;
      lv_label_set_text(label, "OK");
      return 0;
    }
    case 3: /* the all-widgets kitchen-sink mega scene: a flex column
                   of three flex rows carrying every buildable kind */
    {
      lv_obj_t *col = lv_obj_create(parent);
      if (col == NULL)
        return -1;
      lv_obj_set_size(col, 700, 400);
      lv_obj_align(col, LV_ALIGN_CENTER, 0, 0);
      lv_obj_set_flex_flow(col, LV_FLEX_FLOW_COLUMN);
      static const uint8_t rows[3][6] = {
          {0, 1, 2, 3, 4, 5},
          /* obj button label slider image arc */
          {6, 7, 8, 9, 10, 11},
          /* bar switch checkbox dropdown roller
                                         textarea */
          {12, 13, 14, 15, 16, 16}, /* spinbox spinner led line scale
                                             (last repeated: rows are even) */
      };
      for (unsigned r = 0; r < 3; r++) {
        lv_obj_t *row = lv_obj_create(col);
        if (row == NULL)
          return -1;
        lv_obj_set_size(row, 660, 110);
        lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
        for (unsigned i = 0; i < 6; i++) {
          lv_obj_t *w = make_widget(row, rows[r][i]);
          if (w == NULL)
            return -1;
          size_widget(w, rows[r][i], 90, 70);
        }
      }
      return 0;
    }
    default:
      return -1;
    }
  case REF_PROP_BAR_MODE: {
    lv_obj_t *bar = lv_bar_create(parent);
    if (bar == NULL)
      return -1;
    lv_obj_set_size(bar, REF_LABEL_W, 10);
    lv_obj_align(bar, LV_ALIGN_CENTER, 0, 0);
    /* Setter ORDER mirrors renderer.c's bar_props application —
         * range, value, mode, start_value — so clamping behaves alike. */
    lv_bar_set_range(bar, -50, 50);
    lv_bar_set_value(bar, 30, LV_ANIM_OFF);
    lv_bar_set_mode(bar, (lv_bar_mode_t)value);
    lv_bar_set_start_value(bar, 10, LV_ANIM_OFF);
    return 0;
  }
  case REF_PROP_ARC_MODE: {
    lv_obj_t *arc = lv_arc_create(parent);
    if (arc == NULL)
      return -1;
    lv_obj_set_size(arc, 60, 60);
    lv_obj_align(arc, LV_ALIGN_CENTER, 0, 0);
    /* Setter ORDER mirrors renderer.c's arc_props application. */
    if (value != 0)
      lv_arc_set_mode(arc, (lv_arc_mode_t)value);
    lv_arc_set_range(arc, 0, 100);
    lv_arc_set_value(arc, 60);
    lv_arc_set_angles(arc, 135, 45);
    lv_arc_set_bg_angles(arc, 135, 45);
    return 0;
  }
  case REF_PROP_ROLLER_MODE: {
    lv_obj_t *roller = lv_roller_create(parent);
    if (roller == NULL)
      return -1;
    lv_obj_set_width(roller, REF_LABEL_W);
    lv_obj_align(roller, LV_ALIGN_CENTER, 0, 0);
    /* Mode rides set_options, as in renderer.c's roller_props. */
    lv_roller_set_options(roller, "a\nb\nc", (lv_roller_mode_t)value);
    lv_roller_set_selected(roller, 1, LV_ANIM_OFF);
    lv_roller_set_visible_row_count(roller, 2);
    return 0;
  }
  case REF_PROP_SCALE_MODE: {
    lv_obj_t *scale = lv_scale_create(parent);
    if (scale == NULL)
      return -1;
    lv_obj_set_size(scale, 60, 60);
    lv_obj_align(scale, LV_ALIGN_CENTER, 0, 0);
    /* Setter ORDER mirrors renderer.c's scale_props application. */
    if (value != 0)
      lv_scale_set_mode(scale, (lv_scale_mode_t)value);
    lv_scale_set_range(scale, 0, 100);
    lv_scale_set_total_tick_count(scale, 5);
    lv_scale_set_major_tick_every(scale, 2);
    /* renderer.c applies label_show UNCONDITIONALLY (the proto default
         * would otherwise hide labels); mirror with an explicit true. */
    lv_scale_set_label_show(scale, true);
    return 0;
  }
  default:
    return -1;
  }
}

int update_state_from_proto(const uint8_t *data, uint32_t len) {
  /* The reference path has no reactive state — every case is static. */
  (void)data;
  (void)len;
  return 0;
}

void renderer_cleanup(void) {
  /* No subjects or style pool in the reference path — nothing to free. */
}

void proxy_report_sweep(void) {
  /* The reference path builds no host proxies — nothing to report. */
}

int32_t cmd_spec_decode_probe(const uint8_t *data, uint32_t len) {
  /* The reference path decodes no proto — a crafted CmdSpec `.pb` never reaches
   * a decode boundary here. Report the nanopb-reject sentinel; the controls
   * module owns the real guard (the harness runs the probe against
   * controls.wasm, never the reference oracle). */
  (void)data;
  (void)len;
  return -2;
}

int apply_patch_from_proto_raw(const uint8_t *data, uint32_t len,
                               uint32_t expected_base_hash,
                               uint32_t *out_target_hash) {
  /* The reference path renders compiled scenes — there is no proto tree
   * to morph; a patch against it is always a decode-level error. */
  (void)data;
  (void)len;
  (void)expected_base_hash;
  if (out_target_hash != NULL) {
    *out_target_hash = 0;
  }
  return PATCH_ERR_DECODE;
}
