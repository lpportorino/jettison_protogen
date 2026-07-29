/*
 * Execute renderer/src/theme.c as a projection, replacing only its LVGL
 * mutation boundary with recorders.  This is intentionally C, not a parser:
 * the real compiler evaluates every family/mode/size/DPI branch and all token
 * expressions exactly as the renderer source spells them.
 *
 * The build script links the vendored lv_color.c and lv_palette.c, so even the
 * stock GREY values used by the vanilla family come from LVGL rather than a
 * second table here.  Every lv_style_set_* call in theme.c is macro-routed
 * below.  A new setter that is not classified therefore leaves an unresolved
 * lv_style_set_prop at link time and fails generation instead of disappearing.
 */
#include "lvgl/src/core/lv_obj_class_private.h"
#include "lvgl/src/core/lv_obj_private.h"
#include "theme.h"
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define ARRAY_LEN(xs) (sizeof(xs) / sizeof((xs)[0]))
#define MAX_PROPERTIES 256
#define MAX_APPLICATIONS 1024
#define MAX_RESET_STYLES 64
typedef enum {
  CAP_NUM,
  CAP_COLOR,
  CAP_TRANSITION,
} cap_kind_t;
typedef struct {
  const lv_style_t *style;
  const char *name;
  cap_kind_t kind;
  int32_t num;
  uint32_t color;
} cap_prop_t;
typedef struct {
  int family;
  int target;
  const lv_style_t *style;
  uint32_t selector;
  uint32_t attachment_order;
} cap_application_t;
typedef struct {
  int family;
  bool dark;
  int profile;
  cap_prop_t properties[MAX_PROPERTIES];
  size_t property_count;
} cap_variant_t;
static cap_prop_t current_properties[MAX_PROPERTIES];
static size_t current_property_count;
static cap_application_t applications[MAX_APPLICATIONS];
static size_t application_count;
static const lv_style_t *reset_styles[MAX_RESET_STYLES];
static size_t reset_style_count;
static int32_t probe_dpi = 160;
static int32_t probe_width = 800;
static int32_t probe_height = 480;
static int current_family;
static int current_target;
static uint32_t current_attachment_order;
static size_t add_style_calls;
static void die(const char *message) {
  (void)fprintf(stderr, "theme-style-groups: %s\n", message);
  exit(2);
}
/*
 * lv_palette.c's invalid-input branches log.  The emitter never supplies an
 * invalid palette/level, so reaching this stub is a generator bug.
 */
void lv_log_add(lv_log_level_t level, const char *file, int line,
                const char *func, const char *format, ...) {
  (void)level;
  (void)file;
  (void)line;
  (void)func;
  (void)format;
  die("vendored palette rejected a value used by theme.c");
}
static uint32_t color_to_rgb(lv_color_t color) {
  return ((uint32_t)color.red << 16) | ((uint32_t)color.green << 8) |
         color.blue;
}
static void remember_reset_style(const lv_style_t *style) {
  for (size_t i = 0; i < reset_style_count; i++)
    if (reset_styles[i] == style)
      return;
  if (reset_style_count == MAX_RESET_STYLES)
    die("too many reset styles");
  reset_styles[reset_style_count++] = style;
}
static void cap_reset(lv_style_t *style) {
  size_t out = 0;
  remember_reset_style(style);
  for (size_t i = 0; i < current_property_count; i++) {
    if (current_properties[i].style != style)
      current_properties[out++] = current_properties[i];
  }
  current_property_count = out;
}
static cap_prop_t *find_property(const lv_style_t *style, const char *name) {
  for (size_t i = 0; i < current_property_count; i++)
    if (current_properties[i].style == style &&
        strcmp(current_properties[i].name, name) == 0)
      return &current_properties[i];
  return NULL;
}
static cap_prop_t *new_property(const lv_style_t *style, const char *name) {
  if (current_property_count == MAX_PROPERTIES)
    die("too many captured style properties");
  cap_prop_t *property = &current_properties[current_property_count++];
  *property = (cap_prop_t){.style = style, .name = name};
  return property;
}
static void cap_set_num(const lv_style_t *style, const char *name,
                        int32_t value) {
  cap_prop_t *property = find_property(style, name);
  if (property == NULL)
    property = new_property(style, name);
  property->kind = CAP_NUM;
  property->num = value;
}
static void cap_set_color(const lv_style_t *style, const char *name,
                          lv_color_t value) {
  cap_prop_t *property = find_property(style, name);
  if (property == NULL)
    property = new_property(style, name);
  property->kind = CAP_COLOR;
  property->color = color_to_rgb(value);
}
static void cap_set_transition(const lv_style_t *style) {
  cap_prop_t *property = find_property(style, "transition");
  if (property == NULL)
    property = new_property(style, "transition");
  property->kind = CAP_TRANSITION;
}
static cap_application_t *find_application(int family, int target,
                                           const lv_style_t *style,
                                           uint32_t selector) {
  for (size_t i = 0; i < application_count; i++) {
    cap_application_t *application = &applications[i];
    if (application->family == family && application->target == target &&
        application->style == style && application->selector == selector)
      return application;
  }
  return NULL;
}
static bool wildcard_has_application(int family, const lv_style_t *style,
                                     uint32_t selector) {
  return find_application(family, 0, style, selector) != NULL;
}
static void cap_add_style(lv_obj_t *obj, const lv_style_t *style,
                          lv_style_selector_t selector) {
  (void)obj;
  add_style_calls++;
  uint32_t order = current_attachment_order++;
  cap_application_t *existing =
      find_application(current_family, current_target, style, selector);
  if (existing != NULL) {
    /* lv_obj_add_style removes and re-adds a duplicate: retain its last order. */
    existing->attachment_order = order;
    return;
  }
  if (current_target != 0 &&
      wildcard_has_application(current_family, style, selector))
    return;
  if (application_count == MAX_APPLICATIONS)
    die("too many captured style applications");
  applications[application_count++] = (cap_application_t){
      .family = current_family,
      .target = current_target,
      .style = style,
      .selector = selector,
      .attachment_order = order,
  };
}
/*
 * These three read helpers mirror tiny LVGL accessors.  generate.sh guards
 * their source bodies before compiling this file, so an LVGL semantic change
 * cannot leave this projection silently using the old behavior.
 */
