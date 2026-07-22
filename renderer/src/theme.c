/* Asgard child theme — see theme.h for the family contract.
 *
 * MECHANISM (the one hard rule): every override is a NEW style object added
 * by THIS theme's apply_cb, layered AFTER the stock parent's styles. The
 * stock theme's own style structs are NEVER touched — they feed chart,
 * tabview pages, and the demo-parity frozen constants.
 *
 * The VANILLA family restates stock values with stock's own formulas
 * (lv_theme_default.c: RADIUS_DEFAULT/PAD_DEF/PAD_SMALL/BORDER_WIDTH/
 * OUTLINE_WIDTH macros + the DARK_/LIGHT_COLOR_* defines + the
 * greater_res display-size derivation), so VANILLA-over-stock is a visual
 * no-op — held bit-exact by the family=1 vs family=2 render-hash gate.
 *
 * Deliberate EXCLUSIONS (protect the demo-parity frozen constants):
 * chart + table MAIN keep stock geometry; the obj arm replicates stock's
 * special-case guards (tabview bar/content/pages, win header/content,
 * calendar children) so panels only restyle where stock's card fallback
 * fired. The arc knob keeps stock's circle (a crisp radius reads glitchy
 * on an arc end — knob-spike verdict). */
#include "lvgl/src/themes/lv_theme_private.h"
#include "theme.h"
#include "theme_tokens.h"
/* ── Stock-formula mirrors (vanilla column) ────────────────────────────────
 * lv_theme_default.c is the source: dpx == LV_DPX_CALC, the size thresholds
 * are greater_res <=320 SMALL / <720 MEDIUM / else LARGE. */
typedef enum {
  SIZE_SMALL = 0,
  SIZE_MEDIUM = 1,
  SIZE_LARGE = 2,
} disp_size_t;
typedef struct {
  lv_style_t panel;       /* obj card fallback: radius/pad/bg/border/text */
  lv_style_t control_rad; /* dropdown/textarea/spinbox MAIN radius        */
  lv_style_t btn;         /* button MAIN: radius + pads                   */
  lv_style_t btn_shadow;  /* button/btnmatrix shadow (asgard: zeroed)     */
  lv_style_t item_rad;    /* buttonmatrix ITEMS radius                    */
  lv_style_t btnm_pads;   /* buttonmatrix MAIN pads + gaps                */
  lv_style_t roller_pad;  /* roller MAIN pad_hor                          */
  lv_style_t ta_pad;      /* textarea MAIN pad_ver                        */
  lv_style_t table_items; /* table ITEMS pad                              */
  lv_style_t cb_ind;      /* checkbox INDICATOR radius                    */
  lv_style_t led;         /* led shadow flattening                        */
  lv_style_t knob;        /* slider/switch KNOB radius (crisp)            */
  lv_style_t focus;       /* FOCUS_KEY outline                            */
  lv_style_t disabled;    /* DISABLED dim (asgard-only, empty in vanilla) */
  lv_style_t hover;       /* HOVERED lighten (asgard-only)                */
  lv_style_t pressed;     /* PRESSED darken for classes stock leaves
                            * unpressed — arc, roller (asgard-only)        */
  lv_style_t light_track; /* LIGHT-mode resting-track tone (arc/spinner
                            * ring, switch unchecked bg) — asgard-only;
                            * the stock light track sinks into the light
                            * surface                                      */
  lv_style_t cursor_off;  /* spinbox CURSOR hidden under DISABLED — a
                            * disabled control has no active edit cell
                            * (asgard-only)                                */
  lv_style_t trans;       /* zero-time transitions (asgard-only)          */
} asgard_styles_t;
typedef struct {
  lv_theme_t base;
  asgard_styles_t styles;
  asgard_theme_family_t family;
  bool dark;
  bool inited;
  int32_t dpi;
  disp_size_t size;
} asgard_theme_t;
static asgard_theme_t theme_inst;
/* Zero-time transition: state changes render INSTANTLY under the asgard
 * family (deterministic state cards + tactical immediacy). Props mirror the
 * stock theme's own transition set so every animated stock property is
 * covered. Vanilla does not attach this style — stock transitions stay. */
