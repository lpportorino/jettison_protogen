/* Exercise real initialization/destruction with observable LVGL boundaries.
 * Drawing and input dispatch are outside this native ownership probe. */
#include <stdlib.h>
static void *probe_malloc(size_t size);
static void *probe_calloc(size_t count, size_t size);
static void probe_free(void *ptr);
#define malloc probe_malloc
#define calloc probe_calloc
#define free probe_free
#ifndef MAIN_SOURCE
#define MAIN_SOURCE "../src/main.c"
#endif
#include MAIN_SOURCE
#undef malloc
#undef calloc
#undef free
static unsigned assertions, failures, case_failures;
static const char *test_case;
#define CHECK(expression)                                                      \
  do {                                                                         \
    assertions++;                                                              \
    if (!(expression)) {                                                       \
      failures++;                                                              \
      fprintf(stderr, "FAIL %s: %s\n", test_case, #expression);                \
    }                                                                          \
  } while (0)
static unsigned heap_live, create_calls, delete_calls, fail_create;
static unsigned tree_cleaned, registry_cleaned, boundary_errors;
static unsigned devices_live, display_deleted, decoder_live;
static bool subjects_live[2];
static max_align_t subject_tokens[2], display_token, screen_token;
static max_align_t group_token, device_tokens[2], theme_token;
static void *probe_malloc(size_t size) {
  void *ptr = malloc(size);
  if (ptr)
    heap_live++;
  return ptr;
}
static void *probe_calloc(size_t count, size_t size) {
  void *ptr = calloc(count, size);
  if (ptr)
    heap_live++;
  return ptr;
}
static void probe_free(void *ptr) {
  if (ptr) {
    if (!heap_live)
      boundary_errors++;
    else
      heap_live--;
  }
  free(ptr);
}
void lv_init(void) {}
void palette_observer_init(void) {}
void gesture_reset(gesture_recognizer_t *state) {
  memset(state, 0, sizeof(*state));
}
lv_display_t *lv_display_create(int32_t width, int32_t height) {
  (void)width;
  (void)height;
  return (lv_display_t *)&display_token;
}
void lv_display_set_color_format(lv_display_t *disp, lv_color_format_t format) {
  (void)disp;
  (void)format;
}
void lv_display_set_buffers(lv_display_t *disp, void *first, void *second,
                            uint32_t size, lv_display_render_mode_t mode) {
  (void)disp;
  (void)first;
  (void)second;
  (void)size;
  (void)mode;
}
void lv_display_set_flush_cb(lv_display_t *disp,
                             lv_display_flush_cb_t callback) {
  (void)disp;
  (void)callback;
}
lv_obj_t *lv_screen_active(void) { return (lv_obj_t *)&screen_token; }
lv_obj_t *lv_layer_bottom(void) { return (lv_obj_t *)&screen_token; }
void lv_obj_set_clickable(lv_obj_t *obj, bool enabled) {
  (void)obj;
  (void)enabled;
}
lv_color_t lv_palette_main(lv_palette_t palette) {
  (void)palette;
  return (lv_color_t){0};
}
lv_color_t lv_color_hex(uint32_t color) {
  (void)color;
  return (lv_color_t){0};
}
void lv_obj_set_style_bg_opa(lv_obj_t *obj, lv_opa_t opacity,
                             lv_style_selector_t selector) {
  (void)obj;
  (void)opacity;
  (void)selector;
}
const lv_font_t lv_font_montserrat_16 = {0};
lv_theme_t *lv_theme_default_init(lv_display_t *disp, lv_color_t primary,
                                  lv_color_t secondary, bool dark,
                                  const lv_font_t *font) {
  (void)disp;
  (void)primary;
  (void)secondary;
  (void)dark;
  (void)font;
  return (lv_theme_t *)&theme_token;
}
lv_theme_t *asgard_theme_init(lv_display_t *disp, bool dark,
                              asgard_theme_family_t family,
                              lv_theme_t *parent) {
  (void)disp;
  (void)dark;
  (void)family;
  return parent;
}
void lv_display_set_theme(lv_display_t *disp, lv_theme_t *theme) {
  (void)disp;
  (void)theme;
}
void lv_obj_set_local_style_prop(lv_obj_t *obj, lv_style_prop_t prop,
                                 lv_style_value_t value,
                                 lv_style_selector_t selector) {
  (void)obj;
  (void)prop;
  (void)value;
  (void)selector;
}
lv_indev_t *lv_indev_create(void) {
  if (devices_live >= 2) {
    boundary_errors++;
    return NULL;
  }
  return (lv_indev_t *)&device_tokens[devices_live++];
}
void lv_indev_set_type(lv_indev_t *device, lv_indev_type_t type) {
  (void)device;
  (void)type;
}
void lv_indev_set_read_cb(lv_indev_t *device, lv_indev_read_cb_t callback) {
  (void)device;
  (void)callback;
}
lv_group_t *lv_group_create(void) { return (lv_group_t *)&group_token; }
void lv_group_set_default(lv_group_t *group) { (void)group; }
void lv_indev_set_group(lv_indev_t *device, lv_group_t *group) {
  (void)device;
  (void)group;
}
void svg_decoder_init(void) { decoder_live++; }
lv_subject_t *lv_subject_create(lv_subject_type_t type) {
  unsigned index = create_calls++ % 2;
  if (type != LV_SUBJECT_TYPE_INT || subjects_live[index])
    boundary_errors++;
  if (create_calls == fail_create)
    return NULL;
  subjects_live[index] = true;
  return (lv_subject_t *)&subject_tokens[index];
}
void lv_subject_delete(lv_subject_t *subject) {
  if (!subject)
    return;
  unsigned index = subject == (lv_subject_t *)&subject_tokens[0] ? 0 : 1;
  if (subject != (lv_subject_t *)&subject_tokens[index] ||
      !subjects_live[index] || !tree_cleaned || !registry_cleaned)
    boundary_errors++;
  subjects_live[index] = false;
  delete_calls++;
}
void lv_group_remove_all_objs(lv_group_t *group) { (void)group; }
void lv_obj_clean(lv_obj_t *obj) {
  (void)obj;
  tree_cleaned++;
}
void renderer_cleanup(void) {
  if (!tree_cleaned)
    boundary_errors++;
  registry_cleaned++;
}
void svg_decoder_deinit(void) {
  if (!decoder_live || subjects_live[0] || subjects_live[1])
    boundary_errors++;
  decoder_live = 0;
}
lv_indev_t *lv_indev_get_next(lv_indev_t *device) {
  (void)device;
  return devices_live ? (lv_indev_t *)&device_tokens[devices_live - 1] : NULL;
}
void lv_indev_delete(lv_indev_t *device) {
  if (!devices_live || device != (lv_indev_t *)&device_tokens[devices_live - 1])
    boundary_errors++;
  if (devices_live)
    devices_live--;
}
void lv_group_delete(lv_group_t *group) { (void)group; }
void lv_display_delete(lv_display_t *disp) {
  if (disp != (lv_display_t *)&display_token || subjects_live[0] ||
      subjects_live[1])
    boundary_errors++;
  display_deleted++;
}
static void begin_case(const char *name) {
  test_case = name;
  case_failures = failures;
}
static void end_case(void) {
  if (failures == case_failures)
    printf("PASS %s\n", test_case);
}
static void clean_state(void) {
  CHECK(heap_live == 0);
  CHECK(!subjects_live[0] && !subjects_live[1]);
  CHECK(!subj_composite && !subj_channel_type);
  CHECK(!display && !input_group);
  CHECK(devices_live == 0 && decoder_live == 0);
  CHECK(boundary_errors == 0);
}
static void reset_case(void) {
  if (display)
    (void)controls_destroy();
  create_calls = delete_calls = fail_create = boundary_errors = 0;
  tree_cleaned = registry_cleaned = display_deleted = 0;
  subjects_live[0] = subjects_live[1] = false;
  subj_composite = subj_channel_type = NULL;
}
int main(void) {
  begin_case("globals.invalid-size");
#ifdef GLOBAL_PROBE_FAIL_CANARY
  CHECK(false);
#endif
  CHECK(controls_init(0, 24) == -1);
  CHECK(create_calls == 0);
  clean_state();
  end_case();
  begin_case("globals.success-and-reinit");
  for (unsigned cycle = 1; cycle <= 2; cycle++) {
    tree_cleaned = registry_cleaned = 0;
    CHECK(controls_init(32, 24) == 0);
    CHECK(create_calls == 2 * cycle);
    CHECK(subj_composite && subj_channel_type &&
          subj_composite != subj_channel_type);
    CHECK(subjects_live[0] && subjects_live[1]);
    CHECK(controls_destroy() == 0);
    CHECK(delete_calls == 2 * cycle);
    CHECK(display_deleted == cycle);
    clean_state();
    CHECK(controls_destroy() == 0);
    CHECK(delete_calls == 2 * cycle);
    clean_state();
  }
  end_case();
  for (unsigned failed = 1; failed <= 2; failed++) {
    reset_case();
    begin_case(failed == 1 ? "globals.first-allocation-fails"
                           : "globals.second-allocation-fails");
    fail_create = failed;
    CHECK(controls_init(32, 24) == -1);
    CHECK(create_calls == 2);
    CHECK(delete_calls == 1);
    CHECK(display_deleted == 1);
    clean_state();
    CHECK(controls_destroy() == 0);
    CHECK(delete_calls == 1);
    end_case();
  }
  printf("SUMMARY assertions=%u failures=%u\n", assertions, failures);
  return failures ? 1 : 0;
}
