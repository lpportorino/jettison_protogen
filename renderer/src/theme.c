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
  lv_style_t accent_ink;     /* ink for ANY surface stock fills with
                              * color_primary — asgard-only. Stock's
                              * bg_color_primary hardcodes lv_color_white()
                              * beside that fill, which is only legible while
                              * the fill is dark; the asgard accent is LIGHT in
                              * dark mode, so every such site needs the ink
                              * inverted with it. Applied wherever stock uses
                              * that pair, not only on buttons.               */
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
                            * indicator, bar indicator, dropdown-list
                            * selected option, buttonmatrix checked item)
                            * (asgard-only). NOT the roller band — see
                            * roller_sel                                   */
  lv_style_t roller_sel;     /* roller SELECTED band, ENABLED: fg-0 fill +
                            * surface-1 glyphs (asgard-only)               */
  lv_style_t roller_sel_dis; /* roller SELECTED band, DISABLED: the disabled
                            * PAIR SWAPPED — disabled-fg fill + surface-2
                            * glyphs (asgard-only)                         */
  lv_style_t edited_edge;    /* EDITED-state outline — cyan ring over stock's
                            * red color_secondary (slider/bar/roller/spinbox/
                            * textarea) (asgard-only)                       */
  lv_style_t disabled;       /* DISABLED for text-bearing widgets with NO
                             * fill to swap (label, checkbox MAIN): tone
                             * only, NO opa and NO recolor (asgard-only)  */
  lv_style_t disabled_dim;   /* DISABLED for TEXT-FREE geometry widgets
                             * only: opa fade + recolor. Its precondition
                             * is the empty subtree, not the class list  */
  lv_style_t disabled_fill;  /* DISABLED PAIR SWAP — surface-2 fill +
                             * disabled text, stock recolor neutralized.
                             * The default for any FILLED text-bearing
                             * widget: button, the field controls, the
                             * tabview root (asgard-only)                 */
  lv_style_t disabled_edge;  /* DISABLED BOUNDARY for a widget whose only
                             * visual edge is its FILL — the button. An
                             * OUTLINE, never a border: a border is
                             * layout-bearing and clipped every label it
                             * enclosed. Rides BESIDE disabled_fill, never
                             * inside it (asgard-only)                    */
  lv_style_t disabled_flat;  /* DISABLED for the table's line-art grid:
                             * opa fade, NO recolor — a fixed recolor
                             * target LIGHTENS dark cells. Same
                             * text-free precondition as disabled_dim   */
  lv_style_t disabled_track; /* DISABLED track for a two-part control whose
                             * VALUE is knob-vs-track contrast — the switch.
                             * Pair-swap, no fade: see disabled_knob      */
  lv_style_t disabled_knob;  /* DISABLED knob, the other half of that pair
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
  lv_style_t readout_arc;    /* readout VALUE tone — the arc INDICATOR under
                              * USER_2, and the spinner's moving arm; closes
                              * the stock color_primary fallthrough on a
                              * readout (asgard-only)                       */
  lv_style_t readout_knob;   /* an arc mark the operator cannot move: a
                              * ring-thick pointer tip, not a grab handle
                              * (asgard-only, LV_STATE_USER_2)             */
  lv_style_t readout_knob_off; /* the SAME mark withdrawn once the readout is
                              * DISABLED — a dead widget shows no live value
                              * (asgard-only, USER_2 + DISABLED)           */
  lv_style_t track_bg;         /* resting rect-track fill (bar/slider/switch
                            * MAIN): edge tone at FULL opa — stock's
                            * LV_OPA_20 muted track dilutes any authored
                            * color back toward the panel (asgard-only)    */
  lv_style_t tab_txt;          /* selected tab-bar label text, DARK only: the
                            * stock-derived selected-label tint converges
                            * with the muted accent tab fill (asgard-only) */
  lv_style_t tab_bar_bg;  /* tab-bar chrome fill — surface-2 (asgard-only) */
  lv_style_t tab_page_bg; /* tab content + pages — surface-0, the base tier
                            * a surface-1 panel sits ON. COLOR-ONLY, both:
                            * the obj arm returns early for these three
                            * containers to keep stock GEOMETRY (the
                            * demo-parity capstone froze it), which also left
                            * them with NO fill of ours — so they fell through
                            * to stock, and stock LIGHT is white. Measured:
                            * asgard-dark was byte-identical to VANILLA there,
                            * i.e. unstyled in both families; dark merely read
                            * as acceptable because stock LVGL is dark too.
                            * Setting bg_color/bg_opa moves no geometry, so the
                            * freeze holds (the same argument tab_txt makes),
                            * and demo-parity renders VANILLA, which these skip
                            * entirely (asgard-only)                        */
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
static bool style_recolor_matches(const lv_style_t *style,
                                  lv_color32_t recolor) {
  lv_style_value_t color;
  lv_style_value_t opa;
  if (lv_style_get_prop(style, LV_STYLE_RECOLOR, &color) !=
          LV_STYLE_RES_FOUND ||
      lv_style_get_prop(style, LV_STYLE_RECOLOR_OPA, &opa) !=
          LV_STYLE_RES_FOUND)
    return false;
  return recolor.red == color.color.red && recolor.green == color.color.green &&
         recolor.blue == color.color.blue && recolor.alpha == (lv_opa_t)opa.num;
}
bool asgard_theme_recolor_is_declared(lv_color32_t recolor) {
  /* `inited` is load-bearing, not belt-and-braces: theme_inst is static, so
   * before the first asgard_theme_init its zeroed `family` reads as
   * ASGARD_THEME_FAMILY_ASGARD (0) and the guard below would vouch for
   * recolors against never-populated styles. */
  if (!theme_inst.inited || theme_inst.family != ASGARD_THEME_FAMILY_ASGARD ||
      recolor.alpha == LV_OPA_TRANSP)
    return false;
  return style_recolor_matches(&theme_inst.styles.hover, recolor) ||
         style_recolor_matches(&theme_inst.styles.pressed, recolor) ||
         style_recolor_matches(&theme_inst.styles.disabled_dim, recolor);
}
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
/* The dark/light TOKEN-PAIR select — the one compound this theme spells at
 * every token site: resolve the mode's member of a THEME_*_DARK /
 * THEME_*_LIGHT pair to a colour. Arguments in that order (dark first),
 * matching the pick_u32 sites it replaces. */