static bool cap_check_type(const lv_obj_t *obj, const lv_obj_class_t *class_p) {
  return obj != NULL && obj->class_p == class_p;
}
static lv_obj_t *cap_get_parent(const lv_obj_t *obj) {
  return obj == NULL ? NULL : obj->parent;
}
static lv_obj_t *cap_get_child(const lv_obj_t *obj, int32_t index) {
  if (obj == NULL || obj->spec_attr == NULL || index != 0)
    return NULL;
  if (obj->spec_attr->child_cnt == 0)
    return NULL;
  return obj->spec_attr->children[0];
}
static int32_t cap_display_get_dpi(const lv_display_t *display) {
  (void)display;
  return probe_dpi;
}
static int32_t
cap_display_get_horizontal_resolution(const lv_display_t *display) {
  (void)display;
  return probe_width;
}
static int32_t
cap_display_get_vertical_resolution(const lv_display_t *display) {
  (void)display;
  return probe_height;
}
/*
 * Capture fully-expanded LVGL properties.  Convenience setters such as
 * pad_all become their four concrete properties, which is the shape a cascade
 * consumer needs.
 */
#define lv_style_init(style) cap_reset(style)
#define lv_style_reset(style) cap_reset(style)
#define lv_style_set_arc_color(style, value)                                   \
  cap_set_color(style, "arc-color", value)
#define lv_style_set_bg_color(style, value)                                    \
  cap_set_color(style, "bg-color", value)
#define lv_style_set_bg_opa(style, value) cap_set_num(style, "bg-opa", value)
#define lv_style_set_border_color(style, value)                                \
  cap_set_color(style, "border-color", value)
#define lv_style_set_border_side(style, value)                                 \
  cap_set_num(style, "border-side", value)
#define lv_style_set_border_width(style, value)                                \
  cap_set_num(style, "border-width", value)
#define lv_style_set_flex_cross_place(style, value)                            \
  cap_set_num(style, "flex-cross-place", value)
#define lv_style_set_flex_flow(style, value)                                   \
  cap_set_num(style, "flex-flow", value)
#define lv_style_set_flex_main_place(style, value)                             \
  cap_set_num(style, "flex-main-place", value)
#define lv_style_set_flex_track_place(style, value)                            \
  cap_set_num(style, "flex-track-place", value)
#define lv_style_set_layout(style, value) cap_set_num(style, "layout", value)
#define lv_style_set_opa(style, value) cap_set_num(style, "opa", value)
#define lv_style_set_outline_color(style, value)                               \
  cap_set_color(style, "outline-color", value)
#define lv_style_set_outline_opa(style, value)                                 \
  cap_set_num(style, "outline-opa", value)
