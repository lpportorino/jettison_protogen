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
#include "theme.h"
#include "lvgl/src/themes/lv_theme_private.h"
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
  lv_style_t panel;          /* obj card fallback: radius/pad/bg/border/text */
  lv_style_t control_rad;    /* control-tier MAIN radius + edge border
                           * (dropdown/list/roller/spinbox/textarea/
                           * buttonmatrix)                                */
  lv_style_t btn;            /* button MAIN: radius + pads                   */
  lv_style_t btn_shadow;     /* button/btnmatrix shadow (asgard: zeroed)     */
  lv_style_t item_rad;       /* buttonmatrix ITEMS radius                    */
  lv_style_t btnm_pads;      /* buttonmatrix MAIN pads + gaps                */
  lv_style_t btnm_items;     /* buttonmatrix ITEMS fill (asgard-only)        */
  lv_style_t roller_pad;     /* roller MAIN pad_hor                          */
  lv_style_t ta_pad;         /* textarea MAIN pad_ver                        */
  lv_style_t table_items;    /* table ITEMS pad                              */
  lv_style_t table_grid;     /* table ITEMS full-side dividers (asgard-only) */
  lv_style_t cb_ind;         /* checkbox INDICATOR radius                    */
  lv_style_t cb_grow_off;    /* checkbox INDICATOR|PRESSED grow cancel
                           * (asgard-only)                               */
  lv_style_t led;            /* led shadow flattening                        */
  lv_style_t knob;           /* slider/switch KNOB radius (crisp)            */
  lv_style_t scrollbar;      /* SCROLLBAR crisp radius + edge tone
                           * (asgard-only)                               */
  lv_style_t field_bg;       /* closed-field surface fill — dropdown MAIN
                           * (asgard-only)                               */
  lv_style_t focus;          /* FOCUS_KEY outline                            */
  lv_style_t checked_accent; /* CHECKED-state fill — cyan affordance over
                            * stock's violet color_primary (checkbox/switch
                            * indicator, roller/dropdown selected band,
                            * buttonmatrix checked item) (asgard-only)     */
  lv_style_t edited_edge;    /* EDITED-state outline — cyan ring over stock's
                            * red color_secondary (slider/bar/roller/spinbox/
                            * textarea) (asgard-only)                       */
  lv_style_t disabled;       /* DISABLED dim — filled value widgets
                            * (asgard-only, empty in vanilla)              */
  lv_style_t disabled_text;  /* DISABLED for text-value widgets (spinbox/
                             * textarea/checkbox label): authored text
                             * tone + recolor, NO opa — a whole-widget
                             * opa halves text-vs-fill self-contrast
                             * (asgard-only)                              */
  lv_style_t disabled_fill;  /* DISABLED for accent-filled buttons:
                             * authored surface-2 fill + disabled text,
                             * stock recolor neutralized (asgard-only)    */
  lv_style_t disabled_flat;  /* DISABLED for no-fill line-art surfaces
                             * (table): opa fade toward the local bg +
                             * authored text, NO recolor — a fixed
                             * recolor target LIGHTENS dark cells
                             * (asgard-only)                              */
  lv_style_t hover;          /* HOVERED lighten (asgard-only)                */
  lv_style_t pressed;        /* PRESSED darken for classes stock leaves
                            * unpressed — arc, roller, dropdown, checkbox
                            * indicator (asgard-only)                      */
  lv_style_t track_tone;     /* resting ring tone (arc/spinner MAIN
                            * arc_color) — asgard-only, BOTH modes: the
                            * stock grey ring sinks into the light
                            * surface AND measures under the 3:1 floor
                            * on the dark canvas                           */
  lv_style_t track_bg;       /* resting rect-track fill (bar/slider/switch
                            * MAIN): edge tone at FULL opa — stock's
                            * LV_OPA_20 muted track dilutes any authored
                            * color back toward the panel (asgard-only)    */
  lv_style_t tab_txt;        /* selected tab-bar label text, DARK only: the
                            * stock-derived selected-label tint converges
                            * with the muted accent tab fill (asgard-only) */
  lv_style_t cursor_off;     /* spinbox CURSOR hidden under DISABLED — a
                            * disabled control has no active edit cell
                            * (asgard-only)                                */
  lv_style_t trans;          /* zero-time transitions (asgard-only)          */
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
  /* control radius — form-control tier. Asgard also owns the tier's
   * component boundary here: stock's card border (grey, 2px) measured
   * ~1.03-1.2:1 against the light surface on textarea/spinbox, so the
   * edge-0 token (the 3:1-floor boundary tone panels already use) replaces
   * it wherever control_rad is applied — dropdown, dropdown list, roller,
   * spinbox, textarea, buttonmatrix container. Vanilla keeps stock's card
   * border untouched. */
  style_reset(&s->control_rad, inited);
  lv_style_set_radius(&s->control_rad, pick_i32(v, stock_radius_default(t),
                                                THEME_RADIUS_CONTROL));
  if (!v) {
    lv_style_set_border_color(
        &s->control_rad,
        lv_color_hex(pick_u32(t->dark, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT)));
    lv_style_set_border_width(&s->control_rad, THEME_BORDER_W);
  }
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
  /* buttonmatrix MAIN pads + gaps. The edge pad carries the container's
   * corner radius on top of the control pad: with the surface-2 item fill
   * an item's near-square corner sits inside the container's rounded
   * sweep, and the bare 4px clearance visibly pinches at the corners
   * (worst at the small stress cell). pad + radius keeps the item corner
   * clear of the sweep at any card size. */
  style_reset(&s->btnm_pads, inited);
  lv_style_set_pad_all(
      &s->btnm_pads,
      pick_i32(v, stock_pad_def(t), THEME_PAD_CONTROL + THEME_RADIUS_CONTROL));
  /* Inter-item gap at control-pad size: the 2px grid gap fused adjacent
   * items into one segmented bar (near-invisible divider in dark); 4px
   * shows the container between items so segments read as distinct keys. */
  lv_style_set_pad_row(&s->btnm_pads,
                       pick_i32(v, stock_pad_small(t), THEME_PAD_CONTROL));
  lv_style_set_pad_column(&s->btnm_pads,
                          pick_i32(v, stock_pad_small(t), THEME_PAD_CONTROL));
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
  /* table column dividers — stock's cell style draws TOP|BOTTOM borders
   * only, so columns had no vertical separation at any column count.
   * Full-side cell borders in the edge tone give each cell a real
   * boundary. Asgard-only: vanilla keeps stock's row-only rules. */
  style_reset(&s->table_grid, inited);
  if (!v) {
    lv_style_set_border_side(&s->table_grid, LV_BORDER_SIDE_FULL);
    lv_style_set_border_width(&s->table_grid, THEME_BORDER_W);
    lv_style_set_border_color(
        &s->table_grid,
        lv_color_hex(pick_u32(t->dark, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT)));
  }
  /* checkbox indicator */
  style_reset(&s->cb_ind, inited);
  lv_style_set_radius(&s->cb_ind, pick_i32(v, stock_radius_default(t) / 2,
                                           THEME_RADIUS_CONTROL));
  /* checkbox pressed-indicator grow cancel — stock's default theme adds a +3px
   * GROW transform to the checkbox INDICATOR|PRESSED (its `grow` style). Against
   * asgard's crisp square indicator that renders as a SETTLED, 3px-oversized box
   * (asgard's zero-time transition applies the grow instantly, not mid-anim) that
   * spills 3px outside the checkbox's own bounds — a broken pressed box. Zero the
   * transform so a pressed checkbox recolors IN PLACE. Asgard-only: vanilla keeps
   * stock's grow, so vanilla-equals-stock holds by scope. */
  style_reset(&s->cb_grow_off, inited);
  if (!v) {
    lv_style_set_transform_width(&s->cb_grow_off, 0);
    lv_style_set_transform_height(&s->cb_grow_off, 0);
  }
  /* led shadow flattening */
  style_reset(&s->led, inited);
  lv_style_set_shadow_width(&s->led,
                            pick_i32(v, dpx(t->dpi, 15), THEME_DROP_W));
  lv_style_set_shadow_spread(&s->led,
                             pick_i32(v, dpx(t->dpi, 5), THEME_DROP_SPREAD));
  /* crisp radius: slider/switch knobs AND the slider/bar track+fill reuse
   * this one style so the parts agree by construction (arc knob excluded) */
  style_reset(&s->knob, inited);
  lv_style_set_radius(&s->knob,
                      pick_i32(v, LV_RADIUS_CIRCLE, THEME_RADIUS_CONTROL));
  /* scrollbar — asgard-only: stock leaves every scrollbar a full-circle
   * pill in the resting stock tone (light-mode contrast ~1.4:1). Crisp the
   * radius to the control tier and paint it the edge tone — the same
   * boundary-chrome role edge-0 already owns — full-opa so it clears the
   * light surface. Applied for every object (LVGL draws it only where the
   * part exists), so every scrollable widget agrees at once. */
  style_reset(&s->scrollbar, inited);
  if (!v) {
    lv_style_set_radius(&s->scrollbar, THEME_RADIUS_CONTROL);
    lv_style_set_bg_color(
        &s->scrollbar,
        lv_color_hex(pick_u32(t->dark, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT)));
    lv_style_set_bg_opa(&s->scrollbar, LV_OPA_COVER);
  }
  /* closed-field surface — asgard-only: the closed dropdown kept stock's
   * pure-white fill, a foreign bright patch against the light card
   * (measured (255,255,255) vs THEME_SURFACE1_LIGHT). Author the field's
   * surface to the panel tone; the control's own border + radius keep it
   * legible as a field. */
  style_reset(&s->field_bg, inited);
  if (!v)
    lv_style_set_bg_color(&s->field_bg,
                          lv_color_hex(pick_u32(t->dark, THEME_SURFACE1_DARK,
                                                THEME_SURFACE1_LIGHT)));
  /* buttonmatrix ITEMS fill — asgard-only: container-vs-item contrast
   * measured ~1.0-1.1:1 (items were indistinguishable from the container
   * they sit on); the surface-2 tone gives each key a real fill against
   * the surface-1 card, and the wider gap shows the boundary. */
  style_reset(&s->btnm_items, inited);
  if (!v)
    lv_style_set_bg_color(&s->btnm_items,
                          lv_color_hex(pick_u32(t->dark, THEME_SURFACE2_DARK,
                                                THEME_SURFACE2_LIGHT)));
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
    /* Thin pad, not OUTLINE_W: the outline's corner radius grows with its
     * pad, so a 2px pad swept a visibly rounded cap around the crisp
     * 2px-radius controls (slider knob, spinbox). A 1px standoff keeps the
     * ring separate from the border while the corner stays near-crisp. */
    lv_style_set_outline_pad(&s->focus, THEME_BORDER_W);
    lv_style_set_outline_opa(&s->focus, LV_OPA_COVER);
  }
  /* checked-state fill — asgard-only NEW affordance colour. The interactive
   * lane's :checked-accent (mode-invariant cyan) fills the checked state over
   * stock's violet color_primary: the checkbox/switch indicator, the roller/
   * dropdown selected band, the buttonmatrix checked item. bg only — stock's
   * cover opa + white selected-text stand, so the checkmark/label rides
   * white-on-cyan (the token proves >=4.5:1 both modes). Vanilla stays empty
   * so vanilla-equals-stock holds by scope. */
  style_reset(&s->checked_accent, inited);
  if (!v) {
    lv_style_set_bg_color(&s->checked_accent,
                          lv_color_hex(pick_u32(t->dark, THEME_CHECKED_DARK,
                                                THEME_CHECKED_LIGHT)));
    lv_style_set_bg_opa(&s->checked_accent, LV_OPA_COVER);
  }
  /* edited-state ring — asgard-only. The SAME cyan as an OUTLINE for the
   * encoder-edit state (slider/bar/roller/spinbox/textarea), replacing stock's
   * red color_secondary edited outline (red collides semantically with
   * :status-error AND measured under the light-surface boundary floor). The
   * ring geometry mirrors the focus ring so both crisp affordance rings agree.
   * Vanilla stays empty so vanilla-equals-stock holds by scope. */
  style_reset(&s->edited_edge, inited);
  if (!v) {
    lv_style_set_outline_color(
        &s->edited_edge, lv_color_hex(pick_u32(t->dark, THEME_CHECKED_DARK,
                                               THEME_CHECKED_LIGHT)));
    lv_style_set_outline_width(&s->edited_edge, THEME_OUTLINE_W);
    lv_style_set_outline_pad(&s->edited_edge, THEME_BORDER_W);
    lv_style_set_outline_opa(&s->edited_edge, LV_OPA_COVER);
  }
  /* disabled dim — asgard-only NEW state coverage (stock has none for the
   * value widgets); vanilla stays empty so stock-parity holds. One formula
   * does NOT fit every content class, so the dim splits four ways:
   *
   * - `disabled` (opa + recolor + text): FILLED value widgets whose
   *   critical content is geometry, not glyphs — slider, arc, bar, switch,
   *   dropdown, roller, led, the tabview root, standalone labels, the
   *   checkbox indicator. Opacity alone blends toward whatever sits BEHIND
   *   the part (adequate on dark, self-contrast-collapsing on light) and
   *   never desaturates a saturated hue, so the draw is also pulled toward
   *   the authored disabled-fg tone.
   * - `disabled_text` (text + recolor, NO opa): text-value widgets whose
   *   critical content IS the glyph run — spinbox digits, textarea text,
   *   the checkbox label. A whole-widget opa scales every draw through
   *   layer->opa and LVGL has no per-run text exemption, so the 50% fade
   *   moves glyph luminance toward the fill and halves self-contrast (the
   *   light-mode collapse, measured ~1.2:1 at small spinbox); no token
   *   retune can recover it (even a black pre-image caps ~3:1 after the
   *   blend). The recolor no-ops on the authored glyph tone (same target)
   *   while still muting the field chrome.
   * - `disabled_fill` (authored fill + text, recolor OFF): the accent
   *   button. Both opa and recolor converge a white-on-accent label with
   *   its own fill; instead the fill collapses to surface-2 and the label
   *   takes the disabled-fg tone — a pairing the tokens prove >=4.5:1 in
   *   both modes. recolor_opa 0 overrides the stock grey wash so the
   *   authored pair renders exactly.
   * - `disabled_flat` (opa + text, NO recolor): no-fill line-art surfaces
   *   (table). The disabled-fg recolor target is LIGHTER than the dark
   *   cell tone, so recoloring LIGHTENS a disabled dark table (it pops
   *   instead of receding); the opa fade recedes toward the local bg in
   *   both modes. */
  style_reset(&s->disabled, inited);
  if (!v) {
    lv_style_set_opa(&s->disabled, THEME_DISABLED_OPA);
    lv_style_set_text_color(
        &s->disabled, lv_color_hex(pick_u32(t->dark, THEME_DISABLED_FG_DARK,
                                            THEME_DISABLED_FG_LIGHT)));
    lv_style_set_recolor(&s->disabled,
                         lv_color_hex(pick_u32(t->dark, THEME_DISABLED_FG_DARK,
                                               THEME_DISABLED_FG_LIGHT)));
    lv_style_set_recolor_opa(&s->disabled, 100);
  }
  style_reset(&s->disabled_text, inited);
  if (!v) {
    lv_style_set_text_color(
        &s->disabled_text,
        lv_color_hex(pick_u32(t->dark, THEME_DISABLED_FG_DARK,
                              THEME_DISABLED_FG_LIGHT)));
    lv_style_set_recolor(&s->disabled_text,
                         lv_color_hex(pick_u32(t->dark, THEME_DISABLED_FG_DARK,
                                               THEME_DISABLED_FG_LIGHT)));
    lv_style_set_recolor_opa(&s->disabled_text, 100);
  }
  style_reset(&s->disabled_fill, inited);
  if (!v) {
    lv_style_set_bg_color(&s->disabled_fill,
                          lv_color_hex(pick_u32(t->dark, THEME_SURFACE2_DARK,
                                                THEME_SURFACE2_LIGHT)));
    lv_style_set_text_color(
        &s->disabled_fill,
        lv_color_hex(pick_u32(t->dark, THEME_DISABLED_FG_DARK,
                              THEME_DISABLED_FG_LIGHT)));
    lv_style_set_recolor_opa(&s->disabled_fill, LV_OPA_TRANSP);
  }
  style_reset(&s->disabled_flat, inited);
  if (!v) {
    lv_style_set_opa(&s->disabled_flat, THEME_DISABLED_OPA);
    lv_style_set_text_color(
        &s->disabled_flat,
        lv_color_hex(pick_u32(t->dark, THEME_DISABLED_FG_DARK,
                              THEME_DISABLED_FG_LIGHT)));
  }
  /* hover lighten/darken — asgard-only (stock styles HOVERED nowhere). The
   * recolor targets the CONTRASTING pole — white on dark, black on light — so
   * the delta reads in BOTH themes. A fixed white recolor was a near-no-op on
   * the light surface, the same polarity blindness the resting-track
   * styles below correct for tracks. */
  style_reset(&s->hover, inited);
  if (!v) {
    lv_style_set_recolor(
        &s->hover, pick_color(t->dark, lv_color_white(), lv_color_black()));
    lv_style_set_recolor_opa(&s->hover, 40);
  }
  /* pressed darken/lighten — asgard-only, for the classes whose stock arms
   * never style PRESSED (arc, roller) although the widget-states manifest
   * commits it. Same polarity rule as hover at a stronger opa, so approach
   * (hover) < commit (pressed) in both themes. A fixed black recolor was a
   * near-no-op on the dark surface — pressed was invisible in dark. */
  style_reset(&s->pressed, inited);
  if (!v) {
    lv_style_set_recolor(
        &s->pressed, pick_color(t->dark, lv_color_white(), lv_color_black()));
    lv_style_set_recolor_opa(&s->pressed, 64);
  }
  /* resting-track tones — asgard-only, BOTH modes. A light-mode-only
   * edge tone shipped first on the claim "the dark surface already
   * contrasts"; measurement falsified it (arc/bar/spinner dark tracks
   * ~1.17-1.44:1 vs the >=3:1 boundary floor), so both modes now take
   * their edge-0 tone (dark computes ~3.6:1 on the dark canvas). Two
   * styles because the mechanisms differ per draw primitive:
   *
   * - `track_tone` (arc_color only) — arc/spinner MAIN rings. It must NOT
   *   carry bg_opa: the base obj draw fills the MAIN bg rect when opaque,
   *   so a covered bg would paint a filled square behind the ring.
   * - `track_bg` (bg_color + bg_opa COVER) — bar/slider/switch MAIN rect
   *   tracks. COVER is load-bearing: stock's bg_color_primary_muted keeps
   *   bar/slider MAIN at LV_OPA_20, which rendered the authored edge tone
   *   at ~1.26:1 (near-invisible at min-value) — the color was right and
   *   the opacity diluted it back into the panel. */
  style_reset(&s->track_tone, inited);
  if (!v)
    lv_style_set_arc_color(
        &s->track_tone,
        lv_color_hex(pick_u32(t->dark, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT)));
  style_reset(&s->track_bg, inited);
  if (!v) {
    lv_style_set_bg_color(
        &s->track_bg,
        lv_color_hex(pick_u32(t->dark, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT)));
    lv_style_set_bg_opa(&s->track_bg, LV_OPA_COVER);
  }
  /* selected tab-bar label — asgard DARK only, COLOR only. The tab bar is
   * frozen to stock geometry (demo-parity capstone), but stock derives the
   * selected label tint from color_primary while the selected tab fill is
   * the same primary at LV_OPA_20 — with the violet accent the pair
   * converges (~1.15-2.4:1 measured in dark; light passes AA). fg-0 is the
   * accent-text rule (white-on-accent) applied to the one label the
   * operator glances at most. text_color changes no geometry, so the
   * capstone freeze holds; demo-parity renders family VANILLA, which adds
   * nothing here. */
  style_reset(&s->tab_txt, inited);
  if (!v && t->dark)
    lv_style_set_text_color(&s->tab_txt, lv_color_hex(THEME_FG0_DARK));
  /* disabled spinbox cursor — asgard-only: the stock cursor keeps its
   * highlight under DISABLED, where the dimmed digit sinks into it; a
   * disabled control has no active edit cell, so the highlight goes. The
   * cursor cell's DIGIT rides the cursor part's own text color (stock:
   * white-on-accent), so with the highlight hidden it must take the same
   * disabled tone as its sibling digits or it washes out on the light
   * field. */
  style_reset(&s->cursor_off, inited);
  if (!v) {
    lv_style_set_bg_opa(&s->cursor_off, LV_OPA_TRANSP);
    lv_style_set_text_color(
        &s->cursor_off, lv_color_hex(pick_u32(t->dark, THEME_DISABLED_FG_DARK,
                                              THEME_DISABLED_FG_LIGHT)));
  }
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
  /* Scrollbar chrome for EVERY widget, before the class dispatch: the part
   * only draws where a widget scrolls, so one attachment here keeps table,
   * roller, dropdown-list, textarea, chart, and tabview pages agreeing. */
  if (t->family == ASGARD_THEME_FAMILY_ASGARD)
    lv_obj_add_style(obj, &t->styles.scrollbar, LV_PART_SCROLLBAR);
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
    /* Tab-bar buttons keep stock GEOMETRY entirely (frozen capstone);
     * asgard adds one color-only style — the dark selected-label fix
     * (see the tab_txt init comment). The label child inherits
     * text_color resolved in the button's own state, so the CHECKED
     * selector reaches it without touching the label. */
    lv_obj_t *tv = lv_obj_get_parent(parent);
    if (tv != NULL && lv_obj_get_child(tv, 0) == parent &&
        lv_obj_check_type(tv, &lv_tabview_class)) {
      if (t->family == ASGARD_THEME_FAMILY_ASGARD)
        lv_obj_add_style(obj, &t->styles.tab_txt, LV_STATE_CHECKED);
      return;
    }