static lv_color_t mode_hex(const asgard_theme_t *t, uint32_t dark_hex,
                           uint32_t light_hex) {
  return lv_color_hex(pick_u32(t->dark, dark_hex, light_hex));
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
                 mode_hex(t, THEME_SURFACE1_DARK, THEME_SURFACE1_LIGHT)));
  lv_style_set_border_color(
      &s->panel, pick_color(v,
                            pick_color(t->dark, lv_color_hex(0x2f3237),
                                       lv_palette_lighten(LV_PALETTE_GREY, 2)),
                            mode_hex(t, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT)));
  lv_style_set_border_width(&s->panel,
                            pick_i32(v, dpx(t->dpi, 2), THEME_BORDER_W));
  lv_style_set_text_color(
      &s->panel,
      pick_color(v,
                 pick_color(t->dark, lv_palette_lighten(LV_PALETTE_GREY, 5),
                            lv_palette_darken(LV_PALETTE_GREY, 4)),
                 mode_hex(t, THEME_FG0_DARK, THEME_FG0_LIGHT)));
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
    lv_style_set_border_color(&s->control_rad,
                              mode_hex(t, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT));
    lv_style_set_border_width(&s->control_rad, THEME_BORDER_W);
  }
  /* button geometry */
  style_reset(&s->btn, inited);
  /* Ink on the accent fill — THE OPPOSITE POLE OF fg-0, which looks like a bug
   * and is the point. The accent fill inverts against its surface (light fill
   * in DARK mode, dark fill in LIGHT), so the ink has to invert with it: a
   * dark-mode accent button is a light chip and wants dark glyphs. fg-0's
   * light value IS that dark ink, so the pair is taken from the existing token
   * rather than inventing a second one.
   *
   * Stock cannot do this for us — lv_theme_default_init takes no accent-text
   * parameter and couples the glyph colour to the fill — and the `accent-text`
   * token does not reach here at all (absent from the token->C projection; see
   * tokens.edn). Without this the fill moved and the ink did not, which is the
   * exact half-fix docs/UI-QUALITY-CONTRACTS.md §6.8 warns buys a contrast
   * LOSS. Measured on the pair: 6.39:1 dark / 6.79:1 light against §6.2's
   * governing 6:1, up from 4.68:1 mode-invariant. */
  if (!v)
    lv_style_set_text_color(&s->btn,
                            mode_hex(t, THEME_FG0_LIGHT, THEME_FG0_DARK));
  /* THE SAME INK, AS A REUSABLE STYLE — because `s->btn` is not the only place
   * stock pairs color_primary with white. lv_theme_default's bg_color_primary
   * applies that pair at many sites, and fixing only the button is a half-fix
   * that reads as complete: the button measures 6.39:1 while the spinbox's
   * edit CURSOR still drew stock white on the light dark-mode accent, which
   * measures 2.69:1. (A CHECKED buttonmatrix item is NOT that pair — asgard
   * overrides its fill with `checked_accent`, the cyan affordance, so it is
   * white-on-cyan at 5.36:1, the residual §6.9 already records; and vanilla
   * never sees the accent at all. 2.69:1 is the spinbox cursor alone.) That
   * regression is INVISIBLE
   * to a golden (the hash moves either way, and a moved hash was the intended
   * outcome) and invisible to the palette census (white and the accent are both
   * declared tokens), so it is named here rather than left to be re-found. */
  style_reset(&s->accent_ink, inited);
  if (!v)
    lv_style_set_text_color(&s->accent_ink,
                            mode_hex(t, THEME_FG0_LIGHT, THEME_FG0_DARK));
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
  /* MAIN draws NOTHING (asgard-only) — which is what makes the gap above
   * actually show "the container". Stock fills MAIN, so what appeared between
   * the keys was stock's own panel tone, not the container: measured #FFFFFF
   * in light against the surface-2 keys, and #282B30 in dark, the same pixel
   * count in both families because neither took a token. Transparent rather
   * than surface-1 on purpose: the buttonmatrix then reads correctly on
   * whatever surface it is placed on, instead of being right only on a card. */
  if (!v)
    lv_style_set_bg_opa(&s->btnm_pads, LV_OPA_TRANSP);
  /* LOAD-BEARING roller geometry, not cosmetic breathing room. The selected
   * row is redrawn into W - pad_left - pad_right - 2*border_width pixels, and
   * lv_draw_label cancels below 1px. At the 48px small card, Asgard's 8px pads
   * and 1px border leave 30px; stock large-tier 24px pads + 2px border leave
   * -4px, so the selected glyph vanishes. Do not increase this casually. */
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
    lv_style_set_border_color(&s->table_grid,
                              mode_hex(t, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT));
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
    lv_style_set_bg_color(&s->scrollbar,
                          mode_hex(t, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT));
    lv_style_set_bg_opa(&s->scrollbar, LV_OPA_COVER);
  }
  /* closed-field surface — asgard-only: the closed dropdown kept stock's
   * pure-white fill, a foreign bright patch against the light card
   * (measured (255,255,255) vs THEME_SURFACE1_LIGHT). Author the field's
   * surface to the panel tone; the control's own border + radius keep it
   * legible as a field. */
  style_reset(&s->field_bg, inited);
  if (!v)
    lv_style_set_bg_color(
        &s->field_bg, mode_hex(t, THEME_SURFACE1_DARK, THEME_SURFACE1_LIGHT));
  /* buttonmatrix ITEMS fill — asgard-only: container-vs-item contrast
   * measured ~1.0-1.1:1 (items were indistinguishable from the container
   * they sit on); the surface-2 tone gives each key a real fill against
   * the surface-1 card, and the wider gap shows the boundary. */
  style_reset(&s->btnm_items, inited);
  if (!v) {
    lv_style_set_bg_color(
        &s->btnm_items, mode_hex(t, THEME_SURFACE2_DARK, THEME_SURFACE2_LIGHT));
    /* Key GLYPHS take fg-0 for the same reason the fill takes surface-2: the
     * fill was themed and the ink was not, so the ink stayed stock's own
     * color_text (#FAFAFA dark / #212121 light — the greys lv_palette hands
     * lv_theme_default). Both are legible, so this is token conformance
     * rather than a contrast repair: a themed family should not paint values
     * its own catalogue does not declare. */
    lv_style_set_text_color(&s->btnm_items,
                            mode_hex(t, THEME_FG0_DARK, THEME_FG0_LIGHT));
  }
  /* focus ring */
  style_reset(&s->focus, inited);
  if (v) {
    lv_style_set_outline_color(&s->focus, t->base.color_primary);
    lv_style_set_outline_width(&s->focus, dpx(t->dpi, 3));
    lv_style_set_outline_pad(&s->focus, dpx(t->dpi, 3));
    lv_style_set_outline_opa(&s->focus, LV_OPA_50);
  } else {
    lv_style_set_outline_color(&s->focus, mode_hex(t, THEME_FOCUSED_EDGE_DARK,
                                                   THEME_FOCUSED_EDGE_LIGHT));
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
   * stock's violet color_primary: the checkbox/switch indicator, the bar
   * indicator, the dropdown-list selected option, the buttonmatrix checked
   * item. bg only — stock's cover opa + white selected-text stand, so the
   * checkmark/label rides white-on-cyan (the token proves >=4.5:1 both
   * modes). Vanilla stays empty so vanilla-equals-stock holds by scope.
   *
   * THE ROLLER BAND IS NO LONGER ONE OF ITS CONSUMERS — see `roller_sel`
   * below for why it had to leave, and why it left ALONE rather than
   * dragging this token's other five call sites with it. */
  style_reset(&s->checked_accent, inited);
  if (!v) {
    lv_style_set_bg_color(&s->checked_accent,
                          mode_hex(t, THEME_CHECKED_DARK, THEME_CHECKED_LIGHT));
    lv_style_set_bg_opa(&s->checked_accent, LV_OPA_COVER);
  }
  /* ── the roller's SELECTED band, both states ────────────────────────────
   * ONE RULE, TWO STATES: the band is the field's fill and a legible glyph
   * tone SWAPPED, so the selected row reads as an inversion of the rows
   * around it rather than as a third colour introduced on top of them. The
   * ENABLED band inverts the enabled field (surface-1); the DISABLED band
   * inverts the pair `disabled_fill` drains the whole widget to (surface-2 +
   * disabled-fg).
   *
   * WHAT THIS FIXES, and it is two defects at once. (1) A DISABLED roller
   * had NO selection cue: `disabled_fill` was applied to LV_PART_SELECTED as
   * well as to MAIN, so band and field took the SAME surface-2 fill and the
   * band stopped existing. Measured on this corpus before the change —
   * `lv_roller/disabled/medium/mid` rendered ONE (fill, glyph) pair,
   * #1E1E2E/#9A9BB6 dark and #D0D0C0/#3D3C2C light, on every inked scanline,
   * so POSITION was the only remaining signal. (2) The ENABLED band's glyph
   * failed the governing 6:1: stock's `bg_color_primary` sets bg AND
   * text_color=white together, asgard replaced only the fill, and the leaked
   * white measured 5.36:1 on checked-accent in BOTH modes.
   *
   * THE ASSIGNMENT IS FORCED, not chosen. Over the CLOSED token table
   * (generated/theme_tokens.h) exactly four ordered (fill, glyph) pairs clear
   * a 3:1 band-vs-field floor AND a 6:1 glyph-on-band floor in BOTH modes:
   * fill fg-0 with surface-1 or surface-2 glyphs, and fill disabled-fg with
   * the same two. `tools/devcards/dev/token_band_search.py` prints the whole
   * 49-row grid, so the four survivors are auditable rather than asserted.
   * The DISABLED band must be the WEAKER of the two available fills and the
   * ENABLED band the stronger, because an inert state that out-shouts its
   * live counterpart inverts the salience the state exists to signal — which
   * leaves disabled-fg for DISABLED and fg-0 for ENABLED, and no freedom
   * anywhere in that sentence. Measured against each state's own field:
   * ENABLED 15.22:1 dark / 12.91:1 light, DISABLED 6.04:1 / 7.16:1. Enabled
   * out-separates disabled in both modes, and the disabled band is visible
   * as a band again.
   *
   * WHY THE ROLLER LEAVES `checked_accent` INSTEAD OF MOVING IT. The band
   * needed a fill above 6.04:1 against surface-1 and fg-0 is the only one in
   * the table, so "raise the shared token" means setting `checked_accent` to
   * fg-0 — near-white on dark, near-black on light. That token is also the
   * checkbox and switch CHECKED indicator, the bar INDICATOR, the
   * dropdown-list selected option and the buttonmatrix checked item, and it
   * is THEME_CHECKED, the same value `edited_edge` takes.
   * Raising it would delete the theme's state/value hue on five other
   * surfaces to fix a contrast problem only the roller has — its band is the
   * one of the six that sits on a field surface. So the roller takes its own
   * style and every other checked surface is byte-identical to before.
   *
   * DISABLED STILL MEANS ONE THING, which was the whole defence of the arm
   * this replaces. A disabled roller still renders exactly the two tokens it
   * rendered before — surface-2 and disabled-fg, the pair every other
   * text-bearing disabled control takes — and nothing else. Only which of
   * the two is fill and which is ink changes, and only inside the band.
   *
   * Vanilla and stock stay empty, so their disabled roller card stays
   * byte-identical to its enabled twin: stock's roller arm adds no style on
   * any disabled selector, and that differential is the reference a shipped
   * gate needs to reproduce upstream. */
  style_reset(&s->roller_sel, inited);
  if (!v) {
    lv_style_set_bg_color(&s->roller_sel,
                          mode_hex(t, THEME_FG0_DARK, THEME_FG0_LIGHT));
    lv_style_set_bg_opa(&s->roller_sel, LV_OPA_COVER);
    /* NOT optional, and not symmetry: without it the band keeps stock's
     * white text_color, which measures 1.22:1 on the dark fg-0 fill. The
     * fill and the glyph have to move together on this part. */
    lv_style_set_text_color(
        &s->roller_sel, mode_hex(t, THEME_SURFACE1_DARK, THEME_SURFACE1_LIGHT));
  }
  style_reset(&s->roller_sel_dis, inited);
  if (!v) {
    lv_style_set_bg_color(
        &s->roller_sel_dis,
        mode_hex(t, THEME_DISABLED_FG_DARK, THEME_DISABLED_FG_LIGHT));
    lv_style_set_bg_opa(&s->roller_sel_dis, LV_OPA_COVER);
    lv_style_set_text_color(&s->roller_sel_dis, mode_hex(t, THEME_SURFACE2_DARK,
                                                         THEME_SURFACE2_LIGHT));
    /* Carried over from the `disabled_fill` this selector used to take, and
     * NOT as a no-op copied for luck: `normal_apply_layer_recolor`
     * (lv_obj_draw.c) folds the LAYER recolor and then, for any part that is
     * not MAIN, applies that PART's own recolor on top — so a recolor
     * reaching LV_PART_SELECTED would re-composite this band's fill and its
     * glyphs toward each other, which is exactly the mechanism
     * UI-QUALITY-CONTRACTS.md §6 bans on a text-bearing surface. */
    lv_style_set_recolor_opa(&s->roller_sel_dis, LV_OPA_TRANSP);
  }
  /* edited-state ring — asgard-only. The SAME cyan as an OUTLINE for the
   * encoder-edit state (slider/bar/roller/spinbox/textarea), replacing stock's
   * red color_secondary edited outline (red collides semantically with
   * :status-error AND measured under the light-surface boundary floor).
   * Vanilla stays empty so vanilla-equals-stock holds by scope.
   *
   * THE RING IS WIDER THAN THE FOCUS RING, AND THAT WIDTH IS THE ROLE
   * DISTINCTION — it is not decoration. This geometry used to MIRROR the focus
   * ring exactly (same width, same pad, differing only in tone), which left
   * COLOUR as the sole discriminator between focused and edited. That was
   * survivable only while the two tones differed, and it stopped being
   * survivable when :focused-edge's light rung had to move onto :cyan-dim to
   * clear the 3:1 non-text floor — the tone the edited ring already paints.
   * Separating on width instead holds in BOTH modes, survives greyscale and
   * colour-blindness, and leaves :checked-accent's deliberate mode-invariance
   * unspent.
   *
   * THE PAD DELIBERATELY DOES NOT GROW WITH IT. An outline's corner radius
   * grows with its PAD, and the focus ring records why that matters: a 2px pad
   * swept a visibly rounded cap around the crisp 2px-radius controls. Widening
   * the stroke outward carries the whole distinction without reintroducing
   * that artefact, so both rings keep the same 1px standoff and the same
   * near-crisp corner. */
  style_reset(&s->edited_edge, inited);
  if (!v) {
    lv_style_set_outline_color(
        &s->edited_edge, mode_hex(t, THEME_CHECKED_DARK, THEME_CHECKED_LIGHT));
    lv_style_set_outline_width(&s->edited_edge, THEME_EDITED_W);
    lv_style_set_outline_pad(&s->edited_edge, THEME_BORDER_W);
    lv_style_set_outline_opa(&s->edited_edge, LV_OPA_COVER);
  }
  /* DISABLED — asgard-only NEW state coverage (stock has none for the value
   * widgets); vanilla stays empty so stock-parity holds.
   *
   * THE RULE THIS SPLIT ENFORCES: no whole-widget opacity, and no
   * whole-widget recolor, on any subtree containing TEXT. Disabled is a
   * TOKEN-PAIR SWAP there, never a fade.
   *
   * THE RULE IS NOT THIS THEME'S — it is docs/UI-QUALITY-CONTRACTS.md §6,
   * which every consumer inherits, and devcards.opa is its deterministic
   * clause. This comment records how THIS theme obeys it; §6 records why,
   * what a consumer owes for a widget we have never seen, and (§6.6) the
   * adjacent SCRIM question it deliberately leaves open.
   *
   * WHY BOTH, when only opacity is usually named. `lv_obj_refr` folds a MAIN
   * `opa` into `layer->opa` AND a MAIN `recolor` into `layer->recolor`, and
   * lv_obj_draw.c then applies the layer recolor to bg, border, outline,
   * shadow AND text colour alike. So the two mechanisms fail the SAME way:
   * both re-composite the glyph and its fill, and both move the two ends
   * TOWARD EACH OTHER. Measured on this corpus before the split — disabled
   * tabview (opa) 3.76:1 dark / 2.76:1 light against 17.21 / 15.76 enabled;
   * disabled textarea (recolor, no opa) 2.33:1 dark / 3.83:1 light. The
   * recolor arm was the one that had been treated as the SAFE alternative to
   * the fade, and it measured worse than the fade it replaced.
   *
   * WHAT THAT BUYS beyond the contrast: with neither mechanism live, the
   * AUTHORED pair IS the RENDERED pair, so a token-level contrast gate can
   * mean something. Under a fade it cannot — the rendered colours appear in
   * no token table at all.
   *
   * "NEITHER MECHANISM LIVE" IS A SCOPE, NOT A DESCRIPTION OF THIS THEME.
   * It holds for the text-bearing `disabled` style below, precisely because
   * that style pins recolor_opa TRANSP. It does NOT hold theme-wide: `hover`,
   * `pressed` and `disabled_dim` all recolor, and 91 non-token drawn colours
   * on the shipped corpus come from exactly those. Do not lift this sentence
   * out of the disabled-text context — a gate armed on the unscoped reading
   * would condemn disabled_dim, which UI-QUALITY-CONTRACTS.md §6 prescribes.
   *
   * - `disabled` (text only): the text-bearing widgets that have NO fill of
   *   their own to swap — a standalone label, the checkbox's label on MAIN.
   *   recolor_opa TRANSP is load-bearing, not tidiness: the STOCK parent
   *   styles DISABLED as a grey recolor at LV_OPA_50 (lv_theme_default.c),
   *   which is the very mechanism this rule bans, and the only way to
   *   decline an inherited style property is to override it.
   *
   *   ITS REACH IS THE OBJECT, NOT THE SUBTREE, and that limit is why
   *   `disabled_fill` exists for everything else. text_color is inheritable,
   *   but `get_selector_style_prop`'s walk stops at the FIRST ancestor that
   *   sets it, so this only ever colours glyphs drawn by the object itself
   *   or by descendants nothing else has styled. Measured the hard way: the
   *   tabview root took this style and the corpus card came back
   *   BYTE-IDENTICAL to its enabled twin — stock's `btn` sets text_color on
   *   the tab buttons and asgard's own `panel` sets it on the page-content
   *   wrappers, so between them every label in that subtree is claimed.
   * - `disabled_dim` (opa + recolor + text): TEXT-FREE geometry only —
   *   slider, arc, bar, led, and the checkbox INDICATOR part. NOT the switch:
   *   its value is knob-vs-track contrast, which the fade collapses, so it
   *   takes `disabled_track`/`disabled_knob` instead (see their init). Here
   *   the fade is the right signal: the critical content is a shape, there
   *   is no glyph self-contrast to collapse, and opacity alone blends toward
   *   whatever sits BEHIND the part (adequate on dark, collapsing on light)
   *   so the draw is also pulled toward the authored disabled-fg tone.
   *   ITS PRECONDITION IS THE EMPTY SUBTREE, NOT THE CLASS LIST. Attach a
   *   label to any of those six and the hazard is live again — the previous
   *   version of this comment reasoned correctly and then listed four
   *   glyph-bearing classes under it. Nothing HERE catches that, but
   *   devcards.opa now does, and it catches it at the LABEL rather than at
   *   the slider: obj_effective_opa accumulates self -> root, so the fade
   *   declared here is emitted on the attached label's own node.
   * - `disabled_fill` (authored fill + text, recolor OFF): THE PAIR SWAP,
   *   and the default answer for any text-bearing widget that HAS a fill —
   *   the accent button, and every field control (dropdown, roller,
   *   textarea, spinbox) plus the tabview root. The fill drains to surface-2
   *   and the glyphs take the disabled-fg tone, a pair the tokens prove at
   *   6.04:1 dark / 7.16:1 light against the governing 6:1.
   *
   *   MOVING THE FILL IS WHAT MAKES THE STATE VISIBLE AT ALL, which is not
   *   obvious until the text half fails to carry it. Two corpus cards proved
   *   it in one run: a small textarea showing only a PLACEHOLDER has no
   *   glyph whose tone changes between states, and the tabview root cannot
   *   reach its labels at all (see `disabled` above) — both rendered
   *   byte-identical to their enabled twins under a text-only swap. A
   *   disabled control that looks enabled is worse than a faded one: it is
   *   the dead-zone hazard, an affordance that lies about taking a press.
   *   bg_opa COVER is explicit for the same reason — the button inherited
   *   an opaque fill from stock's `btn` and the tabview root does not.
   * - `disabled_flat` (opa + text, NO recolor): the table grid. Same
   *   text-free precondition as `disabled_dim`, and it holds only because
   *   the renderer never sets cell_data, so every cell renders EMPTY (the
   *   corpus lv_table notes record that decode gap). WIRING CELL TEXT
   *   RE-ARMS THE HAZARD HERE and this style must move to `disabled` in the
   *   same change — which devcards.opa-test's `table-carve-out-still-holds`
   *   now enforces from the other end, by reading renderer.c for the call.
   *   Kept separate from `disabled_dim` because the disabled-fg
   *   recolor target is LIGHTER than the dark cell tone, so recoloring
   *   LIGHTENS a disabled dark table (it pops instead of receding). */
  style_reset(&s->disabled, inited);
  if (!v) {
    lv_style_set_text_color(&s->disabled, mode_hex(t, THEME_DISABLED_FG_DARK,
                                                   THEME_DISABLED_FG_LIGHT));
    lv_style_set_recolor_opa(&s->disabled, LV_OPA_TRANSP);
  }
  style_reset(&s->disabled_dim, inited);
  if (!v) {
    lv_style_set_opa(&s->disabled_dim, THEME_DISABLED_OPA);
    lv_style_set_text_color(
        &s->disabled_dim,
        mode_hex(t, THEME_DISABLED_FG_DARK, THEME_DISABLED_FG_LIGHT));
    lv_style_set_recolor(&s->disabled_dim, mode_hex(t, THEME_DISABLED_FG_DARK,
                                                    THEME_DISABLED_FG_LIGHT));
    lv_style_set_recolor_opa(&s->disabled_dim, 100);
  }
  style_reset(&s->disabled_fill, inited);
  if (!v) {
    lv_style_set_bg_color(&s->disabled_fill, mode_hex(t, THEME_SURFACE2_DARK,
                                                      THEME_SURFACE2_LIGHT));
    lv_style_set_bg_opa(&s->disabled_fill, LV_OPA_COVER);
    lv_style_set_text_color(
        &s->disabled_fill,
        mode_hex(t, THEME_DISABLED_FG_DARK, THEME_DISABLED_FG_LIGHT));
    lv_style_set_recolor_opa(&s->disabled_fill, LV_OPA_TRANSP);
  }
  /* THE DISABLED BUTTON'S BOUNDARY, and it is a SEPARATE style on purpose.
   *
   * An `lv_button` here takes `btn`, `btn_shadow` and `focus` — and never
   * `control_rad`, whose six call sites are all FIELD controls. So a button
   * carries no border in any state, and its entire visual edge is its fill.
   * Disabling drains that fill to surface-2, which measures ~1.13:1 dark /
   * 1.17:1 light against the card it sits on: under the 3:1 NON-TEXT floor a
   * boundary is governed by, so the control stops reading as a control at all
   * and reads as static text. The population is large — every `:commit`
   * ("Apply") renders permanently disabled, and every started-gated button
   * renders disabled whenever its device is stopped.
   *
   * WHY NOT A BORDER ON `disabled_fill` ITSELF, which is the obvious edit and
   * is WRONG: that style has six consumers, and the five that are not the
   * button (dropdown, roller, textarea, spinbox, tabview root) already take
   * `control_rad` at state 0, which sets exactly this border. Adding one to
   * `disabled_fill` — applied at LV_STATE_DISABLED — would OVERRIDE the border
   * of five widgets that are already correct, to fix the one that is not.
   *
   * THE TONE IS NOT NEW. `edge-0` at `THEME_BORDER_W` is the pair
   * `control_rad` already carries, and this theme already calls it "the
   * 3:1-floor boundary tone panels already use" — 3.62:1 dark / 3.80:1 light.
   * `track_tone` is the precedent for the SHAPE: an asgard-only style added
   * beside a stock default because the stock answer measured under the floor.
   * Do NOT reach for the 6:1 TEXT floor here; the quality contract names
   * mixing the two as the error its precedence rule exists to prevent.
   *
   * AN OUTLINE, NOT A BORDER, AND THAT IS THE LOAD-BEARING PART. A first
   * version used `border_width` and REGRESSED 20 shipped consumer screens.
   * `lv_obj_get_style_space_left` returns `padding + border_width` whenever
   * that side carries a border, and `lv_obj_get_content_width` subtracts that
   * space — so a border SHRINKS the content box. Every label inside a disabled
   * button lost the border width per side and CLIPPED, while content-sized
   * buttons grew and OVERFLOWED their containers: 244 clipped plus 244
   * overflow findings, exactly paired. `outline_width` appears only in the
   * DRAW path (lv_obj_draw.c) and in no layout or space computation, so it
   * paints the same boundary and moves nothing.
   *
   * The isolated widget cards in this repo could not show it — they carry
   * slack around the button — which is why a consumer pin bump is the proof
   * this repo's charter demands, and not this battery alone. */
  style_reset(&s->disabled_edge, inited);
  if (!v) {
    lv_style_set_outline_color(
        &s->disabled_edge, mode_hex(t, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT));
    lv_style_set_outline_width(&s->disabled_edge, THEME_BORDER_W);
    lv_style_set_outline_opa(&s->disabled_edge, LV_OPA_COVER);
    lv_style_set_outline_pad(&s->disabled_edge, 0);
  }
  style_reset(&s->disabled_flat, inited);
  if (!v) {
    lv_style_set_opa(&s->disabled_flat, THEME_DISABLED_OPA);
    lv_style_set_text_color(
        &s->disabled_flat,
        mode_hex(t, THEME_DISABLED_FG_DARK, THEME_DISABLED_FG_LIGHT));
  }
  /* DISABLED for a control whose VALUE IS knob-vs-track contrast — the switch.
   *
   * `disabled_dim`'s precondition is text-free geometry, and the switch meets
   * it, but the argument underneath it does not reach this case: it says the
   * critical content is a SHAPE with "no glyph self-contrast to collapse".
   * A switch's shape never moves — its ON/OFF reading is carried entirely by
   * the knob standing out from the track, and THAT is a self-contrast, so the
   * fade collapses exactly the channel the state lives in. Measured on the
   * checked card, and READ THE ENABLED FIGURE PER MODE: 5.36:1 enabled in
   * LIGHT against 1.45:1 disabled, where the knob is effectively gone. DARK
   * starts lower, 2.65:1 -> 2.23:1, because the enabled knob falls through to
   * stock's `bg_color_white` — which is `color_card` (0x282b30), not white.
   * The light collapse is the one that motivates this; dark is a smaller
   * loss from an already-poor start, and the pair swap fixes both. A disabled control whose value
   * cannot be read is the failure the disabled_fill comment already names —
   * an affordance that lies about its state.
   *
   * So the switch takes the PAIR SWAP instead of the fade, reusing the exact
   * token pair disabled_fill proves (6.04:1 dark / 7.16:1 light): the track
   * drains to surface-2 and the knob takes the disabled-fg tone. No opa, so
   * nothing folds into layer->opa and the pair keeps its measured contrast. */
  style_reset(&s->disabled_track, inited);
  if (!v) {
    lv_style_set_bg_color(&s->disabled_track, mode_hex(t, THEME_SURFACE2_DARK,
                                                       THEME_SURFACE2_LIGHT));
    lv_style_set_bg_opa(&s->disabled_track, LV_OPA_COVER);
    lv_style_set_recolor_opa(&s->disabled_track, LV_OPA_TRANSP);
  }
  style_reset(&s->disabled_knob, inited);
  if (!v) {
    lv_style_set_bg_color(&s->disabled_knob, mode_hex(t, THEME_DISABLED_FG_DARK,
                                                      THEME_DISABLED_FG_LIGHT));
    lv_style_set_bg_opa(&s->disabled_knob, LV_OPA_COVER);
    lv_style_set_recolor_opa(&s->disabled_knob, LV_OPA_TRANSP);
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
    lv_style_set_arc_color(&s->track_tone,
                           mode_hex(t, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT));
  style_reset(&s->track_bg, inited);
  if (!v) {
    lv_style_set_bg_color(&s->track_bg,
                          mode_hex(t, THEME_EDGE0_DARK, THEME_EDGE0_LIGHT));
    lv_style_set_bg_opa(&s->track_bg, LV_OPA_COVER);
  }
  /* readout arc — the MOVING half of a spinner ring, and the arc twin of
   * `checked_accent` (which the bar's INDICATOR takes). arc_color only, for
   * the same reason `track_tone` carries no bg_opa.
   *
   * IT EXISTS TO CLOSE A LEAK, not to restyle anything: asgard styled the
   * spinner's MAIN and returned, so its INDICATOR fell through to the STOCK
   * parent's `arc_indic_primary` — i.e. to `color_primary`, the call-to-
   * action colour, on a widget the renderer does not even mark interactive.
   * NON-INTERACTIVE READOUTS NEVER TAKE THE ACTION HUE: an operator must be
   * able to read "this is something you press" off the palette, and a
   * spinner and a progress bar are things you WATCH. checked-accent is the
   * theme's existing state/value-indication tone (switch/checkbox checked
   * fill, dropdown-list selected option, buttonmatrix checked item) and 69.9
   * deg off the action hue. It was the ROLLER's selected band too until that
   * band moved to `roller_sel`; the hue argument here is untouched by that,
   * because it turns on checked-accent being a state tone rather than the
   * action hue, and not on which widgets happen to wear it.
   *
   * This comment once deferred the visibility half to a derived-palette task
   * and left a lead to move the track onto surface-2. Both are refuted below,
   * quoted rather than deleted because each was written as a measurement. */
  /* THE TONE IS fg-0, WHICH THIS COMMENT ONCE CALLED IMPOSSIBLE: "NO existing
   * token clears 3:1 against the edge-0 resting track (best measured:
   * focused-edge 2.84 dark / 1.37 light)". That was WRONG WHEN WRITTEN, not
   * overtaken since — against the edge-0 of the commit that landed it, fg-0
   * already cleared 3:1 at 4.21 / 3.40, beating that runner-up by half again,
   * and its figures reproduce exactly, so only the superlative was false.
   * edge-0 has moved
   * once since and flipped no token's verdict — it made fg-0 WORSE, down to
   * the 3.82 / 3.08 relied on here. So do not read this as a palette move
   * rescuing a once-true claim: the fix was available the whole time, and
   * saying otherwise is what kept it from being taken. A contrast claim is a
   * claim about a PAIR; recompute it rather than inheriting it.
   *
   * Against the CURRENT edge-0, FOUR tokens clear 3:1 in both modes — the
   * three surfaces and fg-0 — and fg-0 is the only FOREGROUND among them,
   * which is what a value drawn on a track has to be. The RELATIONSHIP once
   * recorded as failing, the arm against its own ring at 1.04 / 1.06, now
   * clears; the TOKEN PAIR named there still does not (checked vs edge-0 is
   * 1.15 / 1.04) and is not claimed to.
   *
   * THE HUE ARGUMENT IS UNCHANGED, so this is no regression to the action
   * tone: a readout wears a different NON-action tone, not the call-to-action
   * one. checked-accent means "switched on" and a heading is not a state;
   * fg-0 means "this is information". Legibility and semantics agree.
   *
   * THE SURFACE-2 LEAD IS REFUTED. It really does put the indicator/track
   * pair at 3.06 / 3.44 — and it is silent on the track's OTHER neighbour,
   * where it fails: surface-2 against the panel is 1.13 / 1.17, where edge-0
   * is 3.99 / 4.20. It would trade an invisible value for an invisible ring.
   * A track has two neighbours; a fix that measures one is half a
   * measurement. */
  style_reset(&s->readout_arc, inited);
  if (!v)
    lv_style_set_arc_color(&s->readout_arc,
                           mode_hex(t, THEME_FG0_DARK, THEME_FG0_LIGHT));
  /* readout arc MARK — the AFFORDANCE half of the readout argument above, and
   * independent of it: recolouring a knob still draws a knob, and a grab
   * handle on an arc nothing can move is an invitation the widget cannot
   * honour. An arc is the one ring widget where the distinction is invisible
   * otherwise — a spinner is marked non-interactive by the renderer and a bar
   * has no knob part.
   *
   * SHRUNK TO A POINTER, NOT WITHDRAWN — the first version of this style DID
   * withdraw it, which was measured wrong. `pad_all 0` does the work: an arc
   * knob is sized by its pad, so zero pad leaves a mark exactly the ring's
   * thickness, reading as the tip of the value rather than as something to
   * grab. Removing the draw entirely reads as nothing at all.
   *
   * WHY WITHDRAWING IT WAS WRONG: at a ring's FLOOR the indicator has zero
   * angular extent, so the knob is the only mark there is, and a full-circle
   * 0..360 track has no ends to supply a baseline the way a bar does.
   * Withdrawn, a ring reading zero and undriven chrome render identically —
   * measured on the corpus at the time, 550 accent pixels went to 0 at min.
   * The floor is minted deliberately, so this is rendered rather than
   * hypothetical; read which cards from the lv_arc entries in
   * tools/devcards/corpus/spec.edn.
   *
   * IT IS NOT A DIMMED CONTROL EITHER: a dimmed knob reads as DISABLED, which
   * `disabled_dim` already owns here, and a readout is not a disabled input.
   * The mark takes fg-0, the tone the indicator takes, so above the floor it
   * is continuous with the arc it terminates.
   *
   * The state is LV_STATE_USER_2, DERIVED by the renderer from whether the
   * node carries an event binding. Derived is not unspellable: `states` is a
   * raw lv_state_t applied verbatim and this bit is inside its range, so a
   * screen CAN author it on an arc carrying a command. What makes the binding
   * authoritative is that renderer.c CLEARS the bit on the evented arm — and
   * that clearing is skipped under morph, whose payload carries no binding. */
  style_reset(&s->readout_knob, inited);
  if (!v) {
    lv_style_set_bg_color(&s->readout_knob,
                          mode_hex(t, THEME_FG0_DARK, THEME_FG0_LIGHT));
    lv_style_set_bg_opa(&s->readout_knob, LV_OPA_COVER);
    lv_style_set_pad_all(&s->readout_knob, 0);
  }
  /* ...and WITHDRAWN AGAIN once that readout is DISABLED — found by review
   * rather than by reasoning. `disabled_dim` fades through layer opa plus a
   * recolor toward disabled-fg, and measured on the disabled cards that does
   * NOT land the mark and the indicator in the same place: the mark came out
   * near disabled-fg while the indicator went far darker, so the two stopped
   * being one continuous shape and the mark reappeared as a separate blob —
   * precisely the grab-handle reading this style exists to remove, and WORSE
   * when disabled than when live, because a dimmed track and a dimmed
   * indicator converge while the mark does not.
   *
   * Withdrawing it there is the semantic answer rather than a patch for that
   * blend: the mark gives a LIVE readout a floor value to read, and a disabled
   * widget asserts it has none, so there is nothing to mark.
   *
   * IT WINS BY BITMASK VALUE, not specificity — LVGL has no such rule.
   * `get_prop_core` ranks by `weight = state_act`, so USER_2|DISABLED at 8704
   * beats the plain 8192 above; CHECKED|DISABLED at 516 would LOSE to it. */
  style_reset(&s->readout_knob_off, inited);
  if (!v) {
    lv_style_set_bg_opa(&s->readout_knob_off, LV_OPA_TRANSP);
    lv_style_set_pad_all(&s->readout_knob_off, 0);
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
  /* tabview surfaces — COLOR ONLY, asgard only. The bar is chrome and takes
   * the elevated tier; the content/pages are the base tier the surface-1
   * panels sit on, so the two read as distinct without either matching a
   * panel. Nothing here sets radius, padding or border width, which is what
   * keeps the frozen tabview geometry frozen. */
  style_reset(&s->tab_bar_bg, inited);
  if (!v) {
    lv_style_set_bg_color(
        &s->tab_bar_bg, mode_hex(t, THEME_SURFACE2_DARK, THEME_SURFACE2_LIGHT));
    lv_style_set_bg_opa(&s->tab_bar_bg, LV_OPA_COVER);
  }
  style_reset(&s->tab_page_bg, inited);
  if (!v) {
    lv_style_set_bg_color(&s->tab_page_bg, mode_hex(t, THEME_SURFACE0_DARK,
                                                    THEME_SURFACE0_LIGHT));
    lv_style_set_bg_opa(&s->tab_page_bg, LV_OPA_COVER);
  }
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
    lv_style_set_text_color(&s->cursor_off, mode_hex(t, THEME_DISABLED_FG_DARK,
                                                     THEME_DISABLED_FG_LIGHT));
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
    if (lv_obj_check_type(parent, &lv_tabview_class)) {
      /* Tab BAR (child 0) only — stock GEOMETRY, asgard FILL. The CONTENT
       * (child 1) and the pages deliberately keep taking nothing: they are
       * transparent, so what shows through them is the tabview ROOT's fill,
       * which is where both the base tone and the DISABLED state now live.
       *
       * FILLING THEM WAS THE FIRST ATTEMPT AND IT REGRESSED THE DISABLED
       * STATE. LV_STATE_DISABLED does not propagate to children, so an
       * opaque child cannot react to a disabled ROOT — it just covers the
       * root's disabled fill, and the disabled card came back byte-identical
       * to its enabled twin. The distinctness lane caught it; re-declaring
       * disabled_fill on the children did NOT help, for the same reason. */
      if (t->family == ASGARD_THEME_FAMILY_ASGARD &&
          lv_obj_get_child(parent, 0) == obj)
        lv_obj_add_style(obj, &t->styles.tab_bar_bg, 0);
      return;
    }
    if (lv_obj_get_parent(parent) != NULL &&
        lv_obj_check_type(lv_obj_get_parent(parent), &lv_tabview_class))
      return; /* tab pages — transparent, see the bar guard above */
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
     * disabled-fg tone, a pair the tokens prove at 6.04:1 dark / 7.16:1
     * light against the governing 6:1. Both generic formulas fail here: opa
     * halves the label-vs-fill delta, and a recolor drags label and fill
     * toward the same target. THIS ARM GOT THE ANSWER RIGHT FIRST — it is
     * the swap the rest of the disabled system was generalised from.
     *
     * `disabled_edge` RIDES BESIDE IT, and only here. The pair swap fixes the
     * INK against the FILL; it leaves the fill itself at ~1.13:1 against the
     * card, and a button has no edge to carry the boundary because it never
     * takes `control_rad`. See the disabled_edge init comment for why it is a
     * separate style, and why it is an OUTLINE rather than a border. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.disabled_fill, LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.disabled_edge, LV_STATE_DISABLED);
    }
    /* CHECKED — INHERITED FROM STOCK, and the determination is OVERSIGHT,
     * not a semantic. NOTHING IS ADDED FOR IT HERE YET; this is the
     * decision, written down, because the silence was the defect. Four
     * sibling selectors declare their checked hue (`checked_accent` at
     * switch and checkbox INDICATOR|CHECKED, dropdown-list SELECTED|CHECKED,
     * buttonmatrix ITEMS|CHECKED) and a reader of THIS arm could not tell an
     * inherited colour from a chosen one.
     *
     * WHAT IS INHERITED, and by what mechanism. Nothing this arm adds
     * carries LV_STATE_CHECKED, so `get_prop_core` (core/lv_obj_style.c)
     * resolves bg_color AND text_color from stock's `bg_color_secondary` —
     * an EXACT state match at weight 4, which beats every weight-0 style
     * this arm adds. That style paints `color_secondary`, and main.c passes
     * lv_palette_main(LV_PALETTE_RED) for EVERY family, asgard included: a
     * #F44336 fill under a white label. Sampled on the committed gallery,
     * the checked_large sheets under
     * tools/devcards/docs/widgets/WIDGET_BUTTON: #F44236 after JPEG
     * quantisation in asgard-dark, asgard-light AND vanilla alike. It is the
     * ONE button state whose fill is family-invariant — the same sheets put
     * default at #7B3AEC asgard vs #2196F3 vanilla, disabled at #1F1E2E vs
     * #417BA9 — because this selector overrides even the accent main.c bakes
     * into the stock parent's color_primary. Geometry still differs; it is
     * the COLOUR that asgard contributes nothing to here.
     *
     * WHY OVERSIGHT AND NOT INTENT — four pieces, none of them taste.
     * (1) The commit that created the checked hue (`feat(theme): cyan
     *     checked/edited affordance + named media-blue token`) enumerated
     *     the checked surfaces as the ones whose fill was "the brand-violet
     *     color_primary". THIS one's never was — it was already the red —
     *     so it fell outside that enumeration's own premise rather than
     *     being weighed and kept.
     * (2) The same commit stripped this exact red off every EDITED surface
     *     for a reason that applies here verbatim: it "collides semantically
     *     with :status-error". Re-derived — tokens.edn resolves
     *     :status-error to :red #EF4444, this fill is #F44336: 4.1 deg apart
     *     in hue, 1.02:1 in luminance. A checked toggle wears the theme's
     *     error tone. Nothing about a toggle being ON is an error.
     * (3) The same commit rejected a candidate accent for measuring 3.682:1
     *     white-on-fill, and recorded the red it was displacing elsewhere at
     *     2.77:1 on the light surface. This surface ships at BOTH of those
     *     numbers at once: white-on-fill 3.68:1, under WCAG's 4.5:1 text
     *     floor let alone §6.2's 6:1; and fill-vs-card 2.77:1 light, under
     *     WCAG 1.4.11's 3:1 boundary floor. Dark passes that one at 5.04:1,
     *     so the boundary defect is light-mode only; the label is not.
     * (4) A theme hue on LV_STATE_CHECKED is UNIVERSAL, so it cannot encode
     *     "destructive" even in principle: ui_ast carries no button role —
     *     `states` and `checked_when` are raw lv_state_t — and a consumer
     *     wanting a danger colour authors `style_groups` on the node.
     *
     * THE CHANGE, EXACTLY, when it is sequenced. Under the asgard gate:
     *     lv_obj_add_style(obj, &t->styles.checked_accent, LV_STATE_CHECKED);
     * It moves pixels on every checked-button card, so it re-mints goldens
     * and gallery and owes the mandatory VLM review — its own commit, not a
     * rider on this one. It is a strict improvement and still NOT the whole
     * fix: fill-vs-card goes 2.77:1 -> 4.03:1 light (over the floor it fails
     * today) and 5.04:1 -> 3.46:1 dark (still over), and the :status-error
     * collision goes; but `checked_accent` sets bg and NOT text, so stock's
     * white text_color keeps winning the exact-state match and the LABEL
     * lands at 5.36:1 — under docs/UI-QUALITY-CONTRACTS.md §6.2's governing
     * 6:1. That is the same leak `roller_sel` was split out to fix and the
     * one §6.9 still records against the dropdown list. Re-derive the
     * checked-accent figures with tools/devcards/dev/token_band_search.py,
     * whose "SHIPPED enabled band" block prints both.
     *
     * AND THE ROLLER'S REPAIR DOES NOT PORT, which is why this waits.
     * Authoring both ends needs a (fill, glyph) pair clearing 3:1 vs the
     * card and 6:1 glyph-on-fill in both modes; over the closed table
     * (generated/theme_tokens.h) the survivors are enumerated by
     * dev/token_band_search.py, which READS that header rather than copying
     * it — run it; a count here would rot exactly as its own hand-copied
     * table did.
     *
     * THE ACCENT FORK IN THIS COMMIT CHANGED THAT ANSWER, and the honest
     * record is that it changed it in the direction that REMOVES this
     * deferral's stated reason. The pre-fork mode-invariant accent gave a
     * hueless survivor set — fg-0 or disabled-fg under surface glyphs, the
     * roller band's inversion tones, with disabled-fg already spoken for as
     * the DISABLED ink — so the argument was that no token in the closed
     * table gives a checked BUTTON a state HUE clearing 6:1. The forked
     * accent now DOES survive, against surface-0 and surface-1 glyphs.
     *
     * So the wall is gone on both halves: the DEFAULT label, which now takes
     * the inverted fg-0 pole at 6.39:1, and the CHECKED hue, which now has a
     * candidate. This change does NOT take that repair — it is a distinct
     * behavioural change to a state the corpus does not yet cover, and
     * bundling it here would land it unproven. It is deferred on SCOPE now,
     * not on impossibility, and that distinction is the whole point of
     * rewriting this note rather than leaving a stale argument standing. */
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SLIDER
  if (lv_obj_check_type(obj, &lv_slider_class)) {
    lv_obj_add_style(obj, &t->styles.knob, LV_PART_KNOB);
    lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      /* DISABLED — the FADE variant: a slider's content is a track, a fill
       * and a knob, with no glyph anywhere in its subtree, so the opa is the
       * signal and there is no text self-contrast for it to collapse. That
       * emptiness is the precondition (see the disabled_dim init comment);
       * a value label parented INTO a slider would void it. */
      lv_obj_add_style(obj, &t->styles.disabled_dim, LV_STATE_DISABLED);
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
      /* DISABLED — the PAIR SWAP, NOT the fade the slider arm takes. Both are
       * text-free geometry, but only the switch carries its VALUE in
       * knob-vs-track contrast, and folding MAIN's opa into layer->opa fades
       * knob and track together until that contrast is gone: measured 5.36:1
       * enabled -> 1.45:1 disabled in LIGHT (dark starts at 2.65:1, not
       * 5.36 — see the disabled_track init). See the disabled_track init for
       * the full argument and the numbers. The track and the knob are styled
       * as an explicit pair instead, so the state stays readable while still
       * reading as disabled. */
      lv_obj_add_style(obj, &t->styles.disabled_track,
                       LV_PART_MAIN | LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.disabled_track,
                       LV_PART_INDICATOR | LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.disabled_knob,
                       LV_PART_KNOB | LV_STATE_DISABLED);
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
      /* DISABLED — the FADE variant: an arc is two rings and a knob, no
       * glyph (see the disabled_dim init comment for the precondition). */
      lv_obj_add_style(obj, &t->styles.disabled_dim, LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.pressed, LV_STATE_PRESSED);
      lv_obj_add_style(obj, &t->styles.track_tone, LV_PART_MAIN);
      /* ...and, when nothing is bound to move it, mark it a READOUT on both
       * channels. The renderer sets USER_2 from the node's own event binding,
       * so this arm never has to ask what the screen meant.
       *
       * INDICATOR first: without it the value falls through to the stock
       * parent's `arc_indic_primary`, i.e. to color_primary — the action hue,
       * on a widget you only watch. That is the same leak `readout_arc` was
       * written to close for the spinner, arriving on the widget the argument
       * was actually about. It is scoped to the READOUT state rather than
       * applied unconditionally, because an arc that DOES carry a command is
       * a control and the action hue is correct there.
       *
       * KNOB second: shrunk to a ring-thick pointer tip in the same tone, so
       * a value at the floor still has a mark and no value reads as grabbable. */
      lv_obj_add_style(obj, &t->styles.readout_arc,
                       LV_PART_INDICATOR | LV_STATE_USER_2);
      lv_obj_add_style(obj, &t->styles.readout_knob,
                       LV_PART_KNOB | LV_STATE_USER_2);
      lv_obj_add_style(obj, &t->styles.readout_knob_off,
                       LV_PART_KNOB | LV_STATE_USER_2 | LV_STATE_DISABLED);
    }
    add_interactive(t, obj);
    return;
  }