#define lv_style_set_outline_pad(style, value)                                 \
  cap_set_num(style, "outline-pad", value)
#define lv_style_set_outline_width(style, value)                               \
  cap_set_num(style, "outline-width", value)
#define lv_style_set_pad_all(style, value)                                     \
  do {                                                                         \
    int32_t cap_value = (value);                                               \
    cap_set_num(style, "pad-top", cap_value);                                  \
    cap_set_num(style, "pad-right", cap_value);                                \
    cap_set_num(style, "pad-bottom", cap_value);                               \
    cap_set_num(style, "pad-left", cap_value);                                 \
  } while (0)
#define lv_style_set_pad_bottom(style, value)                                  \
  cap_set_num(style, "pad-bottom", value)
#define lv_style_set_pad_column(style, value)                                  \
  cap_set_num(style, "pad-column", value)
#define lv_style_set_pad_hor(style, value)                                     \
  do {                                                                         \
    int32_t cap_value = (value);                                               \
    cap_set_num(style, "pad-left", cap_value);                                 \
    cap_set_num(style, "pad-right", cap_value);                                \
  } while (0)
#define lv_style_set_pad_left(style, value)                                    \
  cap_set_num(style, "pad-left", value)
#define lv_style_set_pad_right(style, value)                                   \
  cap_set_num(style, "pad-right", value)
#define lv_style_set_pad_row(style, value) cap_set_num(style, "pad-row", value)
#define lv_style_set_pad_top(style, value) cap_set_num(style, "pad-top", value)
#define lv_style_set_pad_ver(style, value)                                     \
  do {                                                                         \
    int32_t cap_value = (value);                                               \
    cap_set_num(style, "pad-top", cap_value);                                  \
    cap_set_num(style, "pad-bottom", cap_value);                               \
  } while (0)
#define lv_style_set_radius(style, value) cap_set_num(style, "radius", value)
#define lv_style_set_recolor(style, value)                                     \
  cap_set_color(style, "recolor", value)
#define lv_style_set_recolor_opa(style, value)                                 \
  cap_set_num(style, "recolor-opa", value)
#define lv_style_set_shadow_color(style, value)                                \
  cap_set_color(style, "shadow-color", value)
#define lv_style_set_shadow_offset_y(style, value)                             \
  cap_set_num(style, "shadow-offset-y", value)
#define lv_style_set_shadow_opa(style, value)                                  \
  cap_set_num(style, "shadow-opa", value)
#define lv_style_set_shadow_spread(style, value)                               \
  cap_set_num(style, "shadow-spread", value)
#define lv_style_set_shadow_width(style, value)                                \
  cap_set_num(style, "shadow-width", value)
#define lv_style_set_text_color(style, value)                                  \
  cap_set_color(style, "text-color", value)
#define lv_style_set_transform_height(style, value)                            \
  cap_set_num(style, "transform-height", value)
#define lv_style_set_transform_width(style, value)                             \
  cap_set_num(style, "transform-width", value)
#define lv_style_set_transition(style, value)                                  \
  do {                                                                         \
    (void)(value);                                                             \
    cap_set_transition(style);                                                 \
  } while (0)
#define lv_style_transition_dsc_init(...) ((void)0)
#define lv_obj_add_style cap_add_style
#define lv_obj_check_type cap_check_type
#define lv_obj_get_parent cap_get_parent
#define lv_obj_get_child cap_get_child
#define lv_obj_report_style_change(...) ((void)0)
#define lv_display_get_dpi cap_display_get_dpi
#define lv_display_get_horizontal_resolution                                   \
  cap_display_get_horizontal_resolution
#define lv_display_get_vertical_resolution cap_display_get_vertical_resolution
#define lv_display_get_theme(...) NULL
#define lv_theme_set_parent(...) ((void)0)
#ifndef THEME_SOURCE_PATH
#define THEME_SOURCE_PATH "../../../../renderer/src/theme.c"
#endif
#include THEME_SOURCE_PATH
/*
 * theme.c performs exact-type checks, so only stable class identities are
 * required.  No widget implementation or copied style behavior lives here.
 */