static const lv_style_prop_t zero_trans_props[] = {
    LV_STYLE_BG_OPA,
    LV_STYLE_BG_COLOR,
    LV_STYLE_TRANSFORM_WIDTH,
    LV_STYLE_TRANSFORM_HEIGHT,
    LV_STYLE_TRANSLATE_Y,
    LV_STYLE_TRANSLATE_X,
    LV_STYLE_TRANSFORM_ROTATION,
    LV_STYLE_TRANSFORM_SCALE_X,
    LV_STYLE_TRANSFORM_SCALE_Y,
    LV_STYLE_RECOLOR_OPA,
    LV_STYLE_RECOLOR,
    0,
};
static lv_style_transition_dsc_t zero_trans;
static int32_t dpx(int32_t dpi, int32_t n) {
  /* LV_DPX_CALC mirror (lv_display.h): 0 stays 0; else max(dpi*n/160, 1). */
  return n == 0 ? 0 : LV_MAX((dpi * n + 80) / 160, 1);
}
static disp_size_t size_of(lv_display_t *disp) {
  int32_t greater = LV_MAX(lv_display_get_horizontal_resolution(disp),
                           lv_display_get_vertical_resolution(disp));
  if (greater <= 320)
    return SIZE_SMALL;
  if (greater < 720)
    return SIZE_MEDIUM;
  return SIZE_LARGE;
}
/* Stock macro mirrors, parameterized on the captured dpi/size. */
static int32_t stock_radius_default(const asgard_theme_t *t) {
  return dpx(t->dpi, t->size == SIZE_LARGE ? 12 : 8);
}
static int32_t stock_btn_radius(const asgard_theme_t *t) {
  if (t->size == SIZE_LARGE)
    return dpx(t->dpi, 16);
  if (t->size == SIZE_MEDIUM)
    return dpx(t->dpi, 12);
  return dpx(t->dpi, 8);
}
static int32_t stock_pad_def(const asgard_theme_t *t) {
  if (t->size == SIZE_LARGE)
    return dpx(t->dpi, 24);
  if (t->size == SIZE_MEDIUM)
    return dpx(t->dpi, 20);
  return dpx(t->dpi, 16);
}
static int32_t stock_pad_small(const asgard_theme_t *t) {
  if (t->size == SIZE_LARGE)
    return dpx(t->dpi, 14);
  if (t->size == SIZE_MEDIUM)
    return dpx(t->dpi, 12);
  return dpx(t->dpi, 10);
}
static void style_reset(lv_style_t *style, bool inited) {
  if (inited)
    lv_style_reset(style);
  else
    lv_style_init(style);
}
/* Vanilla/asgard (and dark/light) value selects. The gate's clang-tidy bans a
 * bool as a ternary condition in C (readability-implicit-bool-conversion —
 * the condition converts to int in C's AST), so the selects route through
 * if/return pickers instead of `cond ? a : b` expressions. */
