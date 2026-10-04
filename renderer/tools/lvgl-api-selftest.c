/* Native boundary contract for the actual renderer translation unit. The
 * generated oracle uses public LVGL declarations, never the renderer table.
 * Unreachable widget/drawing code is discarded by the native linker. */
#include <stdlib.h>
static void *probe_malloc(size_t size);
static void probe_free(void *data);
#define malloc probe_malloc
#define free probe_free
#ifndef RENDERER_SOURCE
#define RENDERER_SOURCE "../src/renderer.c"
#endif
#include RENDERER_SOURCE
#undef malloc
#undef free
#include <pb_encode.h>
static unsigned assertions;
static unsigned failures;
static const char *test_case;
#define CHECK(expression)                                                      \
  do {                                                                         \
    assertions++;                                                              \
    if (!(expression)) {                                                       \
      failures++;                                                              \
      fprintf(stderr, "FAIL %s: %s\n", test_case, #expression);                \
    }                                                                          \
  } while (0)
static unsigned case_failures;
static unsigned case_assertions;
static void begin_case(const char *name) {
  test_case = name;
  case_failures = failures;
  case_assertions = assertions;
}
static void end_case(void) {
  if (assertions == case_assertions) {
    failures++;
    fprintf(stderr, "FAIL %s: no assertions executed\n", test_case);
  }
  if (failures == case_failures)
    printf("PASS %s\n", test_case);
}
static lv_obj_t *expected_obj;
static uint32_t observed_mask;
static uint32_t object_state;
static unsigned flag_calls;
static unsigned boundary_errors;
static lv_prop_id_t rejected_property;
static void record_flag(lv_obj_t *obj, uint32_t flag, bool enabled) {
  if (obj != expected_obj || (observed_mask & flag) != 0 || flag == 0)
    boundary_errors++;
  flag_calls++;
  observed_mask |= flag;
  if (enabled)
    object_state |= flag;
  else
    object_state &= ~flag;
}
/* Defines dedicated setter doubles and property/user dispatch from headers. */
#include "lvgl-api-oracle.h"
static void reset_flags(uint32_t initial) {
  /* The doubles compare identity and never dereference the opaque object. */
  static max_align_t object_token;
  expected_obj = (lv_obj_t *)&object_token;
  observed_mask = 0;
  object_state = initial;
  flag_calls = 0;
  boundary_errors = 0;
  rejected_property = 0;
  load_resource_error = false;
}
static unsigned bit_count(uint32_t value) {
  unsigned count = 0;
  while (value) {
    count += value & 1u;
    value >>= 1;
  }
  return count;
}
static void flag_case(const char *name, uint32_t mask, bool enabled) {
  begin_case(name);
  uint32_t initial = enabled ? UINT32_C(0x80000000) : UINT32_MAX;
  uint32_t known = mask & UINT32_C(0x7fffffff);
  reset_flags(initial);
  apply_wire_flags(expected_obj, mask, enabled);
  CHECK(observed_mask == known);
  CHECK(flag_calls == bit_count(known));
  CHECK(boundary_errors == 0);
  CHECK(object_state == (enabled ? initial | known : initial & ~known));
  CHECK(!load_resource_error);
  end_case();
}
static void test_flags(void) {
  char name[128];
  for (size_t i = 0; i < sizeof(oracle_flags) / sizeof(oracle_flags[0]); i++) {
    snprintf(name, sizeof(name), "flags.%s.set", oracle_flags[i].name);
    flag_case(name, oracle_flags[i].flag, true);
    snprintf(name, sizeof(name), "flags.%s.clear", oracle_flags[i].name);
    flag_case(name, oracle_flags[i].flag, false);
  }
  flag_case("flags.mixed.set", UINT32_C(0x55555555), true);
  flag_case("flags.mixed.clear", UINT32_C(0x55555555), false);
  flag_case("flags.all.set", UINT32_MAX, true);
  flag_case("flags.all.clear", UINT32_MAX, false);
  flag_case("flags.empty", 0, true);
  flag_case("flags.unknown", UINT32_C(0x80000000), false);
  for (size_t i = 0; i < sizeof(oracle_reserved) / sizeof(oracle_reserved[0]);
       i++) {
    for (unsigned enabled = 0; enabled < 2; enabled++) {
      snprintf(name, sizeof(name), "property.reject.%s.%u",
               oracle_reserved[i].name, enabled);
      begin_case(name);
      reset_flags(0);
      rejected_property = oracle_reserved[i].id;
      apply_wire_flags(expected_obj, oracle_reserved[i].flag, enabled != 0);
      CHECK(flag_calls == 1);
      CHECK(boundary_errors == 0);
      CHECK(observed_mask == oracle_reserved[i].flag);
      CHECK(load_resource_error);
      end_case();
    }
  }
  begin_case("property.latch.persist");
  reset_flags(0);
  load_resource_error = true;
  apply_wire_flags(expected_obj, LV_OBJ_FLAG_LAYOUT_2, true);
  CHECK(load_resource_error);
  end_case();
}
static lv_subject_t fake_subjects[MAX_SUBJECTS];
static unsigned deleted[MAX_SUBJECTS];
static unsigned delete_calls;
static unsigned bad_subject_calls;
static unsigned create_calls;
static bool fail_create;
static lv_subject_type_t created_type;
static unsigned int_calls;
static int32_t assigned_int;
static unsigned string_calls;
static char assigned_string[SUBJECT_STRING_BUF_SIZE];
static unsigned buffer_calls;
static char *assigned_buffer;
static char *assigned_previous;
static size_t assigned_size;
static cmd_patch_subject_reader_t installed_reader;
void cmd_patch_set_subject_reader(cmd_patch_subject_reader_t reader) {
  installed_reader = reader;
}
lv_subject_t *lv_subject_create(lv_subject_type_t type) {
  create_calls++;
  created_type = type;
  return fail_create ? NULL : &fake_subjects[0];
}
void lv_subject_delete(lv_subject_t *subject) {
  delete_calls++;
  for (size_t i = 0; i < MAX_SUBJECTS; i++) {
    if (subject == &fake_subjects[i]) {
      deleted[i]++;
      return;
    }
  }
  bad_subject_calls++;
}
void lv_subject_set_int(lv_subject_t *subject, int32_t value) {
  if (subject != &fake_subjects[0])
    bad_subject_calls++;
  int_calls++;
  assigned_int = value;
}
int32_t lv_subject_get_int(lv_subject_t *subject) {
  if (subject != &fake_subjects[0])
    bad_subject_calls++;
  return assigned_int;
}
void lv_subject_set_string_buffer_static(lv_subject_t *subject, char *buffer,
                                         char *previous, size_t size) {
  if (subject != &fake_subjects[0])
    bad_subject_calls++;
  buffer_calls++;
  assigned_buffer = buffer;
  assigned_previous = previous;
  assigned_size = size;
}
void lv_subject_set_string(lv_subject_t *subject, const char *value) {
  if (subject != &fake_subjects[0])
    bad_subject_calls++;
  string_calls++;
  snprintf(assigned_string, sizeof(assigned_string), "%s", value);
}
static void test_registry(void) {
  begin_case("subjects.reset.owned");
  memset(deleted, 0, sizeof(deleted));
  delete_calls = bad_subject_calls = 0;
  installed_reader = NULL;
  for (int i = 0; i < MAX_SUBJECTS; i++)
    subject_registry[i].subject = &fake_subjects[i];
  subject_count = MAX_SUBJECTS;
  subject_overflow = true;
  unknown_subject_reported_count = MAX_SUBJECTS;
  unknown_subject_report_saturated = true;
  reset_subject_registry();
  CHECK(installed_reader == renderer_subject_int);
  CHECK(subject_count == 0);
  CHECK(!subject_overflow);
  CHECK(unknown_subject_reported_count == 0);
  CHECK(!unknown_subject_report_saturated);
  CHECK(delete_calls == MAX_SUBJECTS);
  CHECK(bad_subject_calls == 0);
  for (int i = 0; i < MAX_SUBJECTS; i++) {
    CHECK(deleted[i] == 1);
    CHECK(subject_registry[i].subject == NULL);
  }
  end_case();
  begin_case("subjects.reset.empty");
  unsigned before = delete_calls;
  reset_subject_registry();
  CHECK(delete_calls == before);
  CHECK(subject_count == 0);
  end_case();
}
static void declaration_case(const char *name, bool string_type, bool explicit,
                             bool allocation_failure) {
  begin_case(name);
  memset(subject_registry, 0, sizeof(subject_registry));
  subject_count = 0;
  subject_overflow = false;
  load_resource_error = false;
  create_calls = int_calls = string_calls = buffer_calls = 0;
  bad_subject_calls = 0;
  assigned_buffer = assigned_previous = NULL;
  fail_create = allocation_failure;
  ui_SubjectDeclaration declaration = ui_SubjectDeclaration_init_zero;
  strcpy(declaration.name, "public_probe");
  declaration.type =
      string_type ? ui_SubjectType_SUBJECT_STRING : ui_SubjectType_SUBJECT_INT;
  if (explicit) {
    declaration.which_initial = string_type
                                    ? ui_SubjectDeclaration_string_initial_tag
                                    : ui_SubjectDeclaration_int_initial_tag;
    if (string_type)
      strcpy(declaration.initial.string_initial, "bounded value");
    else
      declaration.initial.int_initial = -12345;
  }
  uint8_t encoded[512];
  pb_ostream_t output = pb_ostream_from_buffer(encoded, sizeof(encoded));
  bool encoded_ok =
      pb_encode(&output, ui_SubjectDeclaration_fields, &declaration);
  CHECK(encoded_ok);
  if (!encoded_ok) {
    end_case();
    return;
  }
  pb_istream_t input = pb_istream_from_buffer(encoded, output.bytes_written);
  CHECK(subjects_decode_cb(&input, NULL, NULL));
  CHECK(input.bytes_left == 0);
  CHECK(create_calls == 1);
  CHECK(created_type ==
        (string_type ? LV_SUBJECT_TYPE_STRING : LV_SUBJECT_TYPE_INT));
  CHECK(strcmp(subject_registry[0].name, "public_probe") == 0);
  CHECK(subject_registry[0].type == (int)declaration.type);
  CHECK(bad_subject_calls == 0);
  CHECK(!subject_overflow);
  CHECK(load_resource_error == allocation_failure);
  CHECK(subject_count == (allocation_failure ? 0 : 1));
  if (allocation_failure) {
    CHECK(subject_registry[0].subject == NULL);
    CHECK(int_calls == 0 && string_calls == 0 && buffer_calls == 0);
  } else {
    CHECK(subject_registry[0].subject == &fake_subjects[0]);
    CHECK(int_calls == (string_type ? 0u : 1u));
    CHECK(string_calls == (string_type ? 1u : 0u));
    CHECK(buffer_calls == (string_type ? 1u : 0u));
    if (string_type) {
      CHECK(strcmp(assigned_string, explicit ? "bounded value" : "") == 0);
      CHECK(assigned_buffer == subject_registry[0].str_buf);
      CHECK(assigned_previous == subject_registry[0].str_prev_buf);
      CHECK(assigned_size == SUBJECT_STRING_BUF_SIZE);
    } else {
      CHECK(assigned_int == (explicit ? -12345 : 0));
    }
  }
  end_case();
}
/* Interpose only this renderer TU's allocation boundary, leaving nanopb and
 * libc real. Bounded typed storage makes a double-free a counted defect
 * instead of a crash that could be mistaken for an assertion kill. */
static union {
  compare_cb_data_t compare;
  color_cb_data_t color;
  max_align_t alignment;
} binding_storage;
#define compare_storage binding_storage.compare
static size_t expected_allocation_size;
static bool allocation_live;
static unsigned allocation_calls;
static unsigned free_calls;
static unsigned allocation_errors;
static bool fail_allocation;
static bool fail_observer;
static bool fail_cleanup;
static unsigned observer_calls;
static unsigned cleanup_calls;
static unsigned cleanup_remove_calls;
static bool cleanup_live;
static bool observer_live;
static bool unrelated_cleanup_live;
static max_align_t event_descriptor;
static max_align_t unrelated_event_descriptor;
static max_align_t observer_descriptor;
static lv_observer_cb_t saved_observer;
static lv_event_cb_t saved_cleanup;
static void *saved_data;
static void *probe_malloc(size_t size) {
  allocation_calls++;
  if (size != expected_allocation_size || allocation_live)
    allocation_errors++;
  allocation_live = !fail_allocation;
  return fail_allocation ? NULL : &binding_storage;
}
static void probe_free(void *data) {
  free_calls++;
  if (data != &binding_storage || !allocation_live)
    allocation_errors++;
  else
    allocation_live = false;
}
lv_observer_t *lv_subject_add_observer_obj(lv_subject_t *subject,
                                           lv_observer_cb_t callback,
                                           lv_obj_t *obj, void *data) {
  observer_calls++;
  if (subject != &fake_subjects[0] || obj != expected_obj ||
      data != &binding_storage || !allocation_live || !cleanup_live)
    allocation_errors++;
  saved_observer = callback;
  saved_data = data;
  observer_live = !fail_observer;
  /* Observe wrong callback identities without executing an unrelated function
   * with incompatible data. Color registrations apply their initial value. */
  if (observer_live && callback == color_observer_cb)
    callback((lv_observer_t *)&observer_descriptor, subject);
  return fail_observer ? NULL : (lv_observer_t *)&observer_descriptor;
}
lv_event_dsc_t *lv_obj_add_event_cb(lv_obj_t *obj, lv_event_cb_t callback,
                                    lv_event_code_t filter, void *data) {
  cleanup_calls++;
  if (obj != expected_obj || filter != LV_EVENT_DELETE ||
      data != &binding_storage || !allocation_live || cleanup_live)
    allocation_errors++;
  if (fail_cleanup)
    return NULL;
  saved_cleanup = callback;
  cleanup_live = true;
  return (lv_event_dsc_t *)&event_descriptor;
}
bool lv_obj_remove_event_dsc(lv_obj_t *obj, lv_event_dsc_t *descriptor) {
  cleanup_remove_calls++;
  if (obj == expected_obj &&
      descriptor == (lv_event_dsc_t *)&unrelated_event_descriptor &&
      unrelated_cleanup_live) {
    unrelated_cleanup_live = false;
    return true;
  }
  if (obj != expected_obj ||
      descriptor != (lv_event_dsc_t *)&event_descriptor || !cleanup_live) {
    allocation_errors++;
    return false;
  }
  cleanup_live = false;
  saved_cleanup = NULL;
  return true;
}
lv_event_dsc_t *lv_obj_get_event_dsc(lv_obj_t *obj, uint32_t index) {
  if (obj != expected_obj || index != 0)
    allocation_errors++;
  return (lv_event_dsc_t *)&unrelated_event_descriptor;
}
void *lv_event_get_user_data(lv_event_t *event) {
  (void)event;
  return saved_data;
}
lv_obj_t *lv_observer_get_target_obj(lv_observer_t *observer) {
  (void)observer;
  return expected_obj;
}
void *lv_observer_get_user_data(const lv_observer_t *observer) {
  if (observer != (lv_observer_t *)&observer_descriptor || !allocation_live ||
      saved_data != &binding_storage)
    allocation_errors++;
  return saved_data;
}
static void delete_binding(void) {
  lv_event_cb_t callback = saved_cleanup;
  observer_live = cleanup_live = false;
  saved_cleanup = NULL;
  if (callback)
    callback(NULL);
}
void lv_obj_add_state(lv_obj_t *obj, lv_state_t state) {
  (void)obj;
  (void)state;
}
void lv_obj_remove_state(lv_obj_t *obj, lv_state_t state) {
  (void)obj;
  (void)state;
}
static void comparison_case(const char *name, ui_CompareOp compare,
                            bool allocation_failure, bool observer_failure,
                            bool cleanup_failure) {
  begin_case(name);
  reset_flags(0);
  memset(subject_registry, 0, sizeof(subject_registry));
  strcpy(subject_registry[0].name, "public_probe");
  subject_registry[0].subject = &fake_subjects[0];
  subject_count = 1;
  allocation_calls = free_calls = allocation_errors = 0;
  allocation_live = false;
  expected_allocation_size = sizeof(compare_cb_data_t);
  observer_calls = cleanup_calls = cleanup_remove_calls = 0;
  observer_live = cleanup_live = false;
  unrelated_cleanup_live = true;
  fail_allocation = allocation_failure;
  fail_observer = observer_failure;
  fail_cleanup = cleanup_failure;
  saved_observer = NULL;
  saved_cleanup = NULL;
  saved_data = NULL;
  memset(&compare_storage, 0, sizeof(compare_storage));
  ui_VisibilityBinding binding = ui_VisibilityBinding_init_zero;
  strcpy(binding.subject, "public_probe");
  binding.compare = compare;
  binding.ref_value = 73;
  apply_compare_binding(expected_obj, &binding, &BIND_VISIBILITY);
  CHECK(allocation_calls == 1);
  CHECK(allocation_errors == 0);
  bool failed = allocation_failure || observer_failure || cleanup_failure;
  CHECK(load_resource_error == failed);
  CHECK(observer_calls == (allocation_failure || cleanup_failure ? 0u : 1u));
  CHECK(cleanup_calls == (allocation_failure ? 0u : 1u));
  CHECK(cleanup_remove_calls == (observer_failure ? 1u : 0u));
  CHECK(observer_live == !failed);
  CHECK(cleanup_live == !failed);
  CHECK(free_calls == (!allocation_failure && failed ? 1u : 0u));
  CHECK(allocation_live == !failed);
  CHECK(unrelated_cleanup_live);
  if (!allocation_failure && !cleanup_failure) {
    compare_cb_data_t *data = &compare_storage;
    CHECK(saved_observer == compare_binding_observer_cb);
    CHECK(data->compare == compare);
    CHECK(data->ref_value == 73);
    CHECK(data->cls == &BIND_VISIBILITY);
  }
  if (!failed) {
    CHECK(saved_cleanup == cleanup_event_cb);
    delete_binding();
    CHECK(free_calls == 1);
    CHECK(allocation_errors == 0);
    CHECK(!allocation_live);
  }
  end_case();
}
static unsigned color_set_calls;
static unsigned color_remove_calls;
static bool local_color_set;
static lv_color_t observed_color;
lv_color_t lv_color_make(uint8_t r, uint8_t g, uint8_t b) {
  lv_color_t value = {.red = r, .green = g, .blue = b};
  return value;
}
void lv_obj_set_style_text_color(lv_obj_t *obj, lv_color_t value,
                                 lv_style_selector_t selector) {
  if (obj != expected_obj || selector != LV_PART_MAIN)
    allocation_errors++;
  color_set_calls++;
  observed_color = value;
  local_color_set = true;
}
bool lv_obj_remove_local_style_prop(lv_obj_t *obj, lv_style_prop_t prop,
                                    lv_style_selector_t selector) {
  if (obj != expected_obj || prop != LV_STYLE_TEXT_COLOR ||
      selector != LV_PART_MAIN)
    allocation_errors++;
  color_remove_calls++;
  local_color_set = false;
  return true;
}
void lv_obj_set_local_style_prop(lv_obj_t *obj, lv_style_prop_t prop,
                                 lv_style_value_t value,
                                 lv_style_selector_t selector) {
  (void)obj;
  (void)prop;
  (void)value;
  (void)selector;
  allocation_errors++;
}
static void check_color(bool holds) {
  CHECK(color_set_calls == (holds ? 1u : 0u));
  CHECK(color_remove_calls == (holds ? 0u : 1u));
  CHECK(local_color_set == holds);
  if (holds) {
    CHECK(observed_color.red == 17);
    CHECK(observed_color.green == 83);
    CHECK(observed_color.blue == 201);
  }
  CHECK(allocation_errors == 0);
}
static void color_case(const char *name, ui_CompareOp op, const bool truth[3],
                       bool allocation_failure, bool observer_failure,
                       bool cleanup_failure) {
  begin_case(name);
  reset_flags(0);
  memset(subject_registry, 0, sizeof(subject_registry));
  strcpy(subject_registry[0].name, "public_probe");
  subject_registry[0].subject = &fake_subjects[0];
  subject_count = 1;
  allocation_calls = free_calls = allocation_errors = 0;
  observer_calls = cleanup_calls = cleanup_remove_calls = 0;
  allocation_live = observer_live = cleanup_live = false;
  unrelated_cleanup_live = true;
  expected_allocation_size = sizeof(color_cb_data_t);
  fail_allocation = allocation_failure;
  fail_observer = observer_failure;
  fail_cleanup = cleanup_failure;
  saved_observer = NULL;
  saved_cleanup = NULL;
  saved_data = NULL;
  memset(&binding_storage, 0, sizeof(binding_storage));
  color_set_calls = color_remove_calls = 0;
  local_color_set = false;
  assigned_int = 73;
  ui_ColorBinding binding = ui_ColorBinding_init_zero;
  strcpy(binding.when.subject, "public_probe");
  binding.when.compare = op;
  binding.when.ref_value = 73;
  binding.color.r = 17;
  binding.color.g = 83;
  binding.color.b = 201;
  apply_color_when(expected_obj, &binding);
  bool failed = allocation_failure || observer_failure || cleanup_failure;
  CHECK(allocation_calls == 1);
  CHECK(allocation_errors == 0);
  CHECK(load_resource_error == failed);
  CHECK(observer_calls == (allocation_failure || cleanup_failure ? 0u : 1u));
  CHECK(cleanup_calls == (allocation_failure ? 0u : 1u));
  CHECK(cleanup_remove_calls == (observer_failure ? 1u : 0u));
  CHECK(observer_live == !failed);
  CHECK(cleanup_live == !failed);
  CHECK(allocation_live == !failed);
  CHECK(unrelated_cleanup_live);
  CHECK(free_calls == (!allocation_failure && failed ? 1u : 0u));
  if (!allocation_failure && !cleanup_failure) {
    CHECK(saved_observer == color_observer_cb);
    CHECK(binding_storage.color.compare == op);
    CHECK(binding_storage.color.ref_value == 73);
    CHECK(binding_storage.color.color.red == 17);
    CHECK(binding_storage.color.color.green == 83);
    CHECK(binding_storage.color.color.blue == 201);
  }
  if (failed) {
    CHECK(color_set_calls == 0 && color_remove_calls == 0);
  } else {
    CHECK(saved_cleanup == cleanup_event_cb);
    check_color(truth[1]); /* LVGL invokes the callback on registration. */
    const int32_t values[3] = {INT32_MIN, 73, INT32_MAX};
    for (unsigned index = 0; index < 3; index++) {
      color_set_calls = color_remove_calls = 0;
      assigned_int = values[index];
      if (saved_observer == color_observer_cb)
        saved_observer((lv_observer_t *)&observer_descriptor,
                       &fake_subjects[0]);
      check_color(truth[index]);
    }
    delete_binding();
    CHECK(free_calls == 1);
    CHECK(allocation_errors == 0);
    CHECK(!allocation_live);
    CHECK(unrelated_cleanup_live);
  }
  end_case();
}
static void test_colors(void) {
  static const struct {
    const char *name;
    ui_CompareOp op;
    bool truth[3];
  } operators[] = {
      {"eq", ui_CompareOp_COMPARE_EQ, {false, true, false}},
      {"ne", ui_CompareOp_COMPARE_NOT_EQ, {true, false, true}},
      {"gt", ui_CompareOp_COMPARE_GT, {false, false, true}},
      {"gte", ui_CompareOp_COMPARE_GTE, {false, true, true}},
      {"lt", ui_CompareOp_COMPARE_LT, {true, false, false}},
      {"lte", ui_CompareOp_COMPARE_LTE, {true, true, false}},
  };
  const char *modes[] = {"success", "allocation_failure", "observer_failure",
                         "cleanup_failure"};
  for (unsigned op = 0; op < sizeof(operators) / sizeof(operators[0]); op++) {
    for (unsigned mode = 0; mode < sizeof(modes) / sizeof(modes[0]); mode++) {
      char name[80];
      snprintf(name, sizeof(name), "color.%s.%s", operators[op].name,
               modes[mode]);
      color_case(name, operators[op].op, operators[op].truth, mode == 1,
                 mode == 2, mode == 3);
    }
  }
}
int main(void) {
  begin_case("config.public");
  CHECK(LV_COLOR_FORMAT_DEFAULT == LV_COLOR_FORMAT_XRGB8888);
  CHECK(LV_USE_THORVG && LV_USE_THORVG_INTERNAL);
  CHECK(LV_USE_OBJ_PROPERTY);
  end_case();
  test_flags();
  test_registry();
  declaration_case("subjects.int.explicit", false, true, false);
  declaration_case("subjects.int.default", false, false, false);
  declaration_case("subjects.string.explicit", true, true, false);
  declaration_case("subjects.string.default", true, false, false);
  declaration_case("subjects.int.allocation_failure", false, true, true);
  declaration_case("subjects.string.allocation_failure", true, true, true);
  comparison_case("compare.eq.success", ui_CompareOp_COMPARE_EQ, false, false,
                  false);
  comparison_case("compare.ne.success", ui_CompareOp_COMPARE_NOT_EQ, false,
                  false, false);
  comparison_case("compare.eq.allocation_failure", ui_CompareOp_COMPARE_EQ,
                  true, false, false);
  comparison_case("compare.ne.allocation_failure", ui_CompareOp_COMPARE_NOT_EQ,
                  true, false, false);
  comparison_case("compare.eq.observer_failure", ui_CompareOp_COMPARE_EQ, false,
                  true, false);
  comparison_case("compare.ne.observer_failure", ui_CompareOp_COMPARE_NOT_EQ,
                  false, true, false);
  comparison_case("compare.eq.cleanup_failure", ui_CompareOp_COMPARE_EQ, false,
                  false, true);
  comparison_case("compare.ne.cleanup_failure", ui_CompareOp_COMPARE_NOT_EQ,
                  false, false, true);
  test_colors();
  printf("SUMMARY assertions=%u failures=%u\n", assertions, failures);
  return failures ? 1 : 0;
}