const lv_obj_class_t lv_obj_class = {.name = "lv_obj"};
const lv_obj_class_t lv_button_class = {.name = "lv_button"};
const lv_obj_class_t lv_slider_class = {.name = "lv_slider"};
const lv_obj_class_t lv_switch_class = {.name = "lv_switch"};
const lv_obj_class_t lv_arc_class = {.name = "lv_arc"};
const lv_obj_class_t lv_spinner_class = {.name = "lv_spinner"};
const lv_obj_class_t lv_bar_class = {.name = "lv_bar"};
const lv_obj_class_t lv_checkbox_class = {.name = "lv_checkbox"};
const lv_obj_class_t lv_dropdown_class = {.name = "lv_dropdown"};
const lv_obj_class_t lv_dropdownlist_class = {.name = "lv_dropdownlist"};
const lv_obj_class_t lv_roller_class = {.name = "lv_roller"};
const lv_obj_class_t lv_textarea_class = {.name = "lv_textarea"};
const lv_obj_class_t lv_spinbox_class = {.name = "lv_spinbox"};
const lv_obj_class_t lv_buttonmatrix_class = {.name = "lv_buttonmatrix"};
const lv_obj_class_t lv_table_class = {.name = "lv_table"};
const lv_obj_class_t lv_tabview_class = {.name = "lv_tabview"};
const lv_obj_class_t lv_led_class = {.name = "lv_led"};
const lv_obj_class_t lv_label_class = {.name = "lv_label"};
#if LV_USE_WIN
const lv_obj_class_t lv_win_class = {.name = "lv_win"};
#endif
#if LV_USE_CALENDAR
const lv_obj_class_t lv_calendar_class = {.name = "lv_calendar"};
#endif
static const lv_obj_class_t unknown_class = {.name = "wildcard-probe"};
typedef struct {
  const char *name;
  const lv_style_t *style;
} named_style_t;
static const named_style_t named_styles[] = {
#define NAMED_STYLE(field) {#field, &theme_inst.styles.field}
    NAMED_STYLE(panel),
    NAMED_STYLE(control_rad),
    NAMED_STYLE(btn),
    NAMED_STYLE(btn_shadow),
    NAMED_STYLE(item_rad),
    NAMED_STYLE(btnm_pads),
    NAMED_STYLE(btnm_items),
    NAMED_STYLE(accent_ink),
    NAMED_STYLE(roller_pad),
    NAMED_STYLE(ta_pad),
    NAMED_STYLE(table_items),
    NAMED_STYLE(table_grid),
    NAMED_STYLE(cb_ind),
    NAMED_STYLE(cb_grow_off),
    NAMED_STYLE(led),
    NAMED_STYLE(knob),
    NAMED_STYLE(scrollbar),
    NAMED_STYLE(field_bg),
    NAMED_STYLE(focus),
    NAMED_STYLE(checked_accent),
    NAMED_STYLE(roller_sel),
    NAMED_STYLE(roller_sel_dis),
    NAMED_STYLE(edited_edge),
    NAMED_STYLE(disabled),
    NAMED_STYLE(disabled_dim),
    NAMED_STYLE(disabled_fill),
    NAMED_STYLE(disabled_flat),
    NAMED_STYLE(hover),
    NAMED_STYLE(pressed),
    NAMED_STYLE(track_tone),
    NAMED_STYLE(readout_arc),
    NAMED_STYLE(track_bg),
    NAMED_STYLE(tab_txt),
    NAMED_STYLE(tab_bar_bg),
    NAMED_STYLE(tab_page_bg),
    NAMED_STYLE(disabled_track),
    NAMED_STYLE(disabled_knob),
    NAMED_STYLE(cursor_off),
    NAMED_STYLE(trans),
#undef NAMED_STYLE
};
typedef enum {
  CONTEXT_NORMAL,
  CONTEXT_TAB_BUTTON,
  CONTEXT_TAB_INTERNAL,
  CONTEXT_TAB_PAGE,
} target_context_t;
typedef struct {
  const char *name;
  const lv_obj_class_t *class_p;
  target_context_t context;
} target_t;
/*
 * Index zero is a synthetic unhandled class.  Anything attached there occurs
 * before class dispatch and is emitted once as target "*".
 */