static int32_t pick_i32(bool cond, int32_t when_true, int32_t when_false) {
  if (cond)
    return when_true;
  return when_false;
}
static uint32_t pick_u32(bool cond, uint32_t when_true, uint32_t when_false) {
  if (cond)
    return when_true;
  return when_false;
}
static lv_color_t pick_color(bool cond, lv_color_t when_true,
                             lv_color_t when_false) {
  if (cond)
    return when_true;
  return when_false;
}
static void style_init(asgard_theme_t *t) {
  bool v = t->family == ASGARD_THEME_FAMILY_VANILLA;
  bool inited = t->inited;
  asgard_styles_t *s = &t->styles;
  /* panel — the obj-card fallback surface */
  style_reset(&s->panel, inited);
  lv_style_set_radius(&s->panel,
                      pick_i32(v, stock_radius_default(t), THEME_RADIUS_PANEL));
  lv_style_set_pad_all(&s->panel,
                       pick_i32(v, stock_pad_def(t), THEME_PAD_PANEL));
  lv_style_set_bg_color(
      &s->panel,
      pick_color(v,
                 pick_color(t->dark, lv_color_hex(0x282b30), lv_color_white()),
                 lv_color_hex(pick_u32(t->dark, THEME_SURFACE1_DARK,
                                       THEME_SURFACE1_LIGHT))));
  lv_style_set_border_color(
      &s->panel, pick_color(v,
                            pick_color(t->dark, lv_color_hex(0x2f3237),
                                       lv_palette_lighten(LV_PALETTE_GREY, 2)),
                            lv_color_hex(pick_u32(t->dark, THEME_EDGE0_DARK,
                                                  THEME_EDGE0_LIGHT))));
  lv_style_set_border_width(&s->panel,
                            pick_i32(v, dpx(t->dpi, 2), THEME_BORDER_W));
  lv_style_set_text_color(
      &s->panel,
      pick_color(
          v,
          pick_color(t->dark, lv_palette_lighten(LV_PALETTE_GREY, 5),
                     lv_palette_darken(LV_PALETTE_GREY, 4)),
          lv_color_hex(pick_u32(t->dark, THEME_FG0_DARK, THEME_FG0_LIGHT))));
  /* control radius — form-control tier */
  style_reset(&s->control_rad, inited);
  lv_style_set_radius(&s->control_rad, pick_i32(v, stock_radius_default(t),
                                                THEME_RADIUS_CONTROL));
  /* button geometry */
  style_reset(&s->btn, inited);
  lv_style_set_radius(&s->btn,
                      pick_i32(v, stock_btn_radius(t), THEME_RADIUS_BUTTON));
  lv_style_set_pad_hor(&s->btn,
                       pick_i32(v, stock_pad_def(t), THEME_PAD_CONTROL));
  lv_style_set_pad_ver(&s->btn,
                       pick_i32(v, stock_pad_small(t), THEME_PAD_CONTROL));
  if (!v) {
    /* Center button content. Stock LVGL leaves label placement to user code
     * (upstream examples hand-call lv_obj_center on the label), so an
     * unstyled button renders its label at the padded top-left; a global
     * DEFAULT theme is where the right default belongs. Flex row centered on
     * both axes covers label, icon, and icon+label children alike. Vanilla
     * sets NOTHING here (stock parity). */
    lv_style_set_layout(&s->btn, LV_LAYOUT_FLEX);
    lv_style_set_flex_flow(&s->btn, LV_FLEX_FLOW_ROW);
    lv_style_set_flex_main_place(&s->btn, LV_FLEX_ALIGN_CENTER);
    lv_style_set_flex_cross_place(&s->btn, LV_FLEX_ALIGN_CENTER);
    /* cross_place centers items within their TRACK; the single track itself
     * defaults to START — center it too or content rides the top edge. */
    lv_style_set_flex_track_place(&s->btn, LV_FLEX_ALIGN_CENTER);
  }
  /* button/btnmatrix shadow: stock draws one in LIGHT mode only; asgard
   * zeroes it in both (sharp tactical surface). Vanilla restates stock's
   * light-mode values and stays EMPTY in dark (stock sets nothing there —
   * an added shadow would break stock-parity). */
  style_reset(&s->btn_shadow, inited);
  if (v) {
    if (!t->dark) {
      lv_style_set_shadow_color(&s->btn_shadow,
                                lv_palette_main(LV_PALETTE_GREY));
      lv_style_set_shadow_width(&s->btn_shadow, dpx(t->dpi, 3));
      lv_style_set_shadow_opa(&s->btn_shadow, LV_OPA_50);
      lv_style_set_shadow_offset_y(&s->btn_shadow, dpx(t->dpi, dpx(t->dpi, 4)));
    }
  } else {
    lv_style_set_shadow_opa(&s->btn_shadow, THEME_DROP_OPA);
    lv_style_set_shadow_width(&s->btn_shadow, THEME_DROP_W);
    lv_style_set_shadow_spread(&s->btn_shadow, THEME_DROP_SPREAD);
  }
  /* buttonmatrix ITEMS radius */
  style_reset(&s->item_rad, inited);
  lv_style_set_radius(&s->item_rad,
                      pick_i32(v, stock_btn_radius(t), THEME_RADIUS_BUTTON));
  /* buttonmatrix MAIN pads + gaps */
  style_reset(&s->btnm_pads, inited);
  lv_style_set_pad_all(&s->btnm_pads,
                       pick_i32(v, stock_pad_def(t), THEME_PAD_CONTROL));
  lv_style_set_pad_row(&s->btnm_pads,
                       pick_i32(v, stock_pad_small(t), THEME_PAD_GRID));
  lv_style_set_pad_column(&s->btnm_pads,
                          pick_i32(v, stock_pad_small(t), THEME_PAD_GRID));
  /* roller horizontal breathing room */
  style_reset(&s->roller_pad, inited);
  lv_style_set_pad_left(&s->roller_pad,
                        pick_i32(v, stock_pad_def(t), THEME_PAD_PANEL));
  lv_style_set_pad_right(&s->roller_pad,
                         pick_i32(v, stock_pad_def(t), THEME_PAD_PANEL));
  /* textarea vertical padding */
  style_reset(&s->ta_pad, inited);
  lv_style_set_pad_top(&s->ta_pad,
                       pick_i32(v, stock_pad_small(t), THEME_PAD_CONTROL));
  lv_style_set_pad_bottom(&s->ta_pad,
                          pick_i32(v, stock_pad_small(t), THEME_PAD_CONTROL));
  /* table cell inset */
  style_reset(&s->table_items, inited);
  lv_style_set_pad_all(&s->table_items,
                       pick_i32(v, stock_pad_def(t), THEME_PAD_PANEL));
  /* checkbox indicator */
  style_reset(&s->cb_ind, inited);
  lv_style_set_radius(&s->cb_ind, pick_i32(v, stock_radius_default(t) / 2,
                                           THEME_RADIUS_CONTROL));
  /* led shadow flattening */
  style_reset(&s->led, inited);
  lv_style_set_shadow_width(&s->led,
                            pick_i32(v, dpx(t->dpi, 15), THEME_DROP_W));
  lv_style_set_shadow_spread(&s->led,
                             pick_i32(v, dpx(t->dpi, 5), THEME_DROP_SPREAD));
  /* crisp knob (slider + switch; arc excluded) */
  style_reset(&s->knob, inited);
  lv_style_set_radius(&s->knob,
                      pick_i32(v, LV_RADIUS_CIRCLE, THEME_RADIUS_CONTROL));
  /* focus ring */
  style_reset(&s->focus, inited);
  if (v) {
    lv_style_set_outline_color(&s->focus, t->base.color_primary);
    lv_style_set_outline_width(&s->focus, dpx(t->dpi, 3));
    lv_style_set_outline_pad(&s->focus, dpx(t->dpi, 3));
    lv_style_set_outline_opa(&s->focus, LV_OPA_50);
  } else {
    lv_style_set_outline_color(
        &s->focus, lv_color_hex(pick_u32(t->dark, THEME_FOCUSED_EDGE_DARK,
                                         THEME_FOCUSED_EDGE_LIGHT)));
    lv_style_set_outline_width(&s->focus, THEME_OUTLINE_W);
    lv_style_set_outline_pad(&s->focus, THEME_OUTLINE_W);
    lv_style_set_outline_opa(&s->focus, LV_OPA_COVER);
  }
  /* disabled dim — asgard-only NEW state coverage (stock has none for the
   * value widgets); vanilla stays empty so stock-parity holds. */
  style_reset(&s->disabled, inited);
  if (!v)
    lv_style_set_opa(&s->disabled, THEME_DISABLED_OPA);
  /* hover lighten — asgard-only (stock styles HOVERED nowhere); the white
   * recolor mirrors stock's PRESSED darken mechanism in the opposite
   * direction, so hover < pressed reads as approach < commit. */
  style_reset(&s->hover, inited);
  if (!v) {
    lv_style_set_recolor(&s->hover, lv_color_white());
    lv_style_set_recolor_opa(&s->hover, 40);
  }
  /* pressed darken — asgard-only, for the classes whose stock arms never
   * style PRESSED (arc, roller) although the widget-states manifest commits
   * it. Black recolor at a stronger opa than hover's 40 white keeps the
   * approach < commit ordering. */
  style_reset(&s->pressed, inited);
  if (!v) {
    lv_style_set_recolor(&s->pressed, lv_color_black());
    lv_style_set_recolor_opa(&s->pressed, 64);
  }
  /* light resting track — asgard-only, LIGHT mode only: arc/spinner rings
   * and the unchecked switch track use the edge tone so the resting state
   * stays visible on the light surface (empty in dark — the dark surface
   * already contrasts). */
  style_reset(&s->light_track, inited);
  if (!v && !t->dark) {
    lv_style_set_arc_color(&s->light_track, lv_color_hex(THEME_EDGE0_LIGHT));
    lv_style_set_bg_color(&s->light_track, lv_color_hex(THEME_EDGE0_LIGHT));
  }
  /* disabled spinbox cursor — asgard-only: the stock cursor keeps its
   * highlight under DISABLED, where the dimmed digit sinks into it; a
   * disabled control has no active edit cell, so the highlight goes. */
  style_reset(&s->cursor_off, inited);
  if (!v)
    lv_style_set_bg_opa(&s->cursor_off, LV_OPA_TRANSP);
  /* zero-time transitions — asgard-only */
  style_reset(&s->trans, inited);
  if (!v) {
    lv_style_transition_dsc_init(&zero_trans, zero_trans_props,
                                 lv_anim_path_linear, 0, 0, NULL);
    lv_style_set_transition(&s->trans, &zero_trans);
  }
}
/* The interactive add-ons every pointer-driven class shares under the
 * asgard family: hover lighten + instant transitions. */