#endif
    lv_obj_add_style(obj, &t->styles.btn, 0);
    lv_obj_add_style(obj, &t->styles.btn_shadow, 0);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    /* DISABLED — the accent-filled variant (see the disabled_fill init
     * comment): the accent drains to surface-2 and the label takes the
     * disabled-fg tone, a token-proven >=4.5:1 pair in both modes. Both
     * generic dim formulas fail here: opa halves the label-vs-fill delta,
     * and a recolor drags label and fill toward the same target. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.disabled_fill, LV_STATE_DISABLED);
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SLIDER
  if (lv_obj_check_type(obj, &lv_slider_class)) {
    lv_obj_add_style(obj, &t->styles.knob, LV_PART_KNOB);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
      /* Edited (encoder-adjust) ring — cyan over stock's red edited outline. */
      lv_obj_add_style(obj, &t->styles.edited_edge, LV_STATE_EDITED);
      /* Track + fill agree with the crisp knob — the same by-construction
       * radius reuse the switch arm documents (stock leaves slider MAIN and
       * INDICATOR at LV_RADIUS_CIRCLE, so the track stayed a pill around a
       * square knob). Asgard-only: vanilla keeps stock's circle on all
       * parts, so vanilla-equals-stock stays true by scope. */
      lv_obj_add_style(obj, &t->styles.knob, LV_PART_MAIN);
      lv_obj_add_style(obj, &t->styles.knob, LV_PART_INDICATOR);
      /* Resting track: edge tone at full opa, both modes — see the
       * track_bg init comment (stock's LV_OPA_20 muted MAIN diluted any
       * authored tone back into the panel). */
      lv_obj_add_style(obj, &t->styles.track_bg, LV_PART_MAIN);
    }
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SWITCH
  if (lv_obj_check_type(obj, &lv_switch_class)) {
    lv_obj_add_style(obj, &t->styles.knob, LV_PART_KNOB);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.track_bg, LV_PART_MAIN);
      /* Checked (ON) fill — cyan over stock's violet indicator. */
      lv_obj_add_style(obj, &t->styles.checked_accent,
                       LV_PART_INDICATOR | LV_STATE_CHECKED);
      /* DISABLED dim — the same object-level opa+recolor the slider arm
       * carries (the MAIN opa folds into layer->opa, so knob and indicator
       * fade with the track). Stock's grey recolor alone pulled the light
       * track TOWARD the page tone; the disabled-fg target is darker than
       * the light surface, so the dim recovers instead of erasing. */
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
      /* Body radius must AGREE with the knob's. `knob` gives asgard a crisp
       * THEME_RADIUS_CONTROL corner, but the switch MAIN was left to stock —
       * whose radius is LV_RADIUS_CIRCLE — so the track stayed a full pill
       * around a square knob. `knob` carries radius and nothing else, so
       * re-using it here makes the two agree BY CONSTRUCTION rather than by a
       * second constant that could drift. Asgard-only: vanilla keeps the stock
       * circle on both parts, so vanilla-equals-stock stays true by scope. */
      lv_obj_add_style(obj, &t->styles.knob, LV_PART_MAIN);
      /* ...and the INDICATOR, which is what a checked switch actually shows.
       * The filled blue body is not MAIN in a checked state — it is the
       * indicator drawn over MAIN, carrying its own circle radius. Squaring
       * MAIN alone therefore fixed every UNchecked state and left the checked
       * one a pill, which is the half-fixed look this line completes. */
      lv_obj_add_style(obj, &t->styles.knob, LV_PART_INDICATOR);
    }
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
      lv_obj_add_style(obj, &t->styles.track_tone, LV_PART_MAIN);
    }
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SPINNER
  if (lv_obj_check_type(obj, &lv_spinner_class)) {
    /* spinner is its own class (exact-type checks miss the arc arm); the
     * resting ring needs the same edge tone. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.track_tone, LV_PART_MAIN);
    return;
  }
#endif
#if LV_USE_BAR
  if (lv_obj_check_type(obj, &lv_bar_class)) {
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
      /* Edited (encoder-adjust) ring — cyan over stock's red edited outline. */
      lv_obj_add_style(obj, &t->styles.edited_edge, LV_STATE_EDITED);
      /* A bar IS a slider track (same pill stock geometry); squaring the
       * slider and not the bar would stack two track shapes side by side.
       * Same knob-radius reuse, same asgard-only scope. */
      lv_obj_add_style(obj, &t->styles.knob, LV_PART_MAIN);
      lv_obj_add_style(obj, &t->styles.knob, LV_PART_INDICATOR);
      /* Resting track — same as the slider MAIN: edge tone at full opa,
       * both modes (see the track_bg init comment). */
      lv_obj_add_style(obj, &t->styles.track_bg, LV_PART_MAIN);
    }
    return;
  }