static const target_t targets[] = {
    {"*", &unknown_class, CONTEXT_NORMAL},
    {"obj", &lv_obj_class, CONTEXT_NORMAL},
    {"button", &lv_button_class, CONTEXT_NORMAL},
    {"slider", &lv_slider_class, CONTEXT_NORMAL},
    {"switch", &lv_switch_class, CONTEXT_NORMAL},
    {"arc", &lv_arc_class, CONTEXT_NORMAL},
    {"spinner", &lv_spinner_class, CONTEXT_NORMAL},
    {"bar", &lv_bar_class, CONTEXT_NORMAL},
    {"checkbox", &lv_checkbox_class, CONTEXT_NORMAL},
    {"dropdown", &lv_dropdown_class, CONTEXT_NORMAL},
    {"dropdown-list", &lv_dropdownlist_class, CONTEXT_NORMAL},
    {"roller", &lv_roller_class, CONTEXT_NORMAL},
    {"textarea", &lv_textarea_class, CONTEXT_NORMAL},
    {"spinbox", &lv_spinbox_class, CONTEXT_NORMAL},
    {"buttonmatrix", &lv_buttonmatrix_class, CONTEXT_NORMAL},
    {"table", &lv_table_class, CONTEXT_NORMAL},
    {"tabview", &lv_tabview_class, CONTEXT_NORMAL},
    {"led", &lv_led_class, CONTEXT_NORMAL},
    {"label", &lv_label_class, CONTEXT_NORMAL},
    {"tabview-tab-button", &lv_button_class, CONTEXT_TAB_BUTTON},
    {"tabview-internal-object", &lv_obj_class, CONTEXT_TAB_INTERNAL},
    {"tabview-page-object", &lv_obj_class, CONTEXT_TAB_PAGE},
};
typedef struct {
  const char *name;
  int32_t width;
  int32_t height;
} display_profile_t;
/* Boundary representatives for theme.c's <=320 / <720 / >=720 tiers. */
static const display_profile_t display_profiles[] = {
    {"small", 320, 240},
    {"medium", 640, 480},
    {"large", 800, 480},
};
static const char *family_name(int family) {
  switch (family) {
  case ASGARD_THEME_FAMILY_ASGARD:
    return "asgard";
  case ASGARD_THEME_FAMILY_VANILLA:
    return "vanilla";
  case ASGARD_THEME_FAMILY_STOCK:
    return "stock";
  default:
    die("unknown theme family");
  }
  return "";
}
static const char *style_name(const lv_style_t *style) {
  for (size_t i = 0; i < ARRAY_LEN(named_styles); i++)
    if (named_styles[i].style == style)
      return named_styles[i].name;
  die("theme.c reset or attached a style absent from named_styles");
  return "";
}
static bool style_is_known(const lv_style_t *style) {
  for (size_t i = 0; i < ARRAY_LEN(named_styles); i++)
    if (named_styles[i].style == style)
      return true;
  return false;
}
static lv_theme_t parent_theme(int family) {
  lv_color_t primary = family == ASGARD_THEME_FAMILY_ASGARD
                           ? lv_color_hex(THEME_ACCENT_DARK)
                           : lv_palette_main(LV_PALETTE_BLUE);
  return (lv_theme_t){
      .color_primary = primary,
      .color_secondary = lv_palette_main(LV_PALETTE_RED),
  };
}
static void initialize_theme(int family, bool dark, int profile) {
  probe_width = display_profiles[profile].width;
  probe_height = display_profiles[profile].height;
  current_property_count = 0;
  reset_style_count = 0;
  lv_theme_t parent = parent_theme(family);
  (void)asgard_theme_init((lv_display_t *)(uintptr_t)1, dark,
                          (asgard_theme_family_t)family, &parent);
  for (size_t i = 0; i < reset_style_count; i++)
    if (!style_is_known(reset_styles[i]))
      (void)style_name(reset_styles[i]);
  if (reset_style_count != ARRAY_LEN(named_styles))
    die("named style roster differs from styles reset by theme.c");
}
static void apply_target(int target_index) {
  lv_obj_t screen = {.class_p = &unknown_class};
  lv_obj_t grandparent = {
      .class_p = &unknown_class,
      .parent = &screen,
  };
  lv_obj_t parent = {
      .class_p = &unknown_class,
      .parent = &grandparent,
  };
  lv_obj_t object = {
      .class_p = targets[target_index].class_p,
      .parent = &parent,
  };
  lv_obj_t *tab_children[1] = {&parent};
  lv_obj_spec_attr_t tab_spec = {
      .children = tab_children,
      .child_cnt = 1,
  };
  switch (targets[target_index].context) {
  case CONTEXT_NORMAL:
    break;
  case CONTEXT_TAB_BUTTON:
    grandparent.class_p = &lv_tabview_class;
    grandparent.spec_attr = &tab_spec;
    tab_children[0] = &parent;
    break;
  case CONTEXT_TAB_INTERNAL:
    parent.class_p = &lv_tabview_class;
    parent.parent = &screen;
    /* MODEL THE BAR, i.e. child 0 of the tabview. theme.c separates the tab
     * bar from the content by `lv_obj_get_child(parent, 0) == obj`, and
     * cap_get_child returns NULL unless spec_attr is populated — so without
     * these two lines that guard is false at every replay point and the
     * bar's style contributes NO applications, hence no group at all. It is
     * not a wrong group, it is an ABSENT one, which is why the "has no
     * applications" assertion cannot see it: that assertion iterates the
     * groups that were emitted. */
    tab_children[0] = &object;
    parent.spec_attr = &tab_spec;
    break;
  case CONTEXT_TAB_PAGE:
    grandparent.class_p = &lv_tabview_class;
    break;
  }
  current_target = target_index;
  current_attachment_order = 0;
  theme_apply(&theme_inst.base, &object);
}
/*
 * WHERE a style attaches is emitted once, without a mode or display-size axis.
 * That is only sound because theme_apply branches on t->family alone.  Rather
 * than assume it, replay the entire capture at every mode/size point and
 * require two things to hold: the raw attachment call count is identical at
 * every point, and no point contributes an application the first point did not.
 * A theme.c that grew a dark-only or size-only attachment fails generation here
 * instead of silently emitting a manifest that is wrong at eleven of twelve
 * points.
 */
