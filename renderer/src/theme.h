/* Asgard child theme — the good-looking global DEFAULT for unstyled widgets.
 *
 * Installed as a CHILD of the stock default theme (lv_theme_set_parent):
 * LVGL applies parent-then-child, so anything this theme does not touch
 * falls through to stock. Values come from generated/theme_tokens.h (the
 * edn/tokens.edn projection). Three families:
 *
 *   ASGARD  (0, the default) — the token-derived look: panel/control radius
 *            tiers, tight control padding, shadow removal, crisp knobs,
 *            hover styling for interactive classes, focus ring, disabled
 *            dim, zero-time state transitions.
 *   VANILLA (1) — the IDEMPOTENCY layer: over the targets it styles (the
 *            asgard-only additions are family-gated OFF) it restates the stock
 *            values (same formulas, same palette calls), so rendering under
 *            VANILLA must be bit-identical to rendering under STOCK. The
 *            per-card family=1 vs family=2 hash-equality gate holds this.
 *   STOCK   (2) — the child apply is skipped entirely; pure parent theme.
 */
#ifndef ASGARD_THEME_H
#define ASGARD_THEME_H
#include "lvgl.h"
typedef enum {
  ASGARD_THEME_FAMILY_ASGARD = 0,
  ASGARD_THEME_FAMILY_VANILLA = 1,
  ASGARD_THEME_FAMILY_STOCK = 2,
} asgard_theme_family_t;
/* Init (or re-init — styles are reset in place, so live widgets restyle on
 * lv_obj_report_style_change) the child theme for `dark` + `family` and
 * return it. `parent` is the stock theme: the child chains to it
 * (lv_theme_set_parent) AND mirrors its base fields (fonts, primary/
 * secondary colors, flags) — the lv_theme_get_* runtime getters read the
 * DISPLAY theme's fields directly with NO parent walk (lv_theme.c), so a
 * child with NULL fonts would feed every runtime theme query garbage.
 * The caller installs the result: lv_display_set_theme(disp, child).
 *
 * SCOPE: the stock-formula mirrors capture the display geometry (dpi, size
 * class) at init time. The stock parent re-derives its own values on a live
 * RESOLUTION_CHANGED; this child does not — vanilla==stock is guaranteed for
 * the init-time geometry (every proof harness re-inits per render). A host
 * resizing across the 320/720 size-class thresholds mid-session must
 * re-init the theme to keep the vanilla mirrors aligned. */
lv_theme_t *asgard_theme_init(lv_display_t *disp, bool dark,
                              asgard_theme_family_t family, lv_theme_t *parent);
/* True only when `recolor` is one of the ASGARD family's live declared
 * recolor signatures (hover, pressed, or disabled_dim).  The draw observer
 * uses this to separate the theme's own intentional composites from stock or
 * authored recolors without copying the style values into another table. */
bool asgard_theme_recolor_is_declared(lv_color32_t recolor);
#endif
/* ASGARD_THEME_H */