static void add_interactive(asgard_theme_t *t, lv_obj_t *obj) {
  if (t->family == ASGARD_THEME_FAMILY_VANILLA)
    return;
  lv_obj_add_style(obj, &t->styles.hover, LV_STATE_HOVERED);
  lv_obj_add_style(obj, &t->styles.trans, 0);
}
static void theme_apply(lv_theme_t *th, lv_obj_t *obj) {
  asgard_theme_t *t = (asgard_theme_t *)th;
  if (t->family == ASGARD_THEME_FAMILY_STOCK)
    return; /* family 2: pure parent — the child adds nothing */
  lv_obj_t *parent = lv_obj_get_parent(obj);
  if (parent == NULL)
    return; /* screens stay stock */
  if (lv_obj_check_type(obj, &lv_obj_class)) {
/* Mirror the stock obj arm's special-case guards exactly — these
     * containers deliberately do NOT get the card fallback, and re-styling
     * them here would shift tabview/win geometry the demo-parity capstone
     * has frozen. */
#if LV_USE_TABVIEW
    if (lv_obj_check_type(parent, &lv_tabview_class))
      return; /* tab bar (child 0) + content (child 1) */
    if (lv_obj_get_parent(parent) != NULL &&
        lv_obj_check_type(lv_obj_get_parent(parent), &lv_tabview_class))
      return; /* tab pages */
#endif
#if LV_USE_WIN
    if (lv_obj_check_type(parent, &lv_win_class))
      return; /* header + content */
#endif
#if LV_USE_CALENDAR
    if (lv_obj_check_type(parent, &lv_calendar_class))
      return;
#endif
    lv_obj_add_style(obj, &t->styles.panel, 0);
    return;
  }
#if LV_USE_BUTTON
  if (lv_obj_check_type(obj, &lv_button_class)) {
#if LV_USE_TABVIEW
    /* Tab-bar buttons keep stock styling entirely (frozen capstone). */
    lv_obj_t *tv = lv_obj_get_parent(parent);
    if (tv != NULL && lv_obj_get_child(tv, 0) == parent &&
        lv_obj_check_type(tv, &lv_tabview_class))
      return;
#endif
    lv_obj_add_style(obj, &t->styles.btn, 0);
    lv_obj_add_style(obj, &t->styles.btn_shadow, 0);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SLIDER
  if (lv_obj_check_type(obj, &lv_slider_class)) {
    lv_obj_add_style(obj, &t->styles.knob, LV_PART_KNOB);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SWITCH
  if (lv_obj_check_type(obj, &lv_switch_class)) {
    lv_obj_add_style(obj, &t->styles.knob, LV_PART_KNOB);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.light_track, LV_PART_MAIN);
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_ARC
  if (lv_obj_check_type(obj, &lv_arc_class)) {
    /* knob stays stock (circle is intrinsic to an arc end) */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.pressed, LV_STATE_PRESSED);
      lv_obj_add_style(obj, &t->styles.light_track, LV_PART_MAIN);
    }
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SPINNER
  if (lv_obj_check_type(obj, &lv_spinner_class)) {
    /* spinner is its own class (exact-type checks miss the arc arm); the
     * resting ring needs the same light-mode tone. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.light_track, LV_PART_MAIN);
    return;
  }
#endif
#if LV_USE_BAR
  if (lv_obj_check_type(obj, &lv_bar_class)) {
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
    return;
  }
#endif
#if LV_USE_CHECKBOX
  if (lv_obj_check_type(obj, &lv_checkbox_class)) {
    lv_obj_add_style(obj, &t->styles.cb_ind, LV_PART_INDICATOR);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_DROPDOWN
  if (lv_obj_check_type(obj, &lv_dropdown_class)) {
    lv_obj_add_style(obj, &t->styles.control_rad, 0);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_ROLLER
  if (lv_obj_check_type(obj, &lv_roller_class)) {
    lv_obj_add_style(obj, &t->styles.roller_pad, 0);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.pressed, LV_STATE_PRESSED);
    }
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_TEXTAREA
  if (lv_obj_check_type(obj, &lv_textarea_class)) {
    lv_obj_add_style(obj, &t->styles.control_rad, 0);
    lv_obj_add_style(obj, &t->styles.ta_pad, 0);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SPINBOX
  if (lv_obj_check_type(obj, &lv_spinbox_class)) {
    lv_obj_add_style(obj, &t->styles.control_rad, 0);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.cursor_off,
                       LV_PART_CURSOR | LV_STATE_DISABLED);
    }
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_BUTTONMATRIX
  if (lv_obj_check_type(obj, &lv_buttonmatrix_class)) {
    lv_obj_add_style(obj, &t->styles.btnm_pads, 0);
    lv_obj_add_style(obj, &t->styles.item_rad, LV_PART_ITEMS);
    lv_obj_add_style(obj, &t->styles.btn_shadow, LV_PART_ITEMS);
    return;
  }
#endif
#if LV_USE_TABLE
  if (lv_obj_check_type(obj, &lv_table_class)) {
    /* MAIN stays stock (already zeroed there); only the cell inset. */
    lv_obj_add_style(obj, &t->styles.table_items, LV_PART_ITEMS);
    return;
  }
#endif
#if LV_USE_LED
  if (lv_obj_check_type(obj, &lv_led_class)) {
    lv_obj_add_style(obj, &t->styles.led, 0);
    return;
  }
#endif
  /* Everything else (label, image, scale, spinner, line, chart, tabview,
   * dropdown list, ...) falls through to stock untouched. */
}
lv_theme_t *asgard_theme_init(lv_display_t *disp, bool dark,
                              asgard_theme_family_t family,
                              lv_theme_t *parent) {
  asgard_theme_t *t = &theme_inst;
  t->base.disp = disp;
  t->base.apply_cb = theme_apply;
  /* Mirror the parent's base fields: the lv_theme_get_* getters read the
   * display theme's OWN fields (no parent walk), so these must be real. */
  t->base.font_small = parent->font_small;
  t->base.font_normal = parent->font_normal;
  t->base.font_large = parent->font_large;
  t->base.color_primary = parent->color_primary;
  t->base.color_secondary = parent->color_secondary;
  t->base.flags = parent->flags;
  lv_theme_set_parent(&t->base, parent);
  t->dark = dark;
  t->family = family;
  t->dpi = lv_display_get_dpi(disp);
  t->size = size_of(disp);
  style_init(t);
  t->inited = true;
  if (disp == NULL || lv_display_get_theme(disp) == (lv_theme_t *)t)
    lv_obj_report_style_change(NULL);
  return (lv_theme_t *)t;
}