static void capture_applications(void) {
  application_count = 0;
  size_t reference_calls = 0;
  bool have_reference = false;
  for (int dark = 0; dark <= 1; dark++) {
    for (size_t profile = 0; profile < ARRAY_LEN(display_profiles); profile++) {
      size_t before = application_count;
      size_t calls = 0;
      for (int family = ASGARD_THEME_FAMILY_ASGARD;
           family <= ASGARD_THEME_FAMILY_STOCK; family++) {
        current_family = family;
        initialize_theme(family, dark != 0, (int)profile);
        for (size_t target = 0; target < ARRAY_LEN(targets); target++) {
          add_style_calls = 0;
          apply_target((int)target);
          calls += add_style_calls;
        }
      }
      if (!have_reference) {
        reference_calls = calls;
        have_reference = true;
      } else {
        if (calls != reference_calls)
          die("theme.c attaches a different number of styles at some "
              "mode/display-size point; the emitted application set is no "
              "longer point-independent");
        if (application_count != before)
          die("theme.c attached a style at a mode/display-size point that the "
              "reference point does not reach");
      }
    }
  }
  for (size_t i = 0; i < application_count; i++)
    (void)style_name(applications[i].style);
  /* ROSTER TOTALITY — every named style must have been APPLIED somewhere.
   *
   * The reset-count check above proves theme.c initialises each rostered
   * style; it says nothing about whether the probe ever reaches an object
   * that style attaches to. A style whose guard the probe cannot satisfy
   * captures zero applications and therefore emits NO GROUP — and the
   * downstream "has no applications" assertion iterates the groups that
   * WERE emitted, so it is structurally unable to see the absence. That is
   * how a real style (the tab bar's fill, whose guard needs a populated
   * spec_attr) shipped invisible to the public projection while every check
   * stayed green. Assert it here, where the absent case is still
   * representable. */
  for (size_t i = 0; i < ARRAY_LEN(named_styles); i++) {
    bool applied = false;
    for (size_t j = 0; j < application_count && !applied; j++)
      if (applications[j].style == named_styles[i].style)
        applied = true;
    if (!applied)
      die("a named style captured ZERO applications — the probe never reaches "
          "an object it attaches to, so it would emit no group at all");
  }
}
static size_t capture_variants(cap_variant_t *variants, size_t capacity) {
  size_t count = 0;
  for (int family = ASGARD_THEME_FAMILY_ASGARD;
       family <= ASGARD_THEME_FAMILY_VANILLA; family++) {
    for (int dark = 0; dark <= 1; dark++) {
      for (size_t profile = 0; profile < ARRAY_LEN(display_profiles);
           profile++) {
        if (count == capacity)
          die("variant storage exhausted");
        initialize_theme(family, dark != 0, (int)profile);
        cap_variant_t *variant = &variants[count++];
        variant->family = family;
        variant->dark = dark != 0;
        variant->profile = (int)profile;
        variant->property_count = current_property_count;
        (void)memcpy(variant->properties, current_properties,
                     current_property_count * sizeof(current_properties[0]));
      }
    }
  }
  return count;
}
static bool group_has_application(const lv_style_t *style) {
  for (size_t i = 0; i < application_count; i++)
    if (applications[i].style == style)
      return true;
  return false;
}
static bool group_has_family_application(const lv_style_t *style, int family) {
  for (size_t i = 0; i < application_count; i++)
    if (applications[i].style == style && applications[i].family == family)
      return true;
  return false;
}
static void print_states(uint32_t selector) {
  uint32_t states = selector & 0xffffu;
  bool first = true;
  (void)putchar('[');
#define PRINT_STATE(bit, name)                                                 \
  do {                                                                         \
    if ((states & (bit)) != 0) {                                               \
      (void)printf("%s\"%s\"", first ? "" : ",", name);                        \
      first = false;                                                           \
    }                                                                          \
  } while (0)
  PRINT_STATE(LV_STATE_ALT, "alt");
  PRINT_STATE(LV_STATE_CHECKED, "checked");
  PRINT_STATE(LV_STATE_FOCUSED, "focused");
  PRINT_STATE(LV_STATE_FOCUS_KEY, "focus-key");
  PRINT_STATE(LV_STATE_EDITED, "edited");
  PRINT_STATE(LV_STATE_HOVERED, "hovered");
  PRINT_STATE(LV_STATE_PRESSED, "pressed");
  PRINT_STATE(LV_STATE_SCROLLED, "scrolled");
  PRINT_STATE(LV_STATE_DISABLED, "disabled");
  PRINT_STATE(LV_STATE_USER_1, "user-1");
  PRINT_STATE(LV_STATE_USER_2, "user-2");
  PRINT_STATE(LV_STATE_USER_3, "user-3");
  PRINT_STATE(LV_STATE_USER_4, "user-4");
#undef PRINT_STATE
  if (first)
    (void)printf("\"default\"");
  (void)putchar(']');
}
static const char *part_name(uint32_t selector) {
  switch (selector & 0x0f0000u) {
  case LV_PART_MAIN:
    return "main";
  case LV_PART_SCROLLBAR:
    return "scrollbar";
  case LV_PART_INDICATOR:
    return "indicator";
  case LV_PART_KNOB:
    return "knob";
  case LV_PART_SELECTED:
    return "selected";
  case LV_PART_ITEMS:
    return "items";
  case LV_PART_CURSOR:
    return "cursor";
  case LV_PART_TEXTAREA_PLACEHOLDER:
    return "textarea-placeholder";
  default:
    die("theme.c used a part the manifest emitter cannot name");
  }
  return "";
}
static const char *context_name(target_context_t context) {
  switch (context) {
  case CONTEXT_NORMAL:
    return "normal";
  case CONTEXT_TAB_BUTTON:
    return "tab-button";
  case CONTEXT_TAB_INTERNAL:
    return "tabview-internal";
  case CONTEXT_TAB_PAGE:
    return "tabview-page";
  }
  die("unknown target context");
  return "";
}
static void print_properties(const cap_variant_t *variant,
                             const lv_style_t *style) {
  bool first = true;
  (void)putchar('{');
  for (size_t i = 0; i < variant->property_count; i++) {
    const cap_prop_t *property = &variant->properties[i];
    if (property->style != style)
      continue;
    (void)printf("%s\"%s\":", first ? "" : ",", property->name);
    first = false;
    switch (property->kind) {
    case CAP_NUM:
      (void)printf("{\"type\":\"int\",\"value\":%d}", property->num);
      break;
    case CAP_COLOR:
      (void)printf("{\"type\":\"color\",\"value\":\"#%06x\"}", property->color);
      break;
    case CAP_TRANSITION:
      (void)printf(
          "{\"type\":\"transition\",\"duration-ms\":0,\"delay-ms\":0}");
      break;
    }
  }
  (void)putchar('}');
}
static void print_manifest(const cap_variant_t *variants, size_t variant_count,
                           const char *theme_hash,
                           const char *theme_header_hash,
                           const char *tokens_hash, const char *config_hash,
                           const char *lvgl_pin_hash,
                           const char *generator_hash) {
  (void)printf("{\n");
  (void)printf("  \"schema-version\":1,\n");
  (void)printf("  \"kind\":\"lvgl-child-theme-style-groups\",\n");
  (void)printf("  \"source\":\"renderer/src/theme.c\",\n");
  (void)printf("  \"source-method\":\"compiled-execution\",\n");
  (void)printf("  \"dpi\":%d,\n", probe_dpi);
  (void)printf("  \"scope\":["
               "\"explicit child-theme groups and selectors\","
               "\"resolved group-local properties\"],\n");
  (void)printf("  \"excluded\":["
               "\"stock parent theme cascade\","
               "\"per-node AST style_groups\","
               "\"effective inherited/composited draw colors\"],\n");
  (void)printf("  \"inputs\":{"
               "\"renderer/src/theme.c\":\"%s\","
               "\"renderer/src/theme.h\":\"%s\","
               "\"renderer/generated/theme_tokens.h\":\"%s\","
               "\"renderer/lv_conf.h\":\"%s\","
               "\"renderer/lvgl/.ported-from.edn\":\"%s\","
               "\"tools/renderer-gen/tools/theme-style-groups/emit.c\":\"%s\""
               "},\n",
               theme_hash, theme_header_hash, tokens_hash, config_hash,
               lvgl_pin_hash, generator_hash);
  (void)printf("  \"display-profiles\":[");
  for (size_t i = 0; i < ARRAY_LEN(display_profiles); i++) {
    const display_profile_t *profile = &display_profiles[i];
    (void)printf("%s{\"id\":\"%s\",\"width\":%d,\"height\":%d}",
                 i == 0 ? "" : ",", profile->name, profile->width,
                 profile->height);
  }
  (void)printf("],\n");
  (void)printf("  \"targets\":[");
  for (size_t i = 0; i < ARRAY_LEN(targets); i++) {
    const target_t *target = &targets[i];
    const char *class_name = i == 0 ? "*" : target->class_p->name;
    (void)printf("%s{\"id\":\"%s\",\"lvgl-class\":\"%s\",\"context\":\"%s\"}",
                 i == 0 ? "" : ",", target->name, class_name,
                 context_name(target->context));
  }
  (void)printf("],\n");
  (void)printf("  \"theme-families\":{"
               "\"asgard\":{\"child-theme\":\"active\"},"
               "\"vanilla\":{\"child-theme\":\"stock-restatement\"},"
               "\"stock\":{\"child-theme\":\"no-op\"}},\n");
  (void)printf("  \"style-groups\":[\n");
  bool first_group = true;
  for (size_t group_index = 0; group_index < ARRAY_LEN(named_styles);
       group_index++) {
    const named_style_t *group = &named_styles[group_index];
    if (!group_has_application(group->style))
      continue;
    (void)printf("%s    {\"name\":\"%s\",\"applications\":[",
                 first_group ? "" : ",\n", group->name);
    first_group = false;
    bool first_application = true;
    for (size_t i = 0; i < application_count; i++) {
      const cap_application_t *application = &applications[i];
      if (application->style != group->style)
        continue;
      (void)printf("%s{\"family\":\"%s\",\"target\":\"%s\","
                   "\"selector\":%u,\"part\":\"%s\",\"states\":",
                   first_application ? "" : ",",
                   family_name(application->family),
                   targets[application->target].name, application->selector,
                   part_name(application->selector));
      print_states(application->selector);
      (void)printf(",\"attachment-order\":%u}", application->attachment_order);
      first_application = false;
    }
    (void)printf("],\"variants\":[");
    bool first_variant = true;
    for (size_t i = 0; i < variant_count; i++) {
      const cap_variant_t *variant = &variants[i];
      if (!group_has_family_application(group->style, variant->family))
        continue;
      (void)printf("%s{\"family\":\"%s\",\"mode\":\"%s\","
                   "\"display-profile\":\"%s\",\"properties\":",
                   first_variant ? "" : ",", family_name(variant->family),
                   variant->dark ? "dark" : "light",
                   display_profiles[variant->profile].name);
      print_properties(variant, group->style);
      (void)putchar('}');
      first_variant = false;
    }
    (void)printf("]}");
  }
  (void)printf("\n  ]\n}\n");
}
int main(int argc, char **argv) {
  if (argc != 8)
    die("internal invocation requires dpi and six input hashes");
  char *dpi_end = NULL;
  long parsed_dpi = strtol(argv[1], &dpi_end, 10);
  if (dpi_end == argv[1] || *dpi_end != '\0' || parsed_dpi <= 0 ||
      parsed_dpi > INT32_MAX)
    die("dpi must be a positive 32-bit integer");
  probe_dpi = (int32_t)parsed_dpi;
  capture_applications();
  cap_variant_t variants[12];
  size_t variant_count = capture_variants(variants, ARRAY_LEN(variants));
  print_manifest(variants, variant_count, argv[2], argv[3], argv[4], argv[5],
                 argv[6], argv[7]);
  return 0;
}