#endif
#if LV_USE_SPINNER
  if (lv_obj_check_type(obj, &lv_spinner_class)) {
    /* spinner is its own class (exact-type checks miss the arc arm); the
     * resting ring needs the same edge tone. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.track_tone, LV_PART_MAIN);
      /* ...and the MOVING arm, which styling MAIN alone left falling through
       * to stock's color_primary. See the readout_arc init comment: this is
       * the action-hue leak, not a restyle. No DISABLED arm — the renderer
       * marks a spinner non-interactive, so it has no disabled state to
       * express. */
      lv_obj_add_style(obj, &t->styles.readout_arc, LV_PART_INDICATOR);
    }
    return;
  }
#endif
#if LV_USE_BAR
  if (lv_obj_check_type(obj, &lv_bar_class)) {
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      /* DISABLED — the FADE variant: a bar is a track and a fill, no glyph
       * (see the disabled_dim init comment for the precondition). */
      lv_obj_add_style(obj, &t->styles.disabled_dim, LV_STATE_DISABLED);
      /* FOCUS ring — a bar took the EDITED ring below but not this one, so its
       * focus fell through to the stock parent's, which paints color_primary:
       * an off-token violet measured under the 3:1 non-text floor in BOTH
       * modes, on a widget whose eight peers in the asgard focus group all
       * ring cyan.
       *
       * THE REST OF THAT CLASS IS NOW CLOSED IN THIS FILE — the buttonmatrix,
       * table and dropdown arms below take the same rings, so no themed class
       * still falls through to stock's violet focus or red edited outline.
       * This paragraph used to say they were NOT fixed here; that was true
       * when written and is retired rather than left standing.
       *
       * WHAT SURVIVES IS HOW IT HID. It was invisible to every deterministic
       * lane — the goldens were self-consistent and no oracle compares one
       * widget's tone against another's — and surfaced only as a cross-card
       * finding in a VLM review. The buttonmatrix and table halves hid one
       * layer deeper still: the corpus asserted both were INERT in these
       * states, reasoning about LV_PART_ITEMS (where a highlight really does
       * need a click) and silently omitting MAIN, where stock paints
       * unconditionally. A probe card at states-bits 16 is what refuted it. */
      lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
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
      /* The FILLED portion — the same color_primary fallthrough the spinner
       * arm carries, in rect form: styling MAIN and returning left stock's
       * `bg_color_primary` on LV_PART_INDICATOR. A bar is a readout, so it
       * takes the state/value tone rather than the action hue (see the
       * readout_arc init comment, including what this does NOT fix). Unlike
       * the spinner this is a RECT part, so it reuses checked_accent's
       * bg_color+bg_opa directly rather than an arc twin. NOT applied to the
       * slider, which is genuinely interactive and keeps the action hue. */
      lv_obj_add_style(obj, &t->styles.checked_accent, LV_PART_INDICATOR);
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
      /* DISABLED — per part, per content class, and the ONE place both
       * variants meet on one widget. The INDICATOR is a box: no glyph, so it
       * takes the fade. MAIN carries the label, so it takes the swap. The
       * split works because a part opa is NOT a layer opa — lv_obj_refr
       * reads `opa` off LV_PART_MAIN only, so the indicator's fade scales
       * the indicator's own draws and never reaches the label. */
      lv_obj_add_style(obj, &t->styles.disabled_dim,
                       LV_PART_INDICATOR | LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.disabled, LV_STATE_DISABLED);
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
      /* DISABLED — the PAIR SWAP: the closed field shows the SELECTED OPTION
       * as a glyph run, so this subtree carries text and the fade is banned
       * here (see the disabled_fill init comment). field_bg drains from
       * surface-1 to surface-2 and the option tone moves with it. */
      lv_obj_add_style(obj, &t->styles.disabled_fill, LV_STATE_DISABLED);
      /* PRESSED — the documented dark-pressed fix already proven on the
       * arc/roller arms; stock's black recolor is a near-no-op on the dark
       * surface (measured pressed-vs-default delta 3-8/255). */
      lv_obj_add_style(obj, &t->styles.pressed, LV_STATE_PRESSED);
      /* EDITED — the focus ring above was overridden and this was not, so the
       * edited state fell through to stock's outline_secondary: the red ring
       * this file rejects on every other encoder-adjustable control. Cyan,
       * two ladder steps wider than focus, matching the bar/slider/roller
       * arms. */
      lv_obj_add_style(obj, &t->styles.edited_edge, LV_STATE_EDITED);
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
      /* Field surface — same closed-field fill as dropdown/textarea/spinbox.
       * The roller's MAIN was left to stock, which is PURE WHITE in light
       * mode (measured), and the fade was the only thing that had been
       * hiding it: removing the fade puts a stock white panel back on a
       * tactical-olive card. */
      lv_obj_add_style(obj, &t->styles.field_bg, 0);
      /* DISABLED — the PAIR SWAP: a roller is a COLUMN OF GLYPH RUNS and
       * nothing else, so it is the purest case the fade ban exists for (see
       * the disabled_fill init comment). */
      lv_obj_add_style(obj, &t->styles.disabled_fill, LV_STATE_DISABLED);
      lv_obj_add_style(obj, &t->styles.pressed, LV_STATE_PRESSED);
      /* Selected band — the always-visible centred option. fg-0 fill with
       * surface-1 glyphs, over stock's violet-fill/white-text
       * `bg_color_primary`; both ends are authored here because stock sets
       * bg AND text_color together on this part and replacing only the fill
       * is what left the previous arm's glyph at 5.36:1. See the roller_sel
       * init comment for the derivation and for why this band no longer
       * takes `checked_accent`. */
      lv_obj_add_style(obj, &t->styles.roller_sel, LV_PART_SELECTED);
      /* DISABLED on the BAND, and this part needs its own entry because the
       * MAIN swap cannot reach it: bg_color is not inherited across parts,
       * and the entry above is set ON this part — so a roller whose MAIN had
       * drained to surface-2 would still show the selected option at full
       * enabled contrast, the widget announcing itself as live while every
       * other row dimmed. LV_STATE_DISABLED outranks the DEFAULT-state entry
       * above by state weight, so it wins independently of add order.
       *
       * WHAT THIS SELECTOR USED TO CARRY, and why it is worth naming: it was
       * `disabled_fill` itself — the same surface-2 fill the MAIN drains to
       * — so band and field became one colour and the band ceased to exist.
       * That was defended on the grounds that DISABLED must keep meaning ONE
       * thing, and the defence survives here intact: `roller_sel_dis` is
       * built from the SAME two tokens, merely swapped. What does not
       * survive is the claim that POSITION alone is an adequate selection
       * cue — it was the only cue a disabled roller had, on a widget whose
       * entire content is a column of glyph runs.
       *
       * The centring that cue rested on is real and stays measured, because
       * it is what puts the band over the selected option in the first
       * place: the SELECTED band sits at y 39-77 of the roller's own 0-115
       * box and the selected option's glyphs at 52-63, at min, mid AND max
       * (`clojure -M:bindings:roller-bounds`). */
      lv_obj_add_style(obj, &t->styles.roller_sel_dis,
                       LV_PART_SELECTED | LV_STATE_DISABLED);
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
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      /* Field surface — the SAME closed-field fill the dropdown arm takes.
       * Without it a textarea kept STOCK's card fill (measured #282B30 dark /
       * #FFFFFF light), so its text pairs were measured against a colour that
       * appears in no token table: the placeholder and disabled numbers below
       * are only meaningful once the backdrop is an authored token. */
      lv_obj_add_style(obj, &t->styles.field_bg, 0);
      /* PLACEHOLDER — asgard never styled LV_PART_TEXTAREA_PLACEHOLDER, so it
       * fell through to stock's grey (lv_theme_default.c), measured 2.995:1
       * dark / 1.412:1 light against the fill it actually rendered on. A
       * placeholder is TEXT AN OPERATOR MUST READ — it is what says what the
       * field wants — so the 6:1 shall binds it like any other text.
       *
       * disabled-fg is the tone by CONSTRAINT, not by analogy: a placeholder
       * survives both field surfaces (surface-1 enabled, surface-2 once
       * disabled_fill drains it), and disabled-fg is the only muted tone
       * clearing 6:1 on BOTH — fg-1, the obvious "muted text" choice, fails
       * the disabled surface at 5.82:1 dark. The token's NAME lags its role
       * here; the value does not. */
      lv_obj_add_style(obj, &t->styles.disabled, LV_PART_TEXTAREA_PLACEHOLDER);
      /* DISABLED — the PAIR SWAP (see the disabled_fill init comment): the
       * content IS a glyph run, so the authored dim tone replaces the fade
       * AND stock's recolor wash, which is neutralised rather than left
       * under the authored text. Leaving that recolor live is what collapsed
       * this widget to 2.33:1 dark / 3.83:1 light: it lifts the FILL toward
       * the text tone even though it no-ops on the text itself. The FILL
       * half is what a placeholder-only field has instead of a tone change:
       * its one glyph run is the placeholder, which reads the same in both
       * states, so without the drain the small cell was byte-identical to
       * its enabled twin. */
      lv_obj_add_style(obj, &t->styles.disabled_fill, LV_STATE_DISABLED);
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
      /* Field surface — same closed-field fill as dropdown/textarea/roller,
       * and this is the ENABLED half; DISABLED drains further to surface-2
       * via disabled_fill below. Measured necessary, not tidied in: the
       * spinbox had been left on STOCK's card tone (#282B30 dark), and with
       * the fade gone the disabled digits sat at 5.23:1 on it — under the 6:1
       * shall, and under it ONLY because the backdrop was not an authored
       * surface. A tone derived against the surface ladder cannot deliver its
       * number on a backdrop that is not on the ladder. */
      lv_obj_add_style(obj, &t->styles.field_bg, 0);
      /* DISABLED — the SWAP, NOT the fade: the digits are the widget's whole
       * content, and the 50% opa collapsed them to ~1.2:1 on the light fill
       * (see the disabled init comment for why neither an opa exemption nor
       * a token retune can recover that). The recolor that replaced the opa
       * here was measured no better — 2.33:1 dark on the textarea twin — so
       * both are gone and the authored PAIR stands alone. */
      lv_obj_add_style(obj, &t->styles.disabled_fill, LV_STATE_DISABLED);
      /* The ENABLED cursor cell is stock's color_primary fill with stock's
       * WHITE digit on it. That pair was legible while the accent was dark in
       * both modes; it is not now — white on the dark-mode accent measures
       * 2.69:1. The digit under the cursor is the one the operator is editing,
       * so it is the least acceptable place to lose contrast. */
      lv_obj_add_style(obj, &t->styles.accent_ink, LV_PART_CURSOR);
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
    /* FOCUS + EDITED rings. Stock's non-calendar arm paints outline_primary
     * and outline_secondary on MAIN, needing no selected button, so these
     * states were never inert here — the corpus asserted they were, and a
     * probe card at states-bits 16 rendered DIFFERENT from default, which is
     * what retired the claim. Same off-token violet the bar arm measured
     * under the 3:1 non-text floor. Asgard-only, so vanilla keeps stock's
     * outlines and vanilla-equals-stock holds by scope. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
      lv_obj_add_style(obj, &t->styles.edited_edge, LV_STATE_EDITED);
    }
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
    /* DISABLED — asgard-only new-state coverage (stock's table arm styles no
     * disabled state); the opa cascades over the grid + cells. The FLAT
     * variant, not the recolor one: the table's cells carry no fill, and the
     * disabled-fg recolor target is lighter than the dark card tone, so the
     * recolor made a disabled dark table POP instead of recede (see the
     * disabled_flat init comment). Vanilla adds nothing, so
     * vanilla-equals-stock holds by scope.
     *
     * THIS IS THE ONE FADE LEFT OVER A WIDGET THAT WILL EVENTUALLY CARRY
     * TEXT, and it is legal today only because renderer.c never sets
     * cell_data — every cell renders empty, so the subtree the ban protects
     * is a grid of lines, with no glyph self-contrast for a fade to collapse.
     *
     * `disabled_fill` WOULD ALSO WORK HERE and is what every other box-owning
     * widget now uses: table MAIN is left at stock's zeroed bg, so the drain
     * would paint surface-2 and read as distinct on its own. It is not used
     * ONLY to keep this change to the widgets that had a measured defect —
     * a scope line, not a claim that the fade is better. WIRING CELL TEXT
     * REMOVES THE CHOICE: this arm must become `disabled_fill` in the same
     * change, and the corpus's :distinct expectation then rides the fill and
     * the text tone instead of the fade. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.disabled_flat, LV_STATE_DISABLED);
    /* FOCUS + EDITED rings. The corpus omitted these on the ground that cell
     * highlighting needs a real click — true of LV_PART_ITEMS and silent
     * about MAIN, where stock paints outline_primary and outline_secondary
     * unconditionally. A probe card at states-bits 16 rendered DIFFERENT from
     * default, so the omission rested on the wrong part. Asgard-only, so
     * vanilla keeps stock's outlines and vanilla-equals-stock holds by
     * scope. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      lv_obj_add_style(obj, &t->styles.focus, LV_STATE_FOCUS_KEY);
      lv_obj_add_style(obj, &t->styles.edited_edge, LV_STATE_EDITED);
    }
    return;
  }
#endif
#if LV_USE_TABVIEW
  if (lv_obj_check_type(obj, &lv_tabview_class)) {
    /* The tabview ROOT: its bar/content/pages are styled by the obj-arm guards
     * above, so the root itself normally takes nothing. Asgard adds only the
     * DISABLED state. Vanilla adds nothing, so vanilla-equals-stock holds by
     * scope.
     *
     * THE PAIR SWAP, and this is the widget where only the FILL half of it
     * survives. A tabview's whole visible content is tab labels and page
     * labels, so it is squarely inside the ban — it measured the worst damage
     * of any card, 3.76:1 dark / 2.76:1 light against 17.21 / 15.76 enabled.
     *
     * THE TEXT HALF REACHES NOTHING HERE, and that was measured, not
     * reasoned: with a text-only swap on this root the corpus card came back
     * byte-identical to its enabled twin. text_color is inheritable but the
     * walk stops at the first ancestor that sets it, and every label in this
     * subtree already has one — stock's `btn` claims the tab labels, asgard's
     * own `panel` claims the page-content wrappers. So what expresses the
     * state is the root's own background, which is otherwise unpainted and
     * shows wherever the transparent content and pages do not cover it.
     *
     * The label TONES stay at their enabled values, which is a smaller signal
     * than the fade gave and a deliberately better trade: the fade "dimmed"
     * them by destroying their contrast. Recovering the tone half needs
     * LV_STATE_DISABLED propagated from the root to the bar's buttons and the
     * pages, so each can swap in its OWN state — a renderer-side change to
     * how state is applied, which is also why the corpus entry calls
     * root-level disable a WIRE granularity note rather than a theme one. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD) {
      /* Base fill, and the root is the RIGHT owner of it: the content and the
       * pages are transparent, so this is the tone that shows behind them —
       * and putting it here keeps the DISABLED swap below on the same surface,
       * which is what makes the state visible at all. Previously the root
       * painted nothing enabled, so what showed through was stock's own
       * `color_scr` — LIGHT_COLOR_SCR (#F5F5F5) and DARK_COLOR_SCR
       * (0x15171A), neither of which the token catalogue declares. Both
       * families took the same stock value there, so asgard-dark rendered
       * that surface exactly as VANILLA does. */
      lv_obj_add_style(obj, &t->styles.tab_page_bg, 0);
      lv_obj_add_style(obj, &t->styles.disabled_fill, LV_STATE_DISABLED);
    }
    return;
  }
#endif
#if LV_USE_LED
  if (lv_obj_check_type(obj, &lv_led_class)) {
    lv_obj_add_style(obj, &t->styles.led, 0);
    /* DISABLED — the FADE variant: an led is one filled rounded rect with a
     * shadow, no glyph (see the disabled_dim init comment for the
     * precondition). asgard-only new-state coverage (stock's led arm styles
     * only MAIN|DEFAULT). Vanilla adds nothing, so vanilla-equals-stock holds
     * by scope. */
    if (t->family == ASGARD_THEME_FAMILY_ASGARD)
      lv_obj_add_style(obj, &t->styles.disabled_dim, LV_STATE_DISABLED);
    return;
  }
#endif
#if LV_USE_LABEL
  if (lv_obj_check_type(obj, &lv_label_class)) {
    /* DISABLED for standalone labels — NO family styles a plain label's
     * disabled state (stock covers labels only as textarea children), so a
     * label carrying LV_STATE_DISABLED rendered pixel-identical to enabled.
     * The SWAP: a label is nothing but a glyph run, so the authored tone is
     * the whole of its disabled expression; a label without the state bit is
     * untouched. Vanilla adds nothing, so vanilla-equals-stock holds by
     * scope. */
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