#endif
#if LV_USE_CHECKBOX
  if (lv_obj_check_type(obj, &lv_checkbox_class)) {
    lv_obj_add_style(obj, &t->styles.cb_ind, LV_PART_INDICATOR);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      /* Cancel stock's +3px grow on the pressed indicator, so the crisp box
       * stays put on press instead of spilling outside the checkbox — and
       * pair the cancel with the pressed recolor the way arc/roller do:
       * without it, cancelling the grow left press feedback 2-4x weaker
       * than stock's own. */
      lv_obj_add_style(obj, &t->styles.cb_grow_off,
                       LV_PART_INDICATOR | LV_STATE_PRESSED);
      lv_obj_add_style(obj, &t->styles.pressed,
                       LV_PART_INDICATOR | LV_STATE_PRESSED);
      /* Checked fill — cyan over stock's violet indicator; stock's white
       * checkmark rides on top (white-on-cyan token-proven >=4.5:1). */
      lv_obj_add_style(obj, &t->styles.checked_accent,
                       LV_PART_INDICATOR | LV_STATE_CHECKED);
      /* DISABLED — per part, per content class: the indicator (border +
       * fill geometry) takes the filled-widget dim; the label text rides
       * MAIN, where a whole-widget opa would halve glyph self-contrast, so
       * MAIN takes the text-preserving variant (authored tone; without it
       * the disabled label rendered byte-identical to default). */
      lv_obj_add_style(obj, &t->styles.disabled,
                       LV_PART_INDICATOR | LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.disabled_text, LV_STATE_DISABLED);
    }
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_DROPDOWN
  if (lv_obj_check_type(obj, &lv_dropdown_class)) {
    lv_obj_add_style(obj, &t->styles.control_rad, 0);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    /* Closed-field surface: stock's white fill is a foreign bright patch
     * against the light card — author it to the panel tone (asgard-only). */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.field_bg, 0);
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
      /* PRESSED — the documented dark-pressed fix already proven on the
       * arc/roller arms; stock's black recolor is a near-no-op on the dark
       * surface (measured pressed-vs-default delta 3-8/255). */
      lv_obj_add_style(obj, &t->styles.pressed, LV_STATE_PRESSED);
    }
    add_interactive(t, obj);
    return;
  }
  /* The popup LIST is its own class the stock theme cards (rounded); the
   * closed dropdown above is crisp, and the SELECTED highlight inside the
   * list is a square band — so the open list was the one rounded container
   * in an otherwise crisp control. Asgard-only, same scope rule as the
   * other radius agreements. */
  if (lv_obj_check_type(obj, &lv_dropdownlist_class)) {
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.control_rad, 0);
      /* Selected option — cyan over stock's violet SELECTED|CHECKED fill
       * (stock's own selector; see lv_theme_default's dropdownlist arm). Not
       * wire-reachable from a static fixture today — the renderer has no
       * open-list decode path — so this is inert-but-correct affordance
       * coverage for the day it lands. */
      lv_obj_add_style(obj, &t->styles.checked_accent,
                       LV_PART_SELECTED | LV_STATE_CHECKED);
    }
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
      /* Selected band — cyan over stock's violet PART_SELECTED fill (the
       * always-visible centred option); white option text rides on top. */
      lv_obj_add_style(obj, &t->styles.checked_accent, LV_PART_SELECTED);
      /* Edited (encoder-adjust) ring — cyan over stock's red edited outline. */
      lv_obj_add_style(obj, &t->styles.edited_edge, LV_STATE_EDITED);
      /* Stock cards the roller MAIN (rounded) while its SELECTED band is a
       * square stripe — the same round-container/square-inner dissonance as
       * the dropdown list; crisp the container to agree. */
      lv_obj_add_style(obj, &t->styles.control_rad, 0);
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
    /* DISABLED — text-preserving variant (see the disabled_text init
     * comment): the content IS a glyph run, so the authored dim tone
     * replaces the fade; stock's own recolor-only dim stays underneath
     * for the field chrome but the recolor target is overridden so it
     * no-ops on the authored text. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.disabled_text, LV_STATE_DISABLED);
      /* Edited (active-edit) ring — cyan over stock's red edited outline. */
      lv_obj_add_style(obj, &t->styles.edited_edge, LV_STATE_EDITED);
    }
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SPINBOX
  if (lv_obj_check_type(obj, &lv_spinbox_class)) {
    lv_obj_add_style(obj, &t->styles.control_rad, 0);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      /* DISABLED — text-preserving variant, NOT the opa dim: the digits
       * are the widget's whole content, and the 50% fade collapsed them
       * to ~1.2:1 on the light fill (see the disabled_text init comment
       * for why neither an opa exemption nor a token retune can fix it). */
      lv_obj_add_style(obj, &t->styles.disabled_text, LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.cursor_off,
                       LV_PART_CURSOR | LV_STATE_DISABLED);
      /* Edited (encoder-adjust) ring — cyan over stock's red edited outline. */
      lv_obj_add_style(obj, &t->styles.edited_edge, LV_STATE_EDITED);
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
    /* Item fill: each key gets the surface-2 tone against the surface-1
     * container (asgard-only) — see the btnm_items init comment. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.btnm_items, LV_PART_ITEMS);
    /* Checked item — cyan over stock's violet ITEMS|CHECKED fill. Not
     * wire-reachable today (the renderer decodes no set_btn_ctrl /
     * set_selected_button), so inert-but-correct affordance coverage. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.checked_accent,
                       LV_PART_ITEMS | LV_STATE_CHECKED);
    /* Square the MAIN container: stock cards the buttonmatrix (rounded) while
     * the items are already crisp chips (item_rad) — the same
     * round-container/crisp-inner dissonance the dropdown-list and roller arms
     * already correct on MAIN. Asgard-only, so vanilla keeps the stock card
     * radius and vanilla-equals-stock holds by scope. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.control_rad, 0);
    return;
  }
#endif
#if LV_USE_TABLE
  if (lv_obj_check_type(obj, &lv_table_class)) {
    /* MAIN stays stock (already zeroed there); only the cell inset. */
    lv_obj_add_style(obj, &t->styles.table_items, LV_PART_ITEMS);
    /* Column dividers (asgard-only) — see the table_grid init comment. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.table_grid, LV_PART_ITEMS);
    /* DISABLED dim — asgard-only new-state coverage (stock's table arm styles
     * no disabled state); the opa cascades over the grid + cells. The FLAT
     * variant, not the recolor one: the table's cells carry no fill, and the
     * disabled-fg recolor target is lighter than the dark card tone, so the
     * recolor made a disabled dark table POP instead of recede (see the
     * disabled_flat init comment). Vanilla adds nothing, so
     * vanilla-equals-stock holds by scope. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.disabled_flat, LV_STATE_DISABLED);
    return;
  }
#endif
#if LV_USE_TABVIEW
  if (lv_obj_check_type(obj, &lv_tabview_class)) {
    /* The tabview ROOT: its bar/content/pages are styled by the obj-arm guards
     * above, so the root itself normally takes nothing. Asgard adds only the
     * DISABLED dim so a disabled tabview reads inactive like the other value
     * widgets — the opa cascades over the whole tab chrome. Vanilla adds
     * nothing, so vanilla-equals-stock holds by scope. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
    return;
  }
#endif
#if LV_USE_LED
  if (lv_obj_check_type(obj, &lv_led_class)) {
    lv_obj_add_style(obj, &t->styles.led, 0);
    /* DISABLED dim — asgard-only new-state coverage (stock's led arm styles
     * only MAIN|DEFAULT). Vanilla adds nothing, so vanilla-equals-stock holds
     * by scope. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
    return;
  }
#endif
#if LV_USE_LABEL
  if (lv_obj_check_type(obj, &lv_label_class)) {
    /* DISABLED dim for standalone labels — NO family styles a plain label's
     * disabled state (stock covers labels only as textarea children), so a
     * label carrying LV_STATE_DISABLED rendered pixel-identical to enabled.
     * The shared disabled style authors the dim text tone + recolor; a
     * label without the state bit is untouched. Vanilla adds nothing, so
     * vanilla-equals-stock holds by scope. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
    return;
  }
#endif
  /* Everything else (image, scale, line, chart, ...) falls through to
   * stock untouched. */
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
