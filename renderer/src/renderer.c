/**
 * Generic Protobuf AST -> LVGL Widget Tree Builder
 *
 * Decodes a protobuf UI AST (nanopb) and creates LVGL widgets dynamically.
 * Styles are fully resolved in the AST — no runtime token resolution.
 *
 * nanopb callback-based decoding: repeated/string fields use pb_callback_t.
 * Callbacks fire during pb_decode() in field-number order, so by the time
 * `children` (field 8) fires, `type` (field 1) through `layout` (field 7)
 * are already populated. This enables single-pass decode-and-build.
 */
#include "cmd_patch.h"
#include "commands.h"
#include "fonts.h"
#include "host_imports.h"
#include "log.h"
#include "lvgl.h"
/* Private slider struct — the press-seek callback mirrors lv_slider.c's
 * update_knob_pos position→value math VERBATIM, which needs the RAW
 * (possibly reversed) bar range and orientation the public getters
 * normalize away (precedent: theme.c/main.c include LVGL private headers
 * where exactness demands it). */
#include "lvgl/src/widgets/slider/lv_slider_private.h"
#include "renderer.h"
#include "theme_tokens.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
/* nanopb-generated headers + factory-generated proto→LVGL lookup tables */
#ifdef HAS_NANOPB
#include "ui_ast.pb.h"
#include "ui_luts.h"
#include <pb_decode.h>
#endif
/* Global subject reference — defined in main.c */
extern lv_subject_t subj_composite;
/* Read current composite index (bp * 2 + theme_dark, range 0-7) */
static int get_composite_idx(void) {
  return lv_subject_get_int(&subj_composite);
}
/* ================================================================
 * Subject registry — reactive data binding via LVGL observers
 * ================================================================ */
#define MAX_SUBJECTS 32
#define SUBJECT_STRING_BUF_SIZE 256
typedef struct {
  char name[64];
  lv_subject_t subject;
  int type; /* 0 = INT, 1 = STRING */
  /* String subjects need caller-owned buffers that outlive the subject */
  char str_buf[SUBJECT_STRING_BUF_SIZE];
  char str_prev_buf[SUBJECT_STRING_BUF_SIZE];
} subject_entry_t;
static subject_entry_t subject_registry[MAX_SUBJECTS];
static int subject_count = 0;
/* Set when a load's subject declarations overflow MAX_SUBJECTS — the extra
 * subjects are dropped and every binding referencing them would resolve to
 * NULL (a dead control). Surfaced as a load failure rather than a silently
 * half-wired screen. Reset at load start (reset_subject_registry). */
static bool subject_overflow = false;
/* Latched by any DEEP load-time site that has no widget_ctx in scope (prop /
 * binding / style application) when it hits a silent failure it could otherwise
 * only LOG_WARN + skip: a pool exhausted (scale-text / bg-image), an event/
 * observer malloc failed, or a binding resolves to a NEVER-DECLARED subject.
 * Each such site LOG_ERRORs its specifics; this flag carries the load-fail
 * signal so the load reports LOAD_ERR_DEFECTIVE instead of presenting a
 * silently degraded screen as a clean one (state-honesty). DEFECTIVE and not
 * ABORTED, deliberately: every site that latches this flag LOG_WARNs and
 * RETURNS, so the decode runs to completion and the tree is complete — the
 * caller must leave the screen up. Reset per load alongside the pool counts. */
static bool load_resource_error = false;
/* Defined beside the CmdSpec copy that needs it, below; declared here because
 * reset_subject_registry installs it and runs first. */
static bool renderer_subject_int(const char *name, int32_t *out);
static void reset_subject_registry(void) {
  /* Install the cmd patcher's subject reader beside the registry's own
   * lifecycle, so the two can never be out of step: this runs at the head of
   * every load, before any CmdSpec is decoded and long before any emit. The
   * patcher lives in a translation unit shared with reference.wasm, which owns
   * no registry — see cmd_patch.h for why the seam is a registered pointer
   * rather than a direct call. */
  cmd_patch_set_subject_reader(renderer_subject_int);
  /* Deinit all subjects AFTER widgets are destroyed (lv_obj_clean) */
  for (int i = 0; i < subject_count; i++) {
    lv_subject_deinit(&subject_registry[i].subject);
  }
  subject_count = 0;
  subject_overflow = false;
}
static subject_entry_t *find_subject(const char *name) {
  for (int i = 0; i < subject_count; i++) {
    if (strcmp(subject_registry[i].name, name) == 0) {
      return &subject_registry[i];
    }
  }
  return NULL;
}
/* Style pool for dynamic allocation */
#define MAX_STYLES 2048
static lv_style_t style_pool[MAX_STYLES];
static int style_pool_idx = 0;
static lv_style_t *alloc_style(void) {
  if (style_pool_idx >= MAX_STYLES) {
    LOG_ERROR("style pool exhausted (%d max)", MAX_STYLES);
    return NULL;
  }
  lv_style_t *s = &style_pool[style_pool_idx++];
  lv_style_init(s);
  return s;
}
static void reset_style_pool(void) {
  for (int i = 0; i < style_pool_idx; i++) {
    lv_style_reset(&style_pool[i]);
  }
  style_pool_idx = 0;
}
/* Track dynamically loaded binary/TTF fonts for cleanup — keyed by the
 * REQUESTING font name so repeated resolutions of the same name reuse the
 * instance (a per-name cache). Without the key, every style-carrying UPDATE
 * morph naming an asset font instantiated a fresh rasterizer into a slot
 * reclaimed only at renderer_cleanup: MAX_BINFONTS filled after ~14 morphs
 * and patch_pools_low then refused every later style morph (rc -4) with no
 * recovery — a full reload does not reset this registry (cached immutable
 * fonts legitimately survive reloads). The name buffer matches the P:fonts
 * path buffer in resolve_font (128, which the "P:fonts/" prefix + extension
 * shrink further), so any name that actually resolved to a file-backed font
 * always fits untruncated — a truncated key can never register. */
#define MAX_BINFONTS 16
typedef struct {
  char name[128];
  lv_font_t *font;
} binfont_entry_t;
static binfont_entry_t loaded_binfonts[MAX_BINFONTS];
static int loaded_binfont_count = 0;
/* Resolve a font name string to an LVGL font pointer */
static const lv_font_t *resolve_font(const char *name) {
  if (!name || name[0] == '\0')
    return &font_b612mono_bold_16;
  /* Custom fonts (B612Mono-Bold + Orbitron-Bold) */
  if (strcmp(name, "b612mono_bold_12") == 0)
    return &font_b612mono_bold_12;
  if (strcmp(name, "b612mono_bold_14") == 0)
    return &font_b612mono_bold_14;
  if (strcmp(name, "b612mono_bold_16") == 0)
    return &font_b612mono_bold_16;
  if (strcmp(name, "b612mono_bold_18") == 0)
    return &font_b612mono_bold_18;
  if (strcmp(name, "b612mono_bold_20") == 0)
    return &font_b612mono_bold_20;
  if (strcmp(name, "orbitron_bold_22") == 0)
    return &font_orbitron_bold_22;
  if (strcmp(name, "orbitron_bold_28") == 0)
    return &font_orbitron_bold_28;
  if (strcmp(name, "orbitron_bold_32") == 0)
    return &font_orbitron_bold_32;
  /* Montserrat fallbacks (the demo-parity set) */
  if (strcmp(name, "montserrat_14") == 0)
    return &lv_font_montserrat_14;
  if (strcmp(name, "montserrat_16") == 0)
    return &lv_font_montserrat_16;
  if (strcmp(name, "montserrat_18") == 0)
    return &lv_font_montserrat_18;
  if (strcmp(name, "montserrat_22") == 0)
    return &lv_font_montserrat_22;
  if (strcmp(name, "montserrat_24") == 0)
    return &lv_font_montserrat_24;
  /* Registry cache: a font already instantiated for this NAME is returned,
   * never re-instantiated (the same-name-morph leak — see the registry
   * comment above). */
  for (int i = 0; i < loaded_binfont_count; i++) {
    if (strcmp(loaded_binfonts[i].name, name) == 0)
      return loaded_binfonts[i].font;
  }
  /* Binary font fallback via WASI filesystem */
  if (loaded_binfont_count < MAX_BINFONTS) {
    binfont_entry_t *slot = &loaded_binfonts[loaded_binfont_count];
    char path[128];
    /* truncation is benign: a too-long path simply fails lv_binfont_create
         below (handled by the NULL check), so the return is discarded. */
    (void)snprintf(path, sizeof(path), "P:fonts/%s.bin", name);
    lv_font_t *bf = lv_binfont_create(path);
    if (bf) {
      strncpy(slot->name, name, sizeof(slot->name) - 1);
      slot->name[sizeof(slot->name) - 1] = '\0';
      slot->font = bf;
      loaded_binfont_count++;
      return bf;
    }
    /* TTF fallback: "<family>_<size>" → P:fonts/<family>.ttf rasterized
       * at <size> by TinyTTF (the canonical asset-font path — arbitrary
       * sizes from one file, no conversion toolchain). */
    const char *underscore = strrchr(name, '_');
    if (underscore && underscore != name) {
      int32_t size = (int32_t)strtol(underscore + 1, NULL, 10);
      size_t flen = (size_t)(underscore - name);
      char family[96];
      if (size > 0 && size <= 256 && flen < sizeof(family)) {
        memcpy(family, name, flen);
        family[flen] = '\0';
        (void)snprintf(path, sizeof(path), "P:fonts/%s.ttf", family);
        lv_font_t *tf = lv_tiny_ttf_create_file(path, size);
        if (tf) {
          strncpy(slot->name, name, sizeof(slot->name) - 1);
          slot->name[sizeof(slot->name) - 1] = '\0';
          slot->font = tf;
          loaded_binfont_count++;
          return tf;
        }
      }
    }
  }
  LOG_ERROR("font '%s' not found (compiled-in, P:fonts .bin, .ttf) — "
            "falling back to b612mono_bold_16",
            name);
  return &font_b612mono_bold_16;
}
/* Event callback data — stored per widget for event dispatch.
 * Strings + the cmd template are copied from the proto decode buffer (which is
 * freed by pb_release right after finalize_widget — R5a), so they must be owned
 * here. The cmd_spec_t is the PERSISTENT copy of EventBinding.cmd: when present
 * the click relays a patched cmd.* via host_command (R5b cmd-out). */
typedef struct {
  char name[UI_EVENT_NAME_BUF]; /* event keyword name — holds the full decoded
                              * EventBinding.name (a dotted command-id). One home
                              * (renderer.h UI_EVENT_NAME_BUF) governs this, the
                              * nanopb decode buffer, and the emit serializer's
                              * tag cap; NOT the old 63-char ceiling that silently
                              * truncated long composites at finalize→emit */
  /* clang-format off */
  int32_t int_value;         /* static int payload */
  bool include_widget_value; /* inject widget value at fire time */
  char set_subject[64];      /* subject to mutate (empty = host event) — subject
                              * names are bounded at 64 everywhere (the registry,
                              * SubjectDeclaration.name), so this stays 64 */
  int32_t set_value;         /* value for subject set */
  bool toggle;               /* flip 0↔1 instead of set_value */
  bool notify_host;          /* also send to host on mutation */
  lv_event_code_t trigger; /* LV_EVENT_CLICKED / VALUE_CHANGED / LONG_PRESSED */
  /* clang-format on */
  cmd_spec_t cmd; /* R5b: pre-encoded cmd.* template (.present gates) */
  /* R5b cmd-by-value: FIXED templates the widget's int value index-selects
   * among (bool-set/on-off/enum). malloc'd (NULL when absent), freed in
   * cleanup_event_data_cb; takes precedence over `cmd` when count > 0. */
  cmd_spec_t *cmd_by_value;
  uint32_t cmd_by_value_count;
} event_cb_data_t;
/* Lock the whole EventBinding.name chain to the nanopb decode buffer. The name
 * flows decode(ui_EventBinding.name) → cache(event_cb_data_t.name, this buffer)
 * → serialize(json_append_str's UI_EVENT_NAME_BUF tag cap in main.c); all three
 * share renderer.h's UI_EVENT_NAME_BUF, and this assert ties that constant (via
 * the cache) to the generated struct. If the nanopb budget grows past it, a long
 * command-id truncates silently somewhere on the chain and the host receives a
 * tag it cannot match — a wrong-command emission with no decode error. The assert
 * makes that a BUILD failure instead. */
_Static_assert(sizeof(((event_cb_data_t *)0)->name) >=
                   sizeof(((ui_EventBinding *)0)->name),
               "event_cb_data_t.name (UI_EVENT_NAME_BUF) must hold the full "
               "ui_EventBinding.name");
/* Get the current integer value from a widget (for include_widget_value).
 * Returns 0 for widget types that don't have a meaningful value. */
static int32_t get_widget_int_value(lv_obj_t *obj) {
  if (!obj)
    return 0;
  if (lv_obj_check_type(obj, &lv_slider_class))
    return lv_slider_get_value(obj);
  if (lv_obj_check_type(obj, &lv_arc_class))
    return lv_arc_get_value(obj);
  if (lv_obj_check_type(obj, &lv_bar_class))
    return lv_bar_get_value(obj);
  if (lv_obj_check_type(obj, &lv_spinbox_class))
    return lv_spinbox_get_value(obj);
  if (lv_obj_check_type(obj, &lv_dropdown_class))
    return (int32_t)lv_dropdown_get_selected(obj);
  if (lv_obj_check_type(obj, &lv_roller_class))
    return (int32_t)lv_roller_get_selected(obj);
  /* Switch/checkbox: return checked state as 0/1 */
  if (lv_obj_has_flag(obj, LV_OBJ_FLAG_CHECKABLE))
    return (int32_t)lv_obj_has_state(obj, LV_STATE_CHECKED);
  return 0;
}
/* The envelope's `event` string for a binding's trigger — the inverse of the
 * decode-time EventTrigger→lv_event_code_t mapping below (default CLICKED),
 * so envelope and attachment can never disagree on what fired. Spelling is
 * the envelope-v1 trigger enum (kebab-case — the host-membrane validators
 * and the protogen JSON Schema pin the same strings). */
/* The closed set of trigger names this renderer emits, named once so the assert
 * below and the switch cannot disagree. Adding a case means adding a macro here
 * and to the assert — which is the point: a name longer than
 * UI_EVENT_TRIGGER_CHARS then fails the BUILD instead of clipping silently at
 * the host membrane. */
#define TRIGGER_NAME_VALUE_CHANGED "value-changed"
#define TRIGGER_NAME_LONG_PRESSED "long-pressed"
#define TRIGGER_NAME_CLICKED "clicked"
_Static_assert(UI_EVENT_TRIGGER_CHARS >=
                       sizeof(TRIGGER_NAME_VALUE_CHANGED) - 1u &&
                   UI_EVENT_TRIGGER_CHARS >=
                       sizeof(TRIGGER_NAME_LONG_PRESSED) - 1u &&
                   UI_EVENT_TRIGGER_CHARS >= sizeof(TRIGGER_NAME_CLICKED) - 1u,
               "UI_EVENT_TRIGGER_CHARS must hold every event_trigger_name() "
               "return in full");
static const char *event_trigger_name(lv_event_code_t trigger) {
  switch (trigger) {
  case LV_EVENT_VALUE_CHANGED:
    return TRIGGER_NAME_VALUE_CHANGED;
  case LV_EVENT_LONG_PRESSED:
    return TRIGGER_NAME_LONG_PRESSED;
  default:
    return TRIGGER_NAME_CLICKED;
  }
}
static void button_event_cb(lv_event_t *e) {
  event_cb_data_t *data = (event_cb_data_t *)lv_event_get_user_data(e);
  if (!data)
    return;
  int32_t int_value = data->int_value;
  /* Inject widget's current value if requested */
  if (data->include_widget_value) {
    lv_obj_t *obj = lv_event_get_current_target(e);
    int_value = get_widget_int_value(obj);
  }
  /* Subject mutation (local state, no host round-trip unless notify_host) */
  if (data->set_subject[0] != '\0') {
    subject_entry_t *entry = find_subject(data->set_subject);
    if (entry && entry->type == 0) { /* INT subject only */
      int32_t new_val;
      if (data->toggle) {
        new_val = (lv_subject_get_int(&entry->subject) == 0) ? 1 : 0;
      } else {
        new_val = data->set_value;
      }
      /* Guard: only notify if value actually changed */
      if (lv_subject_get_int(&entry->subject) != new_val) {
        lv_subject_set_int(&entry->subject, new_val);
      }
      /* If toggle + include_widget_value, report the new value */
      if (data->toggle) {
        int_value = new_val;
      }
    }
  }
  /* Relay the device command to the host if: (a) no subject mutation, or
   * (b) notify_host is set. R5b: a value-widget click carries a pre-encoded
   * cmd.* template (EventBinding.cmd) the patcher overwrites with the widget's
   * live value and relays as OPAQUE bytes via host_command. A widget click has
   * no NDC point, so x/y are 0 (the template carries only a WIDGET_VALUE
   * varint slot). An event with no template (.present == false) is a pure
   * local subject mutation — there is nothing to send. */
  if (data->set_subject[0] == '\0' || data->notify_host) {
    if (data->cmd_by_value_count > 0) {
      /* R5b cmd-by-value: the widget's int value INDEX-selects a FIXED
           * template (no slot rewrite). An out-of-range index (a value with no
           * template) emits NOTHING — fail loud, never a wild read. */
      if (int_value < 0 || (uint32_t)int_value >= data->cmd_by_value_count) {
        LOG_ERROR("cmd_by_value index %d out of range [0,%u) — no emit",
                  (int)int_value, (unsigned)data->cmd_by_value_count);
      } else {
        (void)cmd_patch_emit(&data->cmd_by_value[int_value], 0.0, 0.0,
                             int_value);
      }
    } else {
      (void)cmd_patch_emit(&data->cmd, 0.0, 0.0, int_value);
    }
    /* The named-event lane (host_event): a nonempty EventBinding.name emits
     * the self-describing envelope IN ADDITION to the cmd template path
     * above (cmd first on the wire; its bytes are unchanged). Same gate as
     * the cmd relay — a named local subject mutation without notify_host
     * stays local. Independent of template validity: the event FIRED, so a
     * template-less or out-of-range-index binding still reports it. origin
     * is the firing widget's uid (finalize_widget mirrors it into user_data;
     * 0 when the node carried none). */
    if (data->name[0] != '\0') {
      lv_obj_t *target = lv_event_get_current_target(e);
      uint32_t origin = (uint32_t)(uintptr_t)lv_obj_get_user_data(target);
      (void)controls_emit_host_event(
          data->name, event_trigger_name(data->trigger), origin, int_value);
    }
  }
}
/* Cleanup callback: free a flat heap struct with no owned pointers (the
 * compare_cb_data_t visibility/checked_when observers). event_cb_data_t uses
 * cleanup_event_data_cb instead — it owns the malloc'd cmd_by_value array. */
static void cleanup_event_cb(lv_event_t *e) { free(lv_event_get_user_data(e)); }
/* Cleanup for an event_cb_data_t: free the owned cmd_by_value array (NULL when
 * absent — free(NULL) is a no-op) before the struct itself. Kept distinct from
 * the flat cleanup_event_cb so the compare-observer path never mis-frees a
 * garbage pointer by reading a non-event struct as an event_cb_data_t. */
static void cleanup_event_data_cb(lv_event_t *e) {
  event_cb_data_t *data = (event_cb_data_t *)lv_event_get_user_data(e);
  if (data)
    free(data->cmd_by_value);
  free(data);
}
#ifdef HAS_NANOPB
/* ================================================================
 * Callback-based decode contexts
 *
 * nanopb callback fields (pb_callback_t) fire during pb_decode().
 * Proto field ordering guarantees: type(1) < layout(7) < children(8)
 * so static fields are populated before callback fields fire.
 * ================================================================ */
/* Binding entry — decoded from map<string,string> bindings */
#define MAX_BINDINGS_PER_WIDGET 4
typedef struct {
  char key[64];   /* e.g. "text", "value", "checked" */
  char value[64]; /* subject name, e.g. "brightness" */
} binding_entry_t;
/* Bind format entry — decoded from map<string,string> bind_formats */
typedef struct {
  char key[64];    /* e.g. "text" */
  char value[256]; /* e.g. "%d%%" */
} bind_format_entry_t;
/* Styles attached to one widget during decode — recorded so `bare`
 * (field 37, streaming AFTER style_groups) can strip the theme styles
 * and RE-apply exactly these. */
#define MAX_STYLES_PER_WIDGET 16
typedef struct {
  lv_style_t *style;
  uint32_t selector;
} added_style_t;
/* ================================================================
 * uid → lv_obj registry (tree patching)
 *
 * Codegen assigns every node a stable uid (FNV-1a-32 of its identity
 * path); finalize_widget mirrors it into lv_obj user_data and this
 * bounded registry so ScreenPatch ops can address live widgets. Reset
 * per full build; maintained incrementally by patch ops. Distinct from
 * the host-proxy registry (proxies are an 8-slot pool with their own
 * lifecycle) — uids cover ALL nodes.
 * ================================================================ */
#define MAX_UID_NODES 1024
typedef struct {
  uint32_t uid;
  lv_obj_t *obj;
  /* The node's widget type at registration. An UPDATE_PROPS op carries a
   * `:type`; a mismatch vs this registered type means the patch addresses the
   * wrong widget class (a differ/codegen contract violation) — decoding props
   * for the wrong class corrupts the tree, so the UPDATE arm rejects it (-5).
   */
  int32_t widget_type;
  /* Styles the builder attached to this node (style group decode) — the
   * record that makes the in-place style morph possible: an UPDATE op
   * carrying style_groups removes exactly these, then decodes the new
   * groups into fresh pool slots (old slots are reclaimed only by full
   * reload — accounted monotonic growth). */
  added_style_t styles[MAX_STYLES_PER_WIDGET];
  int style_count;
} uid_entry_t;
static uid_entry_t uid_registry[MAX_UID_NODES];
static int uid_count;
static lv_obj_t *find_uid_obj(uint32_t uid) {
  for (int i = 0; i < uid_count; i++) {
    if (uid_registry[i].uid == uid)
      return uid_registry[i].obj;
  }
  return NULL;
}
/* Register a uid → obj pair; returns 0 ok, -1 on overflow/duplicate
 * (both are codegen/differ contract violations — fail loud). */
static int register_uid(uint32_t uid, lv_obj_t *obj, int32_t widget_type) {
  if (find_uid_obj(uid)) {
    LOG_ERROR("uid %u registered twice", (unsigned)uid);
    return -1;
  }
  if (uid_count >= MAX_UID_NODES) {
    LOG_ERROR("uid registry full (%d max)", MAX_UID_NODES);
    return -1;
  }
  uid_registry[uid_count].uid = uid;
  uid_registry[uid_count].obj = obj;
  uid_registry[uid_count].widget_type = widget_type;
  uid_registry[uid_count].style_count = 0;
  uid_count++;
  return 0;
}
static uid_entry_t *find_uid_entry(uint32_t uid) {
  for (int i = 0; i < uid_count; i++) {
    if (uid_registry[i].uid == uid)
      return &uid_registry[i];
  }
  return NULL;
}
static void unregister_uid_obj(const lv_obj_t *obj) {
  for (int i = 0; i < uid_count; i++) {
    if (uid_registry[i].obj == obj) {
      uid_registry[i] = uid_registry[uid_count - 1];
      uid_count--;
      return;
    }
  }
}
/* ================================================================
 * SYNC C1: per-dropdown enum value->index map.
 *
 * A value-bound dropdown's subject holds the device enum NUMBER, but the option
 * list is 1-based (enum-options drops _UNSPECIFIED / :not-in), so the number is
 * NOT the option index. This sparse map (the decoded
 * DropdownProps.option_values, SAME order as the options) lets
 * dropdown_value_observer_cb resolve number -> index. Only value-bound enum
 * dropdowns register; register is idempotent (find-or-update, so an
 * UPDATE_PROPS morph does not double-register); reset on full load,
 * swap-removed with the widget in unregister_subtree (so a morph that drops a
 * dropdown drops its map). Keyed by obj. A miss when the OBSERVER fires (obj
 * deleted mid-reload) is a safe no-op; a miss at BIND time (registry overflow
 * past MAX) degrades that one dropdown to the pre-fix lv_dropdown_bind_value
 * number-as-index, LOGGED — never a crash, but not silent.
 * ================================================================ */
#define MAX_DROPDOWN_VALUE_MAPS 32
typedef struct {
  const lv_obj_t *obj;
  int32_t values[16];
  pb_size_t count;
} dropdown_value_map_t;
static dropdown_value_map_t dropdown_value_maps[MAX_DROPDOWN_VALUE_MAPS];
static int dropdown_value_map_count;
static const dropdown_value_map_t *
find_dropdown_value_map(const lv_obj_t *obj) {
  for (int i = 0; i < dropdown_value_map_count; i++) {
    if (dropdown_value_maps[i].obj == obj)
      return &dropdown_value_maps[i];
  }
  return NULL;
}
static void register_dropdown_value_map(const lv_obj_t *obj,
                                        const int32_t *values,
                                        pb_size_t count) {
  if (count == 0)
    return;
  /* Idempotent under an UPDATE_PROPS morph: apply_widget_props re-enters the
   * dropdown_props case on the LIVE obj when a hot-reload edits options/
   * direction (the payload retains the arm), so a plain append would
   * double-register and strand a dangling-obj entry after the widget is later
   * removed. Update the existing entry in place instead — mirroring
   * register_uid's morph guard. */
  dropdown_value_map_t *m = NULL;
  for (int i = 0; i < dropdown_value_map_count; i++) {
    if (dropdown_value_maps[i].obj == obj) {
      m = &dropdown_value_maps[i];
      break;
    }
  }
  if (m == NULL) {
    if (dropdown_value_map_count >= MAX_DROPDOWN_VALUE_MAPS) {
      LOG_ERROR("dropdown value-map registry full (%d max)",
                MAX_DROPDOWN_VALUE_MAPS);
      return;
    }
    m = &dropdown_value_maps[dropdown_value_map_count++];
    m->obj = obj;
  }
  m->count = count > 16 ? 16 : count;
  memcpy(m->values, values, m->count * sizeof(int32_t));
}
static void unregister_dropdown_value_map(const lv_obj_t *obj) {
  for (int i = 0; i < dropdown_value_map_count; i++) {
    if (dropdown_value_maps[i].obj == obj) {
      dropdown_value_maps[i] =
          dropdown_value_maps[dropdown_value_map_count - 1];
      dropdown_value_map_count--;
      return;
    }
  }
}
/* Resolve a subject NAME and read its current int — the one seam through which
 * cmd_patch.c (which does not include LVGL) reaches the registry. False when
 * the name names nothing, or names a STRING subject: a string cannot become a
 * numeric wire leaf, so the caller refuses the emit rather than substituting a
 * default. Mirrors the INT-only restriction every other binding already has
 * (visibility / checked_when / enabled_when all decline a STRING subject). */
static bool renderer_subject_int(const char *name, int32_t *out) {
  if (!name || name[0] == '\0' || !out)
    return false;
  subject_entry_t *entry = find_subject(name);
  if (!entry || entry->type != 0)
    return false;
  *out = lv_subject_get_int(&entry->subject);
  return true;
}
/* A cmd patch's SUBJECT_VALUE slot resolves against the SAME registry as every
 * other subject reference, so it meets the SAME ordering: Screen.subjects is
 * field 2 and streams after the tree, so the name cannot be looked up while the
 * spec is being copied. Only the RESOLVABILITY check is deferred here — the
 * stored slot owns its copy of the name and resolves at fire time, exactly as
 * pending_event_subject does for EventBinding.set_subject. Without this check a
 * form wired to a misspelled subject would present as a perfectly healthy
 * screen whose Apply button silently refuses on every press. */
#define MAX_PENDING_PATCH_SUBJECT 32
typedef struct {
  char subject[64];
  char command_id[64]; /* truncated for the diagnostic only — names the form */
} pending_patch_subject_t;
static pending_patch_subject_t pending_patch_subject[MAX_PENDING_PATCH_SUBJECT];
static int pending_patch_subject_count;
/* ================================================================
 * R5b cmd-out: copy a decoded CmdSpec into PERSISTENT storage.
 *
 * EventBinding.cmd and WidgetNode.gestures are FT_POINTER — nanopb malloc's
 * them during decode, and the decode sites pb_release them right after
 * finalize_widget. So finalize MUST copy the template bytes + patch descriptors
 * out of the nanopb copy (which is about to be freed) into a flat, malloc-free
 * cmd_spec_t that outlives it: a widget's into its event_cb_data_t, a gesture
 * surface's into the main.c drain global. Returns 0 ok, -1 on a malformed spec.
 * ================================================================ */
static int cmd_spec_copy_from_proto(cmd_spec_t *dst, const ui_CmdSpec *src) {
  memset(dst, 0, sizeof(*dst));
  uint32_t tlen = src->root_template.size;
  if (tlen > CMD_PATCH_TEMPLATE_CAP) {
    LOG_ERROR("cmd spec template %u exceeds cap %u (cmd %s)", (unsigned)tlen,
              CMD_PATCH_TEMPLATE_CAP, src->command_id);
    return -1;
  }
  if (src->patches_count > CMD_PATCH_MAX_PATCHES) {
    LOG_ERROR("cmd spec has %u patches > max %u (cmd %s)",
              (unsigned)src->patches_count, CMD_PATCH_MAX_PATCHES,
              src->command_id);
    return -1;
  }
  dst->template_len = tlen;
  memcpy(dst->template, src->root_template.bytes, tlen);
  dst->patch_count = src->patches_count;
  for (pb_size_t i = 0; i < src->patches_count; i++) {
    /* §7/§8: the .pb arrives from an untrusted host — validate each slot's
       * bounds at the decode boundary (overflow-safe) BEFORE storing it, so a
       * crafted byte_offset/byte_width can never reach the emit-time write. */
    if (!cmd_patch_slot_in_bounds(src->patches[i].byte_offset,
                                  src->patches[i].byte_width, tlen)) {
      LOG_ERROR("cmd spec slot [%u+%u) overflows template %u (cmd %s)",
                (unsigned)src->patches[i].byte_offset,
                (unsigned)src->patches[i].byte_width, (unsigned)tlen,
                src->command_id);
      memset(dst, 0, sizeof(*dst));
      return -1;
    }
    /* The kind/subject contract, BOTH directions. A SUBJECT_VALUE slot with no
     * name can only ever refuse at emit; a non-subject slot carrying one is a
     * producer that believes it wired a form field and did not. Neither is
     * recoverable by guessing, and both are silent at every other layer. */
    bool is_subject_kind =
        src->patches[i].kind == ui_PatchKind_PATCH_KIND_SUBJECT_VALUE;
    bool has_name = src->patches[i].subject[0] != '\0';
    if (is_subject_kind != has_name) {
      LOG_ERROR("cmd spec slot %u: kind %u %s a subject name (cmd %s)",
                (unsigned)i, (unsigned)src->patches[i].kind,
                is_subject_kind ? "requires" : "must not carry",
                src->command_id);
      memset(dst, 0, sizeof(*dst));
      return -1;
    }
    dst->patches[i].byte_offset = src->patches[i].byte_offset;
    dst->patches[i].byte_width = src->patches[i].byte_width;
    dst->patches[i].kind = (uint32_t)src->patches[i].kind;
    dst->patches[i].wire_scale = src->patches[i].wire_scale;
    dst->patches[i].encoding = (uint32_t)src->patches[i].encoding;
    if (has_name) {
      strncpy(dst->patches[i].subject, src->patches[i].subject,
              sizeof(dst->patches[i].subject) - 1);
      dst->patches[i].subject[sizeof(dst->patches[i].subject) - 1] = '\0';
      /* Queue the resolvability check; the registry is not populated yet. A
       * full queue is itself a defect rather than a reason to skip silently. */
      if (pending_patch_subject_count >= MAX_PENDING_PATCH_SUBJECT) {
        LOG_ERROR("pending patch-subject queue full (%d) — cannot verify '%s'",
                  MAX_PENDING_PATCH_SUBJECT, dst->patches[i].subject);
        load_resource_error = true;
      } else {
        pending_patch_subject_t *q =
            &pending_patch_subject[pending_patch_subject_count++];
        strncpy(q->subject, dst->patches[i].subject, sizeof(q->subject) - 1);
        q->subject[sizeof(q->subject) - 1] = '\0';
        strncpy(q->command_id, src->command_id, sizeof(q->command_id) - 1);
        q->command_id[sizeof(q->command_id) - 1] = '\0';
      }
    }
  }
  dst->present = true;
  return 0;
}
/* The deferred half of the check above, run once the registry is populated. A
 * cmd patch naming a never-declared (or non-INT) subject is a dead form field:
 * the screen renders, the Apply button enables, and every press refuses. Fail
 * the load DEFECTIVE, exactly as a binding to an unknown subject already does,
 * rather than presenting it as clean. */
static void apply_patch_subject(const pending_patch_subject_t *q) {
  subject_entry_t *entry = find_subject(q->subject);
  if (!entry) {
    LOG_ERROR("cmd patch references unknown subject '%s' (cmd %s)", q->subject,
              q->command_id);
    load_resource_error = true;
  } else if (entry->type != 0) {
    LOG_ERROR("cmd patch subject '%s' is not INT — a cmd slot cannot read a "
              "STRING subject (cmd %s)",
              q->subject, q->command_id);
    load_resource_error = true;
  }
}
/* Harness-only: run a crafted CmdSpec `.pb` through the untrusted decode
 * boundary (nanopb + cmd_spec_copy_from_proto) and report which layer
 * accepted/rejected it. See renderer.h for the return contract. ui_CmdSpec is
 * fully static (command_id[128] + a static root_template bytes field + a
 * patches[2] array — no FT_POINTER field), so no pb_release is needed after a
 * decode. */
int32_t cmd_spec_decode_probe(const uint8_t *data, uint32_t len) {
  ui_CmdSpec spec = ui_CmdSpec_init_zero;
  pb_istream_t stream = pb_istream_from_buffer(data, len);
  if (!pb_decode(&stream, ui_CmdSpec_fields, &spec))
    return -2; /* nanopb rejected: template > 128B cap, or > 2 patches */
  cmd_spec_t dst;
  return (int32_t)cmd_spec_copy_from_proto(&dst, &spec);
}
/* Morph mode — set ONLY while an UPDATE_PROPS op re-applies props to a
 * live object. Gates the value-bearing setters (the form-state policy:
 * a wire default on a value-bearing field means 'authored value
 * unchanged — keep live user state') and the chart values-only morph.
 * Full loads are untouched (demo-parity stays bit-exact). */
static bool morph_in_progress;
/* Tabview child staging: children (field 8) stream BEFORE the
 * tabview_props oneof arm (field 38), so when a tabview's children build,
 * the tab pages do not exist yet (tab_names are still undecoded). The
 * children therefore build under a hidden staging container and are
 * reparented into their tab page / the tab bar once tabview_props is
 * decoded (apply_tabview). The staging container is a HIDDEN +
 * IGNORE_LAYOUT sibling inside the live tree (never a detached screen),
 * so an aborted decode cannot leak it past the next lv_obj_clean. */
#define MAX_TABVIEW_CHILDREN 16
/* Max widget-tree nesting the decoder will follow. children_decode_cb recurses
 * (each child re-arms children_decode_cb), so a crafted deeply-nested .pb would
 * blow the C stack; past this depth decode fails loudly instead. Real screens
 * are shallow (< 10) — the codegen mirror (lvgl-codegen.renderer-caps) fails
 * emit well before a legit screen could approach it.
 *
 * This cap is only enforceable if the stack can actually REACH it: each level
 * costs ~4.8 KB of C stack, so the link must reserve room for all of them.
 * wasm.mk's -Wl,-z,stack-size carries that obligation and explains the sum. */
#define MAX_DECODE_DEPTH 32
/* Max children one live parent may hold. LVGL stores the count in a uint16_t
 * (lv_obj_spec_attr_t::child_cnt), so the 65536th sibling wraps it to zero and
 * silently orphans the whole child array — corruption, not a refusal.
 *
 * The bound is on the LIVE tree, not on one decode invocation, because the two
 * ways to reach the wrap are different shapes: one .pb declaring 65536 siblings
 * (load path), and 65536 INSERT ops each adding one child to the same parent
 * (patch path). A per-decode counter catches only the first; a live check
 * catches both and cannot wedge a long-lived module the way an accumulating
 * counter does — nothing has to credit deletions back.
 *
 * 4096 sits above every legitimate fan-out with room to spare: the renderer's
 * own widest fixture is vc_trunc at 780 siblings, and the shipped screen corpus
 * peaks at 78 nodes per SCREEN. decode_limits.rs pins that 780 so this cap can
 * never be lowered under a fixture the battery already depends on. */
#define MAX_LIVE_CHILDREN 4096
/* Context for building a single widget node */
typedef struct widget_ctx {
  lv_obj_t *parent;    /* LVGL parent to create widget under */
  lv_obj_t *self;      /* Created widget (NULL until lazily created) */
  ui_WidgetNode *node; /* Points to the node being decoded */
  char text[256];      /* Collected from text callback */
  int error;
  int depth; /* nesting level from the root (0); bounded by MAX_DECODE_DEPTH */
  /* Binding data collected during decode */
  binding_entry_t bindings[MAX_BINDINGS_PER_WIDGET];
  int binding_count;
  bind_format_entry_t bind_formats[MAX_BINDINGS_PER_WIDGET];
  int bind_format_count;
  added_style_t added_styles[MAX_STYLES_PER_WIDGET];
  int added_style_count;
  /* Tabview only: staging container + per-child tab-bar slot flags
   * (index = staged child order). */
  lv_obj_t *tab_staging;
  bool tab_in_bar[MAX_TABVIEW_CHILDREN];
  int tab_staged_count;
} widget_ctx_t;
/* Context for decoding sparse style-group variants. The wire ships the
 * base (variant_index 0, always first) plus ONLY the composite indices
 * whose complete prop set differs from the base; an absent index renders
 * exactly as the base. The base style is held un-attached until the
 * group ends: the exact-match entry wins, otherwise the base attaches. */
typedef struct {
  lv_obj_t *obj;          /* Widget to bind styles to */
  widget_ctx_t *wctx;     /* Owning widget ctx (records added styles) */
  ui_StyleGroup *group;   /* Group being decoded (state_selector in field 1) */
  lv_style_t *base_style; /* Decoded variant-0 style, pending attach */
  bool exact_match;       /* An entry for the active composite idx streamed */
} style_group_ctx_t;
/* ================================================================
 * Deferred binding/visibility attachment
 *
 * Screen.subjects is field 2 — it streams AFTER root (field 1), so any
 * binding/visibility resolved DURING the tree decode would look up
 * subjects that are not registered yet. Attachments are therefore queued
 * here and flushed after pb_decode completes.
 * ================================================================ */
#define MAX_PENDING_BINDINGS 64
#define MAX_PENDING_VISIBILITY 32
#define MAX_PENDING_CHECKED 32
#define MAX_PENDING_ENABLED 32
#define MAX_PENDING_COLOR 32
#define MAX_PENDING_EVENT_SUBJECT 32
typedef struct {
  lv_obj_t *obj;
  ui_WidgetType wtype;
  binding_entry_t bindings[MAX_BINDINGS_PER_WIDGET];
  int binding_count;
  bind_format_entry_t bind_formats[MAX_BINDINGS_PER_WIDGET];
  int bind_format_count;
} pending_bindings_t;
typedef struct {
  lv_obj_t *obj;
  ui_VisibilityBinding vis;
} pending_visibility_t;
/* checked_when reuses the VisibilityBinding shape; like visibility it
 * attaches only after Screen.subjects (field 2) has streamed. */
typedef struct {
  lv_obj_t *obj;
  ui_VisibilityBinding bind;
} pending_checked_t;
/* enabled_when reuses the VisibilityBinding shape (inverted polarity — it
 * toggles LV_STATE_DISABLED); same post-subjects deferred attach. */
typedef struct {
  lv_obj_t *obj;
  ui_VisibilityBinding bind;
} pending_enabled_t;
/* color_when carries the ColorBinding (VisibilityBinding shape + target
 * color); same post-subjects deferred attach. */
typedef struct {
  lv_obj_t *obj;
  ui_ColorBinding bind;
} pending_color_t;
/* An EventBinding's set_subject resolves against the SAME registry, so it is
 * subject to the same ordering: the name a click will mutate cannot be looked
 * up while finalize_widget attaches the callback. Unlike the three queues
 * above nothing is deferred-ATTACHED here (the callback owns its own copy of
 * the name and resolves at fire time) — only the RESOLVABILITY check is
 * deferred, which is why the entry carries just the name plus the widget uid
 * that names the offender in the diagnostic. */
typedef struct {
  char subject[64];
  uint32_t uid;
} pending_event_subject_t;
/* Deferred tabview activation: a tabview finalizes while its ANCESTORS'
 * style groups are still undecoded (a parent's children stream before its
 * styles), so geometry at that point is not final — an early set_active
 * scrolls by a stale page width, and the scroll-snap machinery re-snaps
 * to tab 0 on the first refresh. Activation is therefore queued and
 * flushed after pb_decode completes, when set_active's own
 * lv_obj_update_layout resolves the FINAL page geometry. */
#define MAX_PENDING_TABVIEW 8
typedef struct {
  lv_obj_t *tabview;
  uint32_t active_index;
} pending_tabview_t;
static pending_bindings_t pending_bindings[MAX_PENDING_BINDINGS];
static int pending_bindings_count;
static pending_visibility_t pending_visibility[MAX_PENDING_VISIBILITY];
static int pending_visibility_count;
static pending_checked_t pending_checked[MAX_PENDING_CHECKED];
static int pending_checked_count;
static pending_enabled_t pending_enabled[MAX_PENDING_ENABLED];
static int pending_enabled_count;
static pending_color_t pending_color[MAX_PENDING_COLOR];
static int pending_color_count;
static pending_event_subject_t pending_event_subject[MAX_PENDING_EVENT_SUBJECT];
static int pending_event_subject_count;
static pending_tabview_t pending_tabview[MAX_PENDING_TABVIEW];
static int pending_tabview_count;
/* Context for decoding one sparse StyleVariant's properties. variant_index
 * (field 1) streams before the properties callback fires — our emitters
 * serialize in field order — so each property can decide wanted-vs-drain
 * from entry->variant_index and the style is allocated lazily on the
 * first wanted property (an unwanted variant never touches the pool). */
typedef struct {
  const ui_StyleVariant *entry; /* Variant being decoded (index in field 1) */
  lv_style_t *style;            /* Lazily allocated on first wanted prop */
} style_variant_ctx_t;
/* Apply the node's Layout message (flex flow + alignment). Called at
 * widget creation, and AGAIN after a `bare` strip: lv_obj_set_flex_flow
 * writes LOCAL styles that lv_obj_remove_style_all erases, and the
 * declarative contract is "bare strips THEME styles — everything
 * declared on the node still applies". */
static void apply_node_layout(lv_obj_t *obj, const ui_WidgetNode *node) {
  if (!node->has_layout)
    return;
  uint32_t flow = (uint32_t)node->layout.flow;
  if (flow > 0 && flow < FLEX_FLOW_LUT_SIZE) {
    lv_obj_set_flex_flow(obj, flex_flow_lut[flow]);
  }
  /* Apply flex alignment if non-default */
  if (node->layout.main_place != 0 || node->layout.cross_place != 0 ||
      node->layout.track_place != 0) {
    lv_obj_set_flex_align(obj, (lv_flex_align_t)node->layout.main_place,
                          (lv_flex_align_t)node->layout.cross_place,
                          (lv_flex_align_t)node->layout.track_place);
  }
}
/* Host-proxy default look: the proxy root is a plain lv_obj, so the theme's
 * heavyweight panel card (opaque fill, size-tier padding, card radius) lands
 * on a widget whose job is to be a lean drag/resize frame over
 * HOST-COMPOSITED content. Override the heavy parts — transparent fill,
 * control-tier pad, panel-tier radius — and keep the BORDER themed (the
 * theme's panel border already carries the edge tone per family/mode).
 * A NORMAL style attached at CREATE time, deliberately NOT
 * lv_obj_set_style_* locals: a local style outranks every added style,
 * which would make wire-authored StyleProperties on the proxy root silently
 * inert. Later-added styles insert ahead of earlier ones, so this default
 * beats the theme (applied inside create) while the authored group styles
 * (attached later during decode) beat the default — pad/bg/radius stay
 * AUTHORABLE, pinned by the harness's host_proxy_authored oracle. Applied
 * identically under every theme family, so family parity holds by
 * construction. */
static void proxy_apply_default_style(lv_obj_t *obj) {
  static lv_style_t proxy_default_style;
  static bool proxy_default_inited = false;
  if (!proxy_default_inited) {
    lv_style_init(&proxy_default_style);
    lv_style_set_bg_opa(&proxy_default_style, LV_OPA_TRANSP);
    lv_style_set_pad_all(&proxy_default_style, THEME_PAD_CONTROL);
    lv_style_set_radius(&proxy_default_style, THEME_RADIUS_PANEL);
    proxy_default_inited = true;
  }
  lv_obj_add_style(obj, &proxy_default_style, 0);
}
/* ================================================================
 * Lazy widget creation
 *
 * Called before processing children or style_groups. By this point,
 * type (field 1) and layout (field 7) are already decoded.
 * ================================================================ */
static lv_obj_t *ensure_widget(widget_ctx_t *ctx) {
  if (ctx->self)
    return ctx->self;
  switch (ctx->node->type) {
  case ui_WidgetType_WIDGET_BUTTON:
    ctx->self = lv_button_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_LABEL:
    ctx->self = lv_label_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_SLIDER:
    ctx->self = lv_slider_create(ctx->parent);
    /* Replace the vendored constructor's inherited pad — renderer.h. */
    lv_obj_set_ext_click_area(ctx->self, LV_DPX(SLIDER_EXT_CLICK_PX));
    break;
  case ui_WidgetType_WIDGET_IMAGE:
    ctx->self = lv_image_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_ARC:
    ctx->self = lv_arc_create(ctx->parent);
    /* Replace the vendored constructor's inherited pad — renderer.h. */
    lv_obj_set_ext_click_area(ctx->self, LV_DPX(ARC_EXT_CLICK_PX));
    break;
  case ui_WidgetType_WIDGET_BAR:
    ctx->self = lv_bar_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_SWITCH:
    ctx->self = lv_switch_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_CHECKBOX:
    ctx->self = lv_checkbox_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_DROPDOWN:
    ctx->self = lv_dropdown_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_ROLLER:
    ctx->self = lv_roller_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_TEXTAREA:
    ctx->self = lv_textarea_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_SPINBOX:
    ctx->self = lv_spinbox_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_SPINNER:
    ctx->self = lv_spinner_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_LED:
    ctx->self = lv_led_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_LINE:
    ctx->self = lv_line_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_SCALE:
    ctx->self = lv_scale_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_TABVIEW:
    ctx->self = lv_tabview_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_CHART:
    ctx->self = lv_chart_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_BUTTONMATRIX:
    ctx->self = lv_buttonmatrix_create(ctx->parent);
    break;
  case ui_WidgetType_WIDGET_TABLE:
    ctx->self = lv_table_create(ctx->parent);
    break;
  /* HOST_PROXY: a plain box — the proxy machinery (glass/handles/
     * grid/registry) assembles at finalize, after host_proxy_props
     * decodes. The default look attaches HERE, at create: wire-authored
     * style groups attach later during decode and must outrank it. */
  case ui_WidgetType_WIDGET_HOST_PROXY:
    ctx->self = lv_obj_create(ctx->parent);
    if (ctx->self)
      proxy_apply_default_style(ctx->self);
    break;
  default:
    ctx->self = lv_obj_create(ctx->parent);
    break;
  }
  if (!ctx->self) {
    LOG_ERROR("lv_*_create() returned NULL for widget type %d",
              ctx->node->type);
    ctx->error = -1;
    return NULL;
  }
  /* Apply layout (field 7 — decoded before children/style_groups) */
  apply_node_layout(ctx->self, ctx->node);
  return ctx->self;
}
/* Apply widget-specific text based on widget type */
static void apply_widget_text(lv_obj_t *obj, ui_WidgetType type,
                              const char *text) {
  if (text[0] == '\0')
    return;
  switch (type) {
  case ui_WidgetType_WIDGET_LABEL:
    lv_label_set_text(obj, text);
    break;
  case ui_WidgetType_WIDGET_CHECKBOX:
    lv_checkbox_set_text(obj, text);
    break;
  case ui_WidgetType_WIDGET_TEXTAREA:
    lv_textarea_set_text(obj, text);
    break;
  default:
    /* For button and others, text is informational or unused */
    break;
  }
}
/* Scale text_src: "\n"-joined labels -> persistent NULL-terminated
 * char* array (LVGL keeps the pointers). Bounded pools, reset per load.
 * Capacity covers the largest demo source (12 month labels). */
#define MAX_SCALE_TEXTS 16
#define MAX_SCALE_TEXT_POOL 4
static char scale_text_buf[MAX_SCALE_TEXT_POOL][256];
static const char *scale_text_ptrs[MAX_SCALE_TEXT_POOL][MAX_SCALE_TEXTS + 1];
static int scale_text_count;
static void apply_scale_text_src(lv_obj_t *obj, const char *joined) {
  if (scale_text_count >= MAX_SCALE_TEXT_POOL) {
    /* B3/ITEM-8a: the 5th+ scale text source would render label-less. The
       * codegen headroom gate (renderer-caps :scale-text-pool) catches this at
       * generation; this is the fail-loud belt for an untrusted/crafted .pb. */
    LOG_ERROR("scale text pool exhausted (%d max)", MAX_SCALE_TEXT_POOL);
    load_resource_error = true;
    return;
  }
  char *buf = scale_text_buf[scale_text_count];
  const char **ptrs = scale_text_ptrs[scale_text_count];
  scale_text_count++;
  (void)snprintf(buf, 256, "%s", joined);
  int n = 0;
  char *tok = buf;
  for (char *c = buf; *c && n < MAX_SCALE_TEXTS; c++) {
    if (*c == '\n') {
      *c = '\0';
      ptrs[n++] = tok;
      tok = c + 1;
    }
  }
  if (n < MAX_SCALE_TEXTS && *tok) {
    ptrs[n++] = tok;
  }
  ptrs[n] = NULL;
  lv_scale_set_text_src(obj, ptrs);
}
/* ButtonMatrix map_str: "\n"-separated button labels -> the persistent
 * ""-terminated char* array LVGL keeps a POINTER to (lv_buttonmatrix_set_map
 * stores the array; it does not copy). Bounded pools, reset per load — the
 * apply_scale_text_src pattern.
 *
 * WIRE ENCODING (defined here because nothing defined it before: the field
 * shipped in ui_ast.proto with no documented shape and no renderer that read
 * it, so no host can be depending on another reading):
 *   - each "\n"-separated segment is one button label, in order;
 *   - an EMPTY segment is LVGL's ROW-BREAK element (its map convention is the
 *     literal one-char string "\n"), so "A\nB\n\nC\n" is row [A B] then [C];
 *   - the trailing empty segment produced by a trailing "\n" is absorbed by
 *     the terminator, so the natural "A\nB\nC\n" is three buttons on one row.
 * The array is ALWAYS ""-terminated whatever the input, because LVGL walks the
 * map until it sees the empty string: an unterminated array from a crafted .pb
 * would read off the end. A map that parses to ZERO real buttons is refused
 * loudly rather than handed to LVGL (btn_cnt 0 allocates nothing and every
 * later index is out of range). */
#define MAX_BTNMATRIX_BUTTONS 32
#define MAX_BTNMATRIX_MAP_POOL 4
/* Per-NODE ceilings on a table's declared grid (see the table_props case):
 * bounds on ONE table's allocation, not a screen-wide pool. */
#define MAX_TABLE_ROWS 64
#define MAX_TABLE_COLS 16
static char btnmatrix_map_buf[MAX_BTNMATRIX_MAP_POOL][1024];
static const char
    *btnmatrix_map_ptrs[MAX_BTNMATRIX_MAP_POOL][MAX_BTNMATRIX_BUTTONS + 1];
static int btnmatrix_map_count;
static void apply_buttonmatrix_map(lv_obj_t *obj, const char *joined) {
  if (btnmatrix_map_count >= MAX_BTNMATRIX_MAP_POOL) {
    /* The 5th+ map would render with LVGL's placeholder map. The codegen
       * headroom gate (renderer-caps :btnmatrix-map-pool) catches this at
       * generation; this is the fail-loud belt for an untrusted/crafted .pb. */
    LOG_ERROR("buttonmatrix map pool exhausted (%d max)",
              MAX_BTNMATRIX_MAP_POOL);
    load_resource_error = true;
    return;
  }
  char *buf = btnmatrix_map_buf[btnmatrix_map_count];
  const char **ptrs = btnmatrix_map_ptrs[btnmatrix_map_count];
  (void)snprintf(buf, sizeof(btnmatrix_map_buf[0]), "%s", joined);
  int n = 0;
  int buttons = 0;
  char *tok = buf;
  for (char *c = buf; *c && n < MAX_BTNMATRIX_BUTTONS; c++) {
    if (*c == '\n') {
      *c = '\0';
      if (*tok == '\0') {
        ptrs[n++] = "\n"; /* row break */
      } else {
        ptrs[n++] = tok;
        buttons++;
      }
      tok = c + 1;
    }
  }
  if (n < MAX_BTNMATRIX_BUTTONS && *tok) {
    ptrs[n++] = tok;
    buttons++;
  }
  if (buttons == 0) {
    LOG_ERROR("buttonmatrix map has no buttons (map_str=\"%s\")", joined);
    load_resource_error = true;
    return;
  }
  ptrs[n] = ""; /* LVGL's terminator — always present */
  btnmatrix_map_count++;
  lv_buttonmatrix_set_map(obj, ptrs);
}
/* bg_image_src style strings: lv_style_set_bg_image_src stores the
 * POINTER (no copy), so the value must outlive the stack-local decode
 * context — a symbol glyph or path pointed at the decode buffer would
 * dangle. Bounded pool, reset per load. */
#define MAX_BG_IMAGE_SRCS 8
static char bg_image_src_pool[MAX_BG_IMAGE_SRCS][64];
static int bg_image_src_count;
static const char *persist_bg_image_src(const char *src) {
  if (bg_image_src_count >= MAX_BG_IMAGE_SRCS) {
    /* B4: the 9th+ bg-image src renders image-less. Codegen headroom
       * (renderer-caps :bg-image-srcs) catches it at generation; fail-loud belt
       * for a crafted .pb. */
    LOG_ERROR("bg_image_src pool exhausted (%d max)", MAX_BG_IMAGE_SRCS);
    load_resource_error = true;
    return NULL;
  }
  char *dst = bg_image_src_pool[bg_image_src_count++];
  (void)snprintf(dst, sizeof(bg_image_src_pool[0]), "%s", src);
  return dst;
}
/* Line points: lv_line_set_points STORES THE ARRAY POINTER AND DOES NOT COPY
 * (lv_line.c line_set_points -> line->point_array.constant = points), so the
 * points must outlive the stack-local ui_WidgetNode they decoded into — the
 * apply_buttonmatrix_map / persist_bg_image_src situation exactly. Bounded
 * pool, reset per load.
 *
 * The wire carries int32 x/y (ui_Point) and LVGL wants lv_point_precise_t,
 * which is `float` under LV_USE_FLOAT (lv_types.h). Authored coordinates are
 * small integer pixel offsets, and every int32 up to 2^24 is exactly
 * representable as a float — four orders of magnitude past any canvas this
 * renderer targets — so the widening is lossless and stays bit-identical
 * across engines. A coordinate large enough to lose precision here would have
 * to be off-screen by a factor of thousands. */
#define MAX_LINE_POINTS 32
#define MAX_LINE_POINT_POOL 16
/* The pool's width and the wire's max_count are ONE fact; keep them one.
 * Raising max_count in proto/ui/ui_ast.options without raising the define
 * would silently truncate every long line, so make it a build failure. */
_Static_assert(MAX_LINE_POINTS ==
                   sizeof(((ui_LineProps *)0)->points) / sizeof(ui_Point),
               "MAX_LINE_POINTS must equal max_count for ui.LineProps.points "
               "(protogen/proto/ui/ui_ast.options)");
static lv_point_precise_t line_point_pool[MAX_LINE_POINT_POOL][MAX_LINE_POINTS];
static int line_point_count;
static void apply_line_points(lv_obj_t *obj, const ui_LineProps *p) {
  /* Zero points applies nothing, leaving LVGL's own empty line — the
   * dropdown/roller/buttonmatrix convention (apply only what the proto
   * states). A single point is harmless: lv_line's draw handler early-returns
   * on `point_num < 2 || point_array.constant == NULL` before any draw reaches
   * the layer, so it draws nothing rather than relying on a loop bound. (It
   * fetches the layer pointer one line earlier, but that is a pure getter and
   * writes nothing.) */
  if (p->points_count == 0) {
    return;
  }
  if (line_point_count >= MAX_LINE_POINT_POOL) {
    /* The 17th+ line would render point-less. The codegen headroom gate
     * (renderer-caps :line-point-pool) catches this at generation; this is
     * the fail-loud belt for an untrusted/crafted .pb. */
    LOG_ERROR("line point pool exhausted (%d max)", MAX_LINE_POINT_POOL);
    load_resource_error = true;
    return;
  }
  lv_point_precise_t *pts = line_point_pool[line_point_count++];
  /* points_count is bounded by nanopb at MAX_LINE_POINTS on decode — a
   * crafted .pb with more entries fails the decode, it does not arrive
   * here oversized. */
  for (pb_size_t i = 0; i < p->points_count; i++) {
    pts[i].x = (lv_value_precise_t)p->points[i].x;
    pts[i].y = (lv_value_precise_t)p->points[i].y;
  }
  lv_line_set_points(obj, pts, p->points_count);
}
/* Colored scale section (demo analytics scales). */
static void apply_scale_section(lv_obj_t *obj, const ui_ScaleSection *sec) {
  lv_scale_section_t *section = lv_scale_add_section(obj);
  if (!section) {
    return;
  }
  lv_scale_set_section_range(obj, section, sec->range_min, sec->range_max);
  lv_style_t *style = alloc_style();
  if (!style) {
    return;
  }
  if (sec->has_color) {
    lv_color_t c = lv_color_make(sec->color.r, sec->color.g, sec->color.b);
    lv_style_set_line_color(style, c);
  }
  if (sec->width != 0) {
    lv_style_set_line_width(style, (int32_t)sec->width);
  }
  lv_scale_set_section_style_indicator(obj, section, style);
  lv_scale_set_section_style_items(obj, section, style);
  /* MAIN-part section style (the demo's colored arc bands): arc_* covers
   * round scales, line_* linear ones — LVGL reads the pair matching the
   * scale mode, so both are set from the one wire color+width. */
  if (sec->has_main_color || sec->main_width != 0) {
    lv_style_t *main_style = alloc_style();
    if (!main_style) {
      return;
    }
    if (sec->has_main_color) {
      lv_color_t mc = lv_color_make(sec->main_color.r, sec->main_color.g,
                                    sec->main_color.b);
      lv_style_set_arc_color(main_style, mc);
      lv_style_set_line_color(main_style, mc);
    }
    if (sec->main_width != 0) {
      lv_style_set_arc_width(main_style, (int32_t)sec->main_width);
      lv_style_set_line_width(main_style, (int32_t)sec->main_width);
    }
    lv_scale_set_section_style_main(obj, section, main_style);
  }
}
/* Chart fader draw-event (ChartProps.fade_area) — replicates the demo's
 * chart_event_cb LV_EVENT_DRAW_TASK_ADDED body (lv_demo_widgets_analytics.c):
 * under every LINE-series segment, a vertical-gradient triangle (the area
 * between the two points) plus a rect down to the chart bottom, with stop
 * opacities derived from the segment y-coords and the object height. The
 * pressed-point value bubbles of the original are omitted: they key on
 * lv_chart_get_pressed_point, which never fires headless/declaratively.
 * The series lookup generalizes the demo's two-series `id1 == 1` step to
 * an id1-step walk (identical for the demo's series counts). */
static void chart_fade_draw_cb(lv_event_t *e) {
  lv_obj_t *obj = lv_event_get_target(e);
  lv_draw_task_t *draw_task = lv_event_get_param(e);
  if (!obj || !draw_task)
    return;
  lv_draw_dsc_base_t *base_dsc = lv_draw_task_get_draw_dsc(draw_task);
  lv_draw_line_dsc_t *draw_line_dsc = lv_draw_task_get_line_dsc(draw_task);
  if (base_dsc->part != LV_PART_ITEMS || !draw_line_dsc)
    return;
  lv_area_t obj_coords;
  lv_obj_get_coords(obj, &obj_coords);
  const lv_chart_series_t *ser = lv_chart_get_series_next(obj, NULL);
  for (uint32_t k = 0; ser && k < base_dsc->id1; k++) {
    ser = lv_chart_get_series_next(obj, ser);
  }
  int32_t full_h = lv_obj_get_height(obj);
  if (!ser || full_h <= 0)
    return;
  for (int32_t i = 0; i < draw_line_dsc->point_cnt - 1; i++) {
    lv_point_precise_t p1 = draw_line_dsc->points[i];
    lv_point_precise_t p2 = draw_line_dsc->points[i + 1];
    lv_draw_triangle_dsc_t tri_dsc;
    lv_draw_triangle_dsc_init(&tri_dsc);
    tri_dsc.p[0].x = (int32_t)p1.x;
    tri_dsc.p[0].y = (int32_t)p1.y;
    tri_dsc.p[1].x = (int32_t)p2.x;
    tri_dsc.p[1].y = (int32_t)p2.y;
    tri_dsc.p[2].x = (int32_t)(p1.y < p2.y ? p1.x : p2.x);
    tri_dsc.p[2].y = (int32_t)LV_MAX(p1.y, p2.y);
    tri_dsc.grad.dir = LV_GRAD_DIR_VER;
    int32_t fract_upper =
        (int32_t)(LV_MIN(p1.y, p2.y) - obj_coords.y1) * 255 / full_h;
    int32_t fract_lower =
        (int32_t)(LV_MAX(p1.y, p2.y) - obj_coords.y1) * 255 / full_h;
    tri_dsc.grad.stops[0].color = lv_chart_get_series_color(obj, ser);
    tri_dsc.grad.stops[0].opa = (lv_opa_t)(255 - fract_upper);
    tri_dsc.grad.stops[0].frac = 0;
    tri_dsc.grad.stops[1].color = lv_chart_get_series_color(obj, ser);
    tri_dsc.grad.stops[1].opa = (lv_opa_t)(255 - fract_lower);
    tri_dsc.grad.stops[1].frac = 255;
    lv_draw_triangle(base_dsc->layer, &tri_dsc);
    lv_draw_rect_dsc_t rect_dsc;
    lv_draw_rect_dsc_init(&rect_dsc);
    rect_dsc.bg_grad.dir = LV_GRAD_DIR_VER;
    rect_dsc.bg_grad.stops[0].color = lv_chart_get_series_color(obj, ser);
    rect_dsc.bg_grad.stops[0].frac = 0;
    rect_dsc.bg_grad.stops[0].opa = (lv_opa_t)(255 - fract_lower);
    rect_dsc.bg_grad.stops[1].color = lv_chart_get_series_color(obj, ser);
    rect_dsc.bg_grad.stops[1].frac = 255;
    rect_dsc.bg_grad.stops[1].opa = 0;
    lv_area_t rect_area;
    rect_area.x1 = (int32_t)p1.x;
    rect_area.x2 = (int32_t)p2.x;
    rect_area.y1 = (int32_t)LV_MAX(p1.y, p2.y);
    rect_area.y2 = obj_coords.y2;
    lv_draw_rect(base_dsc->layer, &rect_dsc, &rect_area);
  }
}
/* ChartProps application — type/point_count/div lines (div presence rides
 * has_div_lines: 0 is a VALID explicit count), then add_series + per-index
 * value writes (lv_chart_set_series_value_by_id COPIES into the chart's
 * own y_points array, so the decoded proto arrays need not outlive decode),
 * then the optional fader draw-event. */
static void apply_chart_props(lv_obj_t *obj, const ui_ChartProps *p) {
  /* Morph: the differ admits ONLY values-only chart changes as UPDATE
   * (anything structural — series count/color/axis, type, divs —
   * REPLACEs the node), so the morph path writes the new values into
   * the EXISTING series (lv_chart series iterate in add order:
   * lv_ll_ins_tail + get_series_next-from-head). */
  if (morph_in_progress) {
    lv_chart_series_t *ser = lv_chart_get_series_next(obj, NULL);
    for (pb_size_t i = 0; i < p->series_count && ser; i++) {
      const ui_ChartSeries *s = &p->series[i];
      for (pb_size_t j = 0; j < s->values_count; j++) {
        lv_chart_set_series_value_by_id(obj, ser, j, s->values[j]);
      }
      ser = lv_chart_get_series_next(obj, ser);
    }
    lv_chart_refresh(obj);
    return;
  }
  if (p->type != 0) {
    lv_chart_set_type(obj, (lv_chart_type_t)p->type);
  }
  if (p->point_count != 0) {
    lv_chart_set_point_count(obj, p->point_count);
  }
  if (p->has_div_lines) {
    lv_chart_set_div_line_count(obj, (uint8_t)p->hdiv_count,
                                (uint8_t)p->vdiv_count);
  }
  for (pb_size_t i = 0; i < p->series_count; i++) {
    const ui_ChartSeries *s = &p->series[i];
    /* Absent color = the theme primary (the demo's default for its
       * unstyled series). */
    lv_color_t color = lv_theme_get_color_primary(obj);
    if (s->has_color) {
      color = lv_color_make(s->color.r, s->color.g, s->color.b);
    }
    lv_chart_series_t *ser =
        lv_chart_add_series(obj, color, (lv_chart_axis_t)s->axis);
    if (!ser) {
      LOG_ERROR("lv_chart_add_series failed (series %d)", (int)i);
      return;
    }
    for (pb_size_t j = 0; j < s->values_count; j++) {
      lv_chart_set_series_value_by_id(obj, ser, j, s->values[j]);
    }
  }
  if (p->fade_area) {
    lv_obj_add_flag(obj, LV_OBJ_FLAG_SEND_DRAW_TASK_EVENTS);
    lv_obj_add_event_cb(obj, chart_fade_draw_cb, LV_EVENT_DRAW_TASK_ADDED,
                        NULL);
  }
  lv_chart_refresh(obj);
}
/* Map the pressed point to a value immediately at LV_EVENT_PRESSED. Stock
 * LVGL seeks a stationary track tap only at RELEASE (update_knob_pos runs
 * with check_drag=false from the RELEASED arm; ADV_HITTEST is never set on
 * sliders, so the whole click area hit-tests). The position→value math
 * MIRRORS update_knob_pos (lv_slider.c) VERBATIM — content-box-relative,
 * MAIN pads subtracted, +indic/2 rounding, RAW (possibly reversed) bar
 * range, RTL flip on horizontal only, the upstream vertical y2+bg_bottom
 * sign quirk included — because the RELEASED arm runs the SAME map over the
 * same point: an exact mirror makes the release a value-unchanged no-op (no
 * duplicate VALUE_CHANGED) where an off-by-one re-derivation would
 * double-fire. */
static void slider_press_seek_cb(lv_event_t *e) {
  lv_obj_t *obj = lv_event_get_current_target(e);
  /* Range mode never press-seeks: WHICH knob a press adjusts is
   * drag_start's proximity contract, and jumping a knob to the pressed
   * point on DOWN would preempt it. Stock press/drag/release behavior
   * stays untouched for range sliders. */
  if (lv_slider_get_mode(obj) == LV_SLIDER_MODE_RANGE)
    return;
  lv_indev_t *indev = lv_indev_active();
  if (indev == NULL || lv_indev_get_type(indev) != LV_INDEV_TYPE_POINTER)
    return;
  if (lv_indev_get_scroll_obj(indev) != NULL)
    return;
  lv_slider_t *slider = (lv_slider_t *)obj;
  lv_point_t p;
  lv_indev_get_point(indev, &p);
  lv_obj_transform_point(obj, &p,
                         LV_OBJ_POINT_TRANSFORM_FLAG_INVERSE_RECURSIVE);
  lv_area_t coords;
  lv_obj_get_coords(obj, &coords);
  bool is_hor;
  if (slider->bar.orientation == LV_BAR_ORIENTATION_AUTO)
    is_hor = lv_obj_get_width(obj) >= lv_obj_get_height(obj);
  else
    is_hor = slider->bar.orientation == LV_BAR_ORIENTATION_HORIZONTAL;
  const int32_t range = slider->bar.max_value - slider->bar.min_value;
  const bool is_rtl =
      LV_BASE_DIR_RTL == lv_obj_get_style_base_dir(obj, LV_PART_MAIN);
  const bool is_reversed = slider->bar.val_reversed ^ (is_rtl && is_hor);
  int32_t new_value = 0;
  if (is_hor) {
    const int32_t bg_left = lv_obj_get_style_pad_left(obj, LV_PART_MAIN);
    const int32_t bg_right = lv_obj_get_style_pad_right(obj, LV_PART_MAIN);
    const int32_t indic_w = lv_obj_get_width(obj) - bg_left - bg_right;
    /* Degenerate geometry (pads >= width): decline — stock's own release
     * map divides by the same zero/negative extent; nothing healthy to
     * seek. */
    if (indic_w <= 0)
      return;
    if (is_reversed)
      new_value = (coords.x2 - bg_right) - p.x;
    else
      new_value = p.x - (coords.x1 + bg_left);
    new_value = (new_value * range + indic_w / 2) / indic_w;
    new_value += slider->bar.min_value;
  } else {
    const int32_t bg_top = lv_obj_get_style_pad_top(obj, LV_PART_MAIN);
    const int32_t bg_bottom = lv_obj_get_style_pad_bottom(obj, LV_PART_MAIN);
    const int32_t indic_h = lv_obj_get_height(obj) - bg_top - bg_bottom;
    if (indic_h <= 0)
      return;
    if (is_reversed) {
      new_value = p.y - (coords.y1 + bg_top);
    } else {
      /* Upstream measures from y2 + bg_bottom (not y2 - bg_bottom);
       * mirrored verbatim — see the function comment. */
      new_value = p.y - (coords.y2 + bg_bottom);
      new_value = -new_value;
    }
    new_value = (new_value * range + indic_h / 2) / indic_h;
    new_value += slider->bar.min_value;
  }
  /* Stock clamp for the cur-value knob (non-range): floored at
   * start_value, exactly update_knob_pos's else-branch. */
  new_value =
      LV_CLAMP(slider->bar.start_value, new_value, slider->bar.max_value);
  if (new_value != slider->bar.cur_value) {
    lv_slider_set_value(obj, new_value, LV_ANIM_OFF);
    lv_result_t res = lv_obj_send_event(obj, LV_EVENT_VALUE_CHANGED, NULL);
    if (res != LV_RESULT_OK)
      return;
  }
}
/* Apply widget-specific properties from the oneof widget_props */
static void apply_widget_props(lv_obj_t *obj, ui_WidgetNode *node) {
  switch (node->which_widget_props) {
  case ui_WidgetNode_slider_props_tag: {
    ui_SliderProps *p = &node->widget_props.slider_props;
    lv_slider_set_range(obj, p->min_value, p->max_value);
    /* Morph: a default value means 'authored value unchanged' (the
         * differ stripped it) — keep the live user-set value. */
    if (!morph_in_progress || p->value != 0) {
      lv_slider_set_value(obj, p->value, LV_ANIM_OFF);
    }
    if (p->mode != 0) {
      lv_slider_set_mode(obj, (lv_slider_mode_t)p->mode);
    }
    if (p->seek_on_press) {
      /* Idempotent under an UPDATE_PROPS morph re-entry on the LIVE obj
       * (the dropdown value-map precedent above): remove before add, so a
       * hot-reload cannot stack duplicate press-seek callbacks. A false
       * flag under morph means 'unchanged' (the differ strips defaults —
       * the slider-value idiom above), so there is no un-set arm. */
      lv_obj_remove_event_cb(obj, slider_press_seek_cb);
      lv_obj_add_event_cb(obj, slider_press_seek_cb, LV_EVENT_PRESSED, NULL);
    }
    break;
  }
  case ui_WidgetNode_arc_props_tag: {
    ui_ArcProps *p = &node->widget_props.arc_props;
    if (p->mode != 0) {
      lv_arc_set_mode(obj, (lv_arc_mode_t)p->mode);
    }
    lv_arc_set_range(obj, p->min_value, p->max_value);
    /* Morph value guard — see the slider note. */
    if (!morph_in_progress || p->value != 0) {
      lv_arc_set_value(obj, p->value);
    }
    lv_arc_set_angles(obj, p->start_angle, p->end_angle);
    lv_arc_set_bg_angles(obj, p->bg_start_angle, p->bg_end_angle);
    if (p->rotation != 0) {
      lv_arc_set_rotation(obj, p->rotation);
    }
    break;
  }
  case ui_WidgetNode_bar_props_tag: {
    ui_BarProps *p = &node->widget_props.bar_props;
    lv_bar_set_range(obj, p->min_value, p->max_value);
    lv_bar_set_value(obj, p->value, LV_ANIM_OFF);
    if (p->mode != 0) {
      lv_bar_set_mode(obj, (lv_bar_mode_t)p->mode);
    }
    if (p->start_value != 0) {
      lv_bar_set_start_value(obj, p->start_value, LV_ANIM_OFF);
    }
    break;
  }
  case ui_WidgetNode_switch_props_tag: {
    if (node->widget_props.switch_props.checked) {
      lv_obj_add_state(obj, LV_STATE_CHECKED);
    }
    break;
  }
  case ui_WidgetNode_checkbox_props_tag: {
    if (node->widget_props.checkbox_props.checked) {
      lv_obj_add_state(obj, LV_STATE_CHECKED);
    }
    break;
  }
  case ui_WidgetNode_spinner_props_tag: {
    ui_SpinnerProps *p = &node->widget_props.spinner_props;
    if (p->spin_time != 0 || p->arc_length != 0) {
      lv_spinner_set_anim_params(obj, p->spin_time, p->arc_length);
    }
    break;
  }
  case ui_WidgetNode_led_props_tag: {
    ui_LedProps *p = &node->widget_props.led_props;
    if (p->has_color) {
      lv_led_set_color(obj, lv_color_make(p->color.r, p->color.g, p->color.b));
    }
    /* Morph value guard — see the slider note. brightness 0 is a real value
     * ("off"), not "unset": a full render always applies it, so an off LED
     * renders off; under a morph a default 0 means the differ stripped an
     * unchanged value (brightness carries no has_ presence flag). */
    if (!morph_in_progress || p->brightness != 0) {
      lv_led_set_brightness(obj, p->brightness);
    }
    break;
  }
  case ui_WidgetNode_line_props_tag: {
    const ui_LineProps *p = &node->widget_props.line_props;
    /* y_invert first: it only flips a flag + invalidates, whereas
     * lv_line_set_points also runs refresh_self_size, so the points call
     * settles the geometry against the final orientation. */
    lv_line_set_y_invert(obj, p->y_invert);
    apply_line_points(obj, p);
    break;
  }
  case ui_WidgetNode_spinbox_props_tag: {
    ui_SpinboxProps *p = &node->widget_props.spinbox_props;
    lv_spinbox_set_range(obj, p->min_value, p->max_value);
    /* Morph value guard — see the slider note. */
    if (!morph_in_progress || p->value != 0) {
      lv_spinbox_set_value(obj, p->value);
    }
    if (p->step != 0) {
      lv_spinbox_set_step(obj, p->step);
    }
    if (p->digit_count != 0) {
      lv_spinbox_set_digit_format(obj, p->digit_count, p->separator_position);
    }
    break;
  }
  case ui_WidgetNode_scale_props_tag: {
    ui_ScaleProps *p = &node->widget_props.scale_props;
    if (p->mode != 0) {
      lv_scale_set_mode(obj, (lv_scale_mode_t)p->mode);
    }
    lv_scale_set_range(obj, p->min_value, p->max_value);
    if (p->total_tick_count != 0) {
      lv_scale_set_total_tick_count(obj, p->total_tick_count);
    }
    if (p->major_tick_every != 0) {
      lv_scale_set_major_tick_every(obj, p->major_tick_every);
    }
    lv_scale_set_label_show(obj, p->label_show);
    if (p->text_src[0] != '\0') {
      apply_scale_text_src(obj, p->text_src);
    }
    if (p->post_draw) {
      lv_scale_set_post_draw(obj, true);
    }
    for (pb_size_t i = 0; i < p->sections_count; i++) {
      apply_scale_section(obj, &p->sections[i]);
    }
    if (p->angle_range != 0) {
      lv_scale_set_angle_range(obj, p->angle_range);
    }
    if (p->rotation != 0) {
      lv_scale_set_rotation(obj, p->rotation);
    }
    break;
  }
  case ui_WidgetNode_label_props_tag: {
    if (node->widget_props.label_props.long_mode != 0) {
      lv_label_set_long_mode(
          obj, (lv_label_long_mode_t)node->widget_props.label_props.long_mode);
    }
    break;
  }
  case ui_WidgetNode_dropdown_props_tag: {
    ui_DropdownProps *p = &node->widget_props.dropdown_props;
    if (p->options[0] != '\0') {
      lv_dropdown_set_options(obj, p->options);
    }
    if (p->selected > 0) {
      lv_dropdown_set_selected(obj, p->selected);
    }
    if (p->direction != 0) {
      lv_dropdown_set_dir(obj, (lv_dir_t)p->direction);
    }
    /* SYNC C1: register the enum value->index map (no-op if empty). */
    register_dropdown_value_map(obj, p->option_values, p->option_values_count);
    break;
  }
  case ui_WidgetNode_roller_props_tag: {
    ui_RollerProps *p = &node->widget_props.roller_props;
    if (p->options[0] != '\0') {
      lv_roller_set_options(obj, p->options, (lv_roller_mode_t)p->mode);
    }
    if (p->selected > 0) {
      lv_roller_set_selected(obj, p->selected, LV_ANIM_OFF);
    }
    if (p->visible_row_count > 0) {
      lv_roller_set_visible_row_count(obj, p->visible_row_count);
    }
    break;
  }
  case ui_WidgetNode_textarea_props_tag: {
    ui_TextareaProps *p = &node->widget_props.textarea_props;
    if (p->placeholder[0] != '\0') {
      lv_textarea_set_placeholder_text(obj, p->placeholder);
    }
    if (p->max_length > 0) {
      lv_textarea_set_max_length(obj, p->max_length);
    }
    lv_textarea_set_one_line(obj, p->one_line);
    lv_textarea_set_password_mode(obj, p->password_mode);
    break;
  }
  case ui_WidgetNode_image_props_tag: {
    ui_ImageProps *p = &node->widget_props.image_props;
    if (p->src[0] != '\0') {
      lv_image_set_src(obj, p->src);
    }
    if (p->has_pivot) {
      lv_image_set_pivot(obj, p->pivot_x, p->pivot_y);
    }
    if (p->rotation != 0) {
      lv_image_set_rotation(obj, p->rotation);
    }
    break;
  }
  case ui_WidgetNode_chart_props_tag: {
    apply_chart_props(obj, &node->widget_props.chart_props);
    break;
  }
  case ui_WidgetNode_buttonmatrix_props_tag: {
    ui_ButtonMatrixProps *p = &node->widget_props.buttonmatrix_props;
    /* Empty map_str leaves LVGL's own default map in place — the
       * dropdown/roller convention (apply only what the proto states). */
    if (p->map_str[0] != '\0') {
      apply_buttonmatrix_map(obj, p->map_str);
    }
    lv_buttonmatrix_set_one_checked(obj, p->one_check);
    break;
  }
  case ui_WidgetNode_table_props_tag: {
    ui_TableProps *p = &node->widget_props.table_props;
    /* row/column counts are uint32 off the wire and each drives an
       * allocation (lv_table_set_row_count reallocs row_h + the cell array),
       * so a crafted .pb must not be able to ask for 4e9 rows. Bounded +
       * fail-loud, like the pools above; 0 means "unset", leaving LVGL's
       * 1x1 default. */
    if (p->row_count > MAX_TABLE_ROWS || p->column_count > MAX_TABLE_COLS) {
      LOG_ERROR("table too large: %ux%u (max %dx%d)", (unsigned)p->row_count,
                (unsigned)p->column_count, MAX_TABLE_ROWS, MAX_TABLE_COLS);
      load_resource_error = true;
      break;
    }
    if (p->row_count > 0) {
      lv_table_set_row_count(obj, p->row_count);
    }
    if (p->column_count > 0) {
      lv_table_set_column_count(obj, p->column_count);
    }
    /* NO CELL TEXT IS DECODED HERE, and the theme currently DEPENDS on that.
     * A table therefore renders an empty grid, which is the only reason
     * `disabled_flat` may still express DISABLED as a whole-widget opa fade:
     * the rule that bans the fade bans it over subtrees containing TEXT, and
     * this one has none. WIRING lv_table_set_cell_value HERE VOIDS THAT — the
     * table's disabled arm in theme.c must move to the token-pair swap in the
     * same change, or every disabled cell's glyphs get composited toward
     * their own background.
     *
     * SOMETHING MECHANICAL DOES CATCH THIS NOW, and it is the reason this
     * paragraph is worth keeping rather than trusting: `lv_table` sits in
     * devcards.opa's `text-free-classes` on exactly the condition stated
     * above, and devcards.opa-test's `table-carve-out-still-holds` reads THIS
     * FILE with comments stripped and fails the moment the call appears. So
     * the change that wires cell text goes red until lv_table moves to
     * `glyph-classes` and theme.c's arm moves off `disabled_flat`. */
    break;
  }
  /* The four arms with nothing to do HERE are named individually rather than
   * left to the default, so the default can mean one thing: an arm this
   * renderer does not know. A bare `default: break` conflated the two, and the
   * unknown case is exactly the one that must not be silent — the producer's
   * props would simply vanish and the widget would render at its defaults,
   * with no error and no pixel to show for it. */
  case 0:
    /* NO widget_props at all — nanopb's unset value for a oneof, and the
     * COMMON case: every container, label wrapper and layout node carries
     * none. It is spelled 0 rather than a named constant because nanopb emits
     * no tag for "unset"; the generated tags start at 10, so nothing else can
     * collide with it. Listing it is load-bearing, not tidiness: without it an
     * unset oneof falls to the default below and every prop-less node in every
     * screen fails the load. */
    break;
  case ui_WidgetNode_obj_props_tag:
  case ui_WidgetNode_button_props_tag:
    /* Genuinely empty messages — nothing to apply. */
    break;
  case ui_WidgetNode_tabview_props_tag:
    /* Applied by apply_tabview, which needs the widget ctx this does not have. */
    break;
  case ui_WidgetNode_host_proxy_props_tag:
    /* Applied during finalize, after the children it composes exist. That arm
     * carries its own refusal for the inverse mistake (a host_proxy node
     * WITHOUT these props), so both directions are loud. */
    break;
  default:
    /* Reachable when ui_ast grows a widget_props arm and THIS switch is not
     * extended in the same change. Note which mechanism does and does not
     * produce it: a NEWER PRODUCER's unknown field cannot, because nanopb
     * decodes against this build's own descriptor and simply skips a tag it
     * does not know, leaving `which_` at 0 (the case above). What DOES produce
     * it is regenerating the bindings — the new tag becomes known, nanopb sets
     * `which_` to it, and this switch has no arm. So the default is a forcing
     * function on the regeneration, which is exactly when the omission is
     * cheap to fix.
     *
     * Refuse the load rather than render a widget stripped of the props its
     * author wrote: a silently default-rendered control is indistinguishable
     * from one that was authored that way. */
    LOG_ERROR(
        "unknown widget_props arm %u — add its case to apply_widget_props "
        "in the same change that added the arm to ui_ast",
        (unsigned)node->which_widget_props);
    load_resource_error = true;
    break;
  }
}
/* Forward declarations */
static void apply_bindings(const pending_bindings_t *p);
/* ================================================================
 * Conditional visibility — show/hide via LVGL observer
 * ================================================================ */
/* Data for custom range-comparison observer callbacks (visibility +
 * checked_when share the VisibilityBinding wire shape).
 * Heap-allocated, freed on LV_EVENT_DELETE via cleanup_event_cb. */
typedef struct {
  int32_t ref_value;
  ui_CompareOp compare;
} compare_cb_data_t;
/* One comparison semantics for every VisibilityBinding consumer. */
static bool compare_holds(ui_CompareOp compare, int32_t val,
                          int32_t ref_value) {
  switch (compare) {
  case ui_CompareOp_COMPARE_NOT_EQ:
    return val != ref_value;
  case ui_CompareOp_COMPARE_GT:
    return val > ref_value;
  case ui_CompareOp_COMPARE_GTE:
    return val >= ref_value;
  case ui_CompareOp_COMPARE_LT:
    return val < ref_value;
  case ui_CompareOp_COMPARE_LTE:
    return val <= ref_value;
  default:
    return val == ref_value;
  }
}
static void visibility_observer_cb(lv_observer_t *observer,
                                   lv_subject_t *subject) {
  lv_obj_t *obj = lv_observer_get_target_obj(observer);
  compare_cb_data_t *data =
      (compare_cb_data_t *)lv_observer_get_user_data(observer);
  if (!obj || !data)
    return;
  bool show = compare_holds(data->compare, lv_subject_get_int(subject),
                            data->ref_value);
  if (show) {
    lv_obj_remove_flag(obj, LV_OBJ_FLAG_HIDDEN);
  } else {
    lv_obj_add_flag(obj, LV_OBJ_FLAG_HIDDEN);
  }
}
static void apply_visibility(lv_obj_t *obj, const ui_VisibilityBinding *vis) {
  subject_entry_t *entry = find_subject(vis->subject);
  if (!entry) {
    /* B7/ITEM-8b: a show-when bound to a never-declared subject is a dead
       * binding (the widget's visibility never reacts). Fail the load loud —
       * the declaration-overflow case already does (subject_overflow). */
    LOG_ERROR("visibility references unknown subject '%s'", vis->subject);
    load_resource_error = true;
    return;
  }
  if (entry->type != 0) {
    LOG_WARN("visibility only supports INT subjects (got '%s')", vis->subject);
    return;
  }
  switch (vis->compare) {
  case ui_CompareOp_COMPARE_EQ:
    /* Show when subject == ref_value → hide when NOT equal */
    lv_obj_bind_flag_if_not_eq(obj, &entry->subject, LV_OBJ_FLAG_HIDDEN,
                               vis->ref_value);
    break;
  case ui_CompareOp_COMPARE_NOT_EQ:
    /* Show when subject != ref_value → hide when equal */
    lv_obj_bind_flag_if_eq(obj, &entry->subject, LV_OBJ_FLAG_HIDDEN,
                           vis->ref_value);
    break;
  default: {
    /* GT, GTE, LT, LTE — custom observer callback */
    compare_cb_data_t *data = malloc(sizeof(compare_cb_data_t));
    if (!data) {
      /* B5: OOM would leave the comparison binding unwired (a control
             * that never reacts to its subject) with no signal. Fail loud. */
      LOG_ERROR("compare observer alloc failed — binding would be inert");
      load_resource_error = true;
      return;
    }
    data->ref_value = vis->ref_value;
    data->compare = vis->compare;
    lv_subject_add_observer_obj(&entry->subject, visibility_observer_cb, obj,
                                data);
    /* Free data on widget deletion */
    lv_obj_add_event_cb(obj, cleanup_event_cb, LV_EVENT_DELETE, data);
    break;
  }
  }
}
/* ================================================================
 * Reactive checked-state binding (checked_when) — the widget carries
 * LV_STATE_CHECKED while the subject comparison holds, cleared
 * otherwise. The reactive sibling of the create-time `states` bitmask;
 * the radio-group idiom (circle-styled checkboxes mirroring one INT
 * subject) rides this.
 * ================================================================ */
static void checked_observer_cb(lv_observer_t *observer,
                                lv_subject_t *subject) {
  lv_obj_t *obj = lv_observer_get_target_obj(observer);
  compare_cb_data_t *data =
      (compare_cb_data_t *)lv_observer_get_user_data(observer);
  if (!obj || !data)
    return;
  if (compare_holds(data->compare, lv_subject_get_int(subject),
                    data->ref_value)) {
    lv_obj_add_state(obj, LV_STATE_CHECKED);
  } else {
    lv_obj_remove_state(obj, LV_STATE_CHECKED);
  }
}
static void apply_checked_when(lv_obj_t *obj,
                               const ui_VisibilityBinding *bind) {
  subject_entry_t *entry = find_subject(bind->subject);
  if (!entry) {
    /* B7/ITEM-8b: a checked-when bound to a never-declared subject is a dead
       * binding. Fail the load loud (see apply_visibility). */
    LOG_ERROR("checked_when references unknown subject '%s'", bind->subject);
    load_resource_error = true;
    return;
  }
  if (entry->type != 0) {
    LOG_WARN("checked_when only supports INT subjects (got '%s')",
             bind->subject);
    return;
  }
  switch (bind->compare) {
  case ui_CompareOp_COMPARE_EQ:
    lv_obj_bind_state_if_eq(obj, &entry->subject, LV_STATE_CHECKED,
                            bind->ref_value);
    break;
  case ui_CompareOp_COMPARE_NOT_EQ:
    lv_obj_bind_state_if_not_eq(obj, &entry->subject, LV_STATE_CHECKED,
                                bind->ref_value);
    break;
  default: {
    /* GT, GTE, LT, LTE — custom observer (the visibility precedent) */
    compare_cb_data_t *data = malloc(sizeof(compare_cb_data_t));
    if (!data) {
      /* B5: OOM would leave the comparison binding unwired (a control
             * that never reacts to its subject) with no signal. Fail loud. */
      LOG_ERROR("compare observer alloc failed — binding would be inert");
      load_resource_error = true;
      return;
    }
    data->ref_value = bind->ref_value;
    data->compare = bind->compare;
    lv_subject_add_observer_obj(&entry->subject, checked_observer_cb, obj,
                                data);
    lv_obj_add_event_cb(obj, cleanup_event_cb, LV_EVENT_DELETE, data);
    break;
  }
  }
}
/* ================================================================
 * Reactive enabled-state binding (enabled_when) — the reactive sibling of
 * checked_when with INVERTED polarity: the widget carries LV_STATE_DISABLED
 * while the subject comparison does NOT hold, and is cleared (enabled) while
 * it holds. Drives reactive precondition-disable (a control greyed until its
 * preconditions read satisfied).
 * ================================================================ */
static void enabled_observer_cb(lv_observer_t *observer,
                                lv_subject_t *subject) {
  lv_obj_t *obj = lv_observer_get_target_obj(observer);
  compare_cb_data_t *data =
      (compare_cb_data_t *)lv_observer_get_user_data(observer);
  if (!obj || !data)
    return;
  if (compare_holds(data->compare, lv_subject_get_int(subject),
                    data->ref_value)) {
    lv_obj_remove_state(obj, LV_STATE_DISABLED);
  } else {
    lv_obj_add_state(obj, LV_STATE_DISABLED);
  }
}
static void apply_enabled_when(lv_obj_t *obj,
                               const ui_VisibilityBinding *bind) {
  subject_entry_t *entry = find_subject(bind->subject);
  if (!entry) {
    /* B7/ITEM-8b: an enabled-when bound to a never-declared subject is a dead
       * binding. Fail the load loud (see apply_visibility). */
    LOG_ERROR("enabled_when references unknown subject '%s'", bind->subject);
    load_resource_error = true;
    return;
  }
  if (entry->type != 0) {
    LOG_WARN("enabled_when only supports INT subjects (got '%s')",
             bind->subject);
    return;
  }
  switch (bind->compare) {
  case ui_CompareOp_COMPARE_EQ:
    /* Enabled when subject == ref → DISABLED when NOT equal */
    lv_obj_bind_state_if_not_eq(obj, &entry->subject, LV_STATE_DISABLED,
                                bind->ref_value);
    break;
  case ui_CompareOp_COMPARE_NOT_EQ:
    /* Enabled when subject != ref → DISABLED when equal */
    lv_obj_bind_state_if_eq(obj, &entry->subject, LV_STATE_DISABLED,
                            bind->ref_value);
    break;
  default: {
    /* GT, GTE, LT, LTE — custom observer (the checked_when precedent) */
    compare_cb_data_t *data = malloc(sizeof(compare_cb_data_t));
    if (!data) {
      /* B5: OOM would leave the comparison binding unwired (a control
             * that never reacts to its subject) with no signal. Fail loud. */
      LOG_ERROR("compare observer alloc failed — binding would be inert");
      load_resource_error = true;
      return;
    }
    data->ref_value = bind->ref_value;
    data->compare = bind->compare;
    lv_subject_add_observer_obj(&entry->subject, enabled_observer_cb, obj,
                                data);
    lv_obj_add_event_cb(obj, cleanup_event_cb, LV_EVENT_DELETE, data);
    break;
  }
  }
}
/* ================================================================
 * Reactive text-color binding (color_when) — the widget's LV_PART_MAIN text
 * color is set to the bound color while the subject comparison holds, and the
 * local override removed (reverting to the theme/authored default) when it
 * does not. LVGL has no native bind helper for a style property, so EVERY
 * compare op rides a custom observer (no EQ/NOT_EQ native-bind fast path).
 * Drives reactive fault-coloring.
 * ================================================================ */
typedef struct {
  int32_t ref_value;
  ui_CompareOp compare;
  lv_color_t color;
} color_cb_data_t;
static void color_observer_cb(lv_observer_t *observer, lv_subject_t *subject) {
  lv_obj_t *obj = lv_observer_get_target_obj(observer);
  color_cb_data_t *data =
      (color_cb_data_t *)lv_observer_get_user_data(observer);
  if (!obj || !data)
    return;
  if (compare_holds(data->compare, lv_subject_get_int(subject),
                    data->ref_value)) {
    lv_obj_set_style_text_color(obj, data->color, LV_PART_MAIN);
  } else {
    /* Revert to the theme/authored default (a no-op the first time, when no
     * local override was ever set). */
    lv_obj_remove_local_style_prop(obj, LV_STYLE_TEXT_COLOR, LV_PART_MAIN);
  }
}
static void apply_color_when(lv_obj_t *obj, const ui_ColorBinding *cb) {
  subject_entry_t *entry = find_subject(cb->when.subject);
  if (!entry) {
    /* B7/ITEM-8b: a color-when bound to a never-declared subject is a dead
       * binding. Fail the load loud (see apply_visibility). */
    LOG_ERROR("color_when references unknown subject '%s'", cb->when.subject);
    load_resource_error = true;
    return;
  }
  if (entry->type != 0) {
    LOG_WARN("color_when only supports INT subjects (got '%s')",
             cb->when.subject);
    return;
  }
  color_cb_data_t *data = malloc(sizeof(color_cb_data_t));
  if (!data) {
    /* B5: OOM would leave the binding unwired (a readout that never recolors)
     * with no signal. Fail loud. */
    LOG_ERROR("color observer alloc failed — binding would be inert");
    load_resource_error = true;
    return;
  }
  data->ref_value = cb->when.ref_value;
  data->compare = cb->when.compare;
  data->color = lv_color_make(cb->color.r, cb->color.g, cb->color.b);
  lv_subject_add_observer_obj(&entry->subject, color_observer_cb, obj, data);
  lv_obj_add_event_cb(obj, cleanup_event_cb, LV_EVENT_DELETE, data);
}
/* An EventBinding.set_subject naming a NEVER-DECLARED subject is a dead
 * control, and a quieter one than the three siblings above: find_subject
 * misses at CLICK time, button_event_cb skips the mutation, and the relay
 * gate downstream still fires — so the binding keeps reporting its STATIC
 * int_value (0 for a toggle) for the life of the screen, with nothing
 * anywhere to say why. Fail the load loud, exactly as apply_visibility /
 * apply_checked_when / apply_bindings do for the same fault (B7/ITEM-8b).
 * A declared-but-STRING subject is a WARN + skip, matching the siblings:
 * the subject exists, only this binding class cannot drive it. */
static void apply_event_subject(const pending_event_subject_t *p) {
  subject_entry_t *entry = find_subject(p->subject);
  if (!entry) {
    LOG_ERROR("event set_subject references unknown subject '%s' (uid %u)",
              p->subject, (unsigned)p->uid);
    load_resource_error = true;
    return;
  }
  if (entry->type != 0) {
    LOG_WARN("event set_subject only supports INT subjects (got '%s')",
             p->subject);
  }
}
/* ================================================================
 * Host proxy — a box that POSITIONS a host-composited element
 * (docs/lvgl-factory/08-HOST-PROXY-DESIGN.md). The renderer draws the
 * box + its interaction affordances (glass gesture surface, corner
 * handles, 3x3 align grid) and streams the box's rect + mode to the
 * host via the host_proxy_report import; the host places its own
 * element (DOM overlay, video plane) at the reported rect.
 * ================================================================ */
#define MAX_PROXIES 8
#define PROXY_HANDLE_COUNT 4
#define PROXY_CELL_COUNT 9
#define PROXY_ID_BUF 64
/* host_proxy_report phases (the wire contract — see host_imports.h) */
#define PROXY_PHASE_SYNC 0
#define PROXY_PHASE_START 1
#define PROXY_PHASE_MOVE 2
#define PROXY_PHASE_END 3
typedef struct {
  lv_obj_t *obj;                         /* the proxy box */
  lv_obj_t *glass;                       /* full-size gesture surface */
  lv_obj_t *handles[PROXY_HANDLE_COUNT]; /* corners: TL, TR, BL, BR */
  lv_obj_t *cells[PROXY_CELL_COUNT];     /* 3x3 align grid, row-major */
  char id[PROXY_ID_BUF];                 /* the host's stable join key */
  int32_t mode;                          /* current ui_ProxyMode */
  int32_t min_w, min_h;                  /* resize clamps (0 = none) */
  int32_t max_w, max_h;
  int32_t handle_size; /* 0 = DPI default */
  int32_t z;           /* opaque hint, echoed */
  /* Last-reported state — the per-tick sweep's change guard. */
  lv_area_t last_rect;
  int32_t last_mode;
  bool last_visible;
  bool reported;       /* false until the first (SYNC) report */
  bool gesture_active; /* between START and END */
} proxy_entry_t;
static proxy_entry_t proxy_registry[MAX_PROXIES];
static int proxy_count;
/* Align-grid cell index (row-major) → the 9 anchor aligns (the proto
 * Align enum is parity-gated to these LVGL values; O2: the cell snaps
 * the PROXY within its PARENT). */
static const lv_align_t proxy_cell_aligns[PROXY_CELL_COUNT] = {
    LV_ALIGN_TOP_LEFT,    LV_ALIGN_TOP_MID,    LV_ALIGN_TOP_RIGHT,
    LV_ALIGN_LEFT_MID,    LV_ALIGN_CENTER,     LV_ALIGN_RIGHT_MID,
    LV_ALIGN_BOTTOM_LEFT, LV_ALIGN_BOTTOM_MID, LV_ALIGN_BOTTOM_RIGHT,
};
/* Which proxy an object belongs to, for the DUMP only.
 *
 * `renderer_proxy_root` answers "is this the proxy box itself", and
 * `renderer_proxy_part` answers "is this one of the affordances that box
 * owns". Both return the proxy's stable id so a consumer of the dump can
 * tell TWO proxies apart rather than lumping every affordance together.
 *
 * These exist because the affordance objects are created by this file with
 * bare lv_obj_create and never pass through finalize_widget, so they carry
 * no uid and nothing downstream can name them. A geometry rule that sees
 * only coordinates cannot tell the designed glass-over-content stack
 * (UI-QUALITY-CONTRACTS §1.5b) from an accidental collision; these two keys
 * are the interpreter DECLARING its own composition rather than leaving the
 * rule to infer it from paint order, which §1.2 forbids. */
const char *renderer_proxy_root(const lv_obj_t *obj) {
  for (int i = 0; i < proxy_count; i++)
    if (proxy_registry[i].obj == obj)
      return proxy_registry[i].id;
  return NULL;
}
const char *renderer_proxy_part(const lv_obj_t *obj, const char **owner_id) {
  for (int i = 0; i < proxy_count; i++) {
    proxy_entry_t *e = &proxy_registry[i];
    if (!e->obj)
      continue;
    const char *part = NULL;
    if (e->glass == obj)
      part = "glass";
    for (int h = 0; !part && h < PROXY_HANDLE_COUNT; h++)
      if (e->handles[h] == obj)
        part = "handle";
    for (int c = 0; !part && c < PROXY_CELL_COUNT; c++)
      if (e->cells[c] == obj)
        part = "cell";
    if (part) {
      if (owner_id)
        *owner_id = e->id;
      return part;
    }
  }
  return NULL;
}
static proxy_entry_t *find_proxy_by_obj(const lv_obj_t *obj) {
  for (int i = 0; i < proxy_count; i++) {
    if (proxy_registry[i].obj == obj)
      return &proxy_registry[i];
  }
  return NULL;
}
/* Proxy slots are TOMBSTONED (obj = NULL), never compacted: the mode
 * observer's user_data points at the registry slot, so moving entries
 * would dangle a live observer. The sweep already skips NULL slots;
 * allocation reuses them (a patch removing + inserting proxies keeps
 * the pool coherent without growing past MAX_PROXIES). */
static proxy_entry_t *alloc_proxy_entry(void) {
  for (int i = 0; i < proxy_count; i++) {
    if (!proxy_registry[i].obj)
      return &proxy_registry[i];
  }
  if (proxy_count >= MAX_PROXIES)
    return NULL;
  return &proxy_registry[proxy_count++];
}
static void remove_proxy_entry(const lv_obj_t *obj) {
  proxy_entry_t *e = find_proxy_by_obj(obj);
  if (e)
    memset(e, 0, sizeof(*e)); /* tombstone — slot address stays valid */
}
/* Resolved corner-handle edge: explicit props value, else DPI-derived. */
static int32_t proxy_handle_px(const proxy_entry_t *e) {
  if (e->handle_size > 0)
    return e->handle_size;
  int32_t px = lv_display_get_dpi(lv_display_get_default()) / 10;
  return px < 12 ? 12 : px;
}
/* Safe corner-handle ext_click_area for the proxy's CURRENT size.
 *
 * Each edge carries two corner handles, and lv_obj_set_ext_click_area grows
 * a handle on every side — so two handles that are `gap` apart have their
 * HIT areas meet once each grows by gap/2, even though the drawn boxes are
 * still clear of each other. Growing by a fixed handle_px/2 therefore makes
 * the two handles on a short edge fight over a band in the middle, and
 * lv_indev_search_obj's reverse walk hands that band to whichever was
 * created later — the earlier handle has a strip it cannot be pressed in.
 *
 * The fix is not to forbid small proxies: it is to give each handle as much
 * grow as it can take without stealing its neighbour's. Uniform because
 * LVGL's ext_click_area is one value for all four sides, so the tighter axis
 * governs. 0 is a legitimate answer for a box with no room at all.
 *
 * Recomputed on resize as well as at construction — the safe value is a
 * function of the current box, not of the authored one. */
static int32_t proxy_handle_ext_px(const proxy_entry_t *e) {
  int32_t handle_px = proxy_handle_px(e);
  int32_t want = handle_px / 2;
  /* CONTENT extent, not the outer box: the handles are aligned inside the
   * padding/border, so the clear gap between the two on an edge is the
   * content extent minus their two widths. Measuring the outer box
   * overstates the room by exactly the insets and leaves the collision in
   * place on a proxy whose padding is what made it tight. */
  int32_t gap_x = lv_obj_get_content_width(e->obj) - 2 * handle_px;
  int32_t gap_y = lv_obj_get_content_height(e->obj) - 2 * handle_px;
  int32_t room = gap_x < gap_y ? gap_x : gap_y;
  if (room < 0)
    room = 0;
  int32_t safe = room / 2;
  return safe < want ? safe : want;
}
/* Apply that to every handle. Wired to LV_EVENT_SIZE_CHANGED on the proxy
 * rather than called once at construction: props stream AFTER children, so
 * at construction the box is still 0x0 and every handle would be pinned at
 * ext 0. Keying on the event means the value tracks the real box through
 * construction, a props-driven resize and a drag-resize alike. */
static void proxy_apply_handle_ext(proxy_entry_t *e) {
  int32_t ext = proxy_handle_ext_px(e);
  for (int i = 0; i < PROXY_HANDLE_COUNT; i++)
    if (e->handles[i])
      lv_obj_set_ext_click_area(e->handles[i], ext);
}
static void proxy_size_changed_cb(lv_event_t *ev) {
  proxy_entry_t *e = (proxy_entry_t *)lv_event_get_user_data(ev);
  if (e && e->obj)
    proxy_apply_handle_ext(e);
}
/* ONE emission site: read the post-layout rect + visibility, report,
 * cache as last-reported. Every phase funnels through here. */
static void proxy_emit(proxy_entry_t *e, int32_t phase) {
  lv_obj_update_layout(e->obj);
  lv_area_t coords;
  lv_obj_get_coords(e->obj, &coords);
  bool visible = lv_obj_is_visible(e->obj);
  int32_t flags = 0;
  if (!visible)
    flags = 1; /* bit0 = hidden */
  (void)host_proxy_report(e->id, (uint32_t)strlen(e->id), phase, e->mode,
                          coords.x1, coords.y1, lv_area_get_width(&coords),
                          lv_area_get_height(&coords), e->z, flags);
  e->last_rect = coords;
  e->last_mode = e->mode;
  e->last_visible = visible;
  e->reported = true;
}
static void proxy_set_shown(lv_obj_t *obj, bool shown) {
  if (shown) {
    lv_obj_remove_flag(obj, LV_OBJ_FLAG_HIDDEN);
  } else {
    lv_obj_add_flag(obj, LV_OBJ_FLAG_HIDDEN);
  }
}
/* Mode semantics (D3/D5): static clears CLICKABLE on the box itself so
 * events fall through to widgets beneath (the host element owns
 * interaction — pointer routing is the HOST's contract); interactive
 * modes show their affordance set.
 *
 * A NON-STATIC PROXY IS THE INTERACTION TARGET, AND ITS CONTENT CHILDREN
 * ARE INERT. This is BY DESIGN, not an accident of stacking: the glass is
 * full-bleed and carries LV_OBJ_FLAG_PRESS_LOCK, so it takes every press
 * inside the proxy and a control placed underneath never sees one. A
 * proxy in draggable/resizable/alignable mode exists to be dragged,
 * resized or aligned — routing some of its presses to a child would make
 * the drag surface unpredictable exactly where the child sits.
 *
 * Measured, tapping node centres taken from dump_tree, with an identical
 * button outside the proxy as a control: a button INSIDE fires only in
 * static mode; in draggable, resizable and alignable it fires nothing
 * while the outside control fires in all four. `interaction.clj`'s
 * proxy-content-inert canary pins this — it injects a pointer, because no
 * framebuffer or DOM assertion can see the difference.
 *
 * So: put only DECORATION inside a non-static proxy. An interactive
 * control there is dead, silently — no pixel differs and no event fires.
 * The overlap lane reports the glass-vs-content pair for this reason and
 * it is a DESIGNED stack, not a defect. */
static void proxy_apply_mode(proxy_entry_t *e, int32_t mode) {
  e->mode = mode;
  bool resizable = mode == (int32_t)ui_ProxyMode_PROXY_MODE_RESIZABLE;
  bool alignable = mode == (int32_t)ui_ProxyMode_PROXY_MODE_ALIGNABLE;
  bool glass_shown = false;
  if (mode == (int32_t)ui_ProxyMode_PROXY_MODE_DRAGGABLE || resizable)
    glass_shown = true;
  if (mode == (int32_t)ui_ProxyMode_PROXY_MODE_STATIC) {
    lv_obj_remove_flag(e->obj, LV_OBJ_FLAG_CLICKABLE);
  } else {
    lv_obj_add_flag(e->obj, LV_OBJ_FLAG_CLICKABLE);
  }
  proxy_set_shown(e->glass, glass_shown);
  for (int i = 0; i < PROXY_HANDLE_COUNT; i++)
    proxy_set_shown(e->handles[i], resizable);
  for (int i = 0; i < PROXY_CELL_COUNT; i++)
    proxy_set_shown(e->cells[i], alignable);
}
/* Re-anchor the proxy to TOP_LEFT at its current on-screen position so
 * gesture math (incremental vect deltas + parent-content clamps) is
 * uniform regardless of any prior align (an earlier 3x3 snap, an
 * authored align style). */
static void proxy_normalize_pos(lv_obj_t *obj) {
  lv_obj_t *parent = lv_obj_get_parent(obj);
  if (!parent)
    return;
  lv_obj_update_layout(obj);
  lv_area_t content;
  lv_obj_get_content_coords(parent, &content);
  lv_area_t coords;
  lv_obj_get_coords(obj, &coords);
  lv_obj_set_align(obj, LV_ALIGN_TOP_LEFT);
  lv_obj_set_pos(obj, coords.x1 - content.x1, coords.y1 - content.y1);
}
/* Body drag: incremental vect delta, clamped to the parent content area
 * (jettison clamps to the viewport — same semantics one level down). */
static void proxy_drag_move(proxy_entry_t *e, const lv_point_t *vect) {
  lv_obj_t *parent = lv_obj_get_parent(e->obj);
  if (!parent)
    return;
  int32_t span_x = lv_obj_get_content_width(parent) - lv_obj_get_width(e->obj);
  int32_t span_y =
      lv_obj_get_content_height(parent) - lv_obj_get_height(e->obj);
  if (span_x < 0)
    span_x = 0;
  if (span_y < 0)
    span_y = 0;
  int32_t x = lv_obj_get_x_aligned(e->obj) + vect->x;
  int32_t y = lv_obj_get_y_aligned(e->obj) + vect->y;
  x = LV_CLAMP(0, x, span_x);
  y = LV_CLAMP(0, y, span_y);
  lv_obj_set_pos(e->obj, x, y);
}
/* Corner resize, jettison quadrant semantics: the OPPOSITE corner stays
 * anchored; width/height clamp to min/max (min floors at 2x the handle
 * edge when unconstrained so handles stay usable). */
static void proxy_resize_move(proxy_entry_t *e, int corner,
                              const lv_point_t *vect) {
  bool left = false;
  if (corner == 0 || corner == 2)
    left = true;
  bool top = false;
  if (corner == 0 || corner == 1)
    top = true;
  int32_t handle_px = proxy_handle_px(e);
  /* 2*handle_px is the floor at which the two corner handles on an edge
   * still do not overlap as DRAWN boxes. An authored min below it does not
   * express a smaller proxy, it expresses handles on top of each other — so
   * it is a floor UNDER the authored value, not an alternative to it. (The
   * hit-area question is separate and handled by proxy_handle_ext_px, which
   * shrinks the grow instead of forbidding the size.) */
  int32_t draw_floor = 2 * handle_px;
  int32_t min_w = e->min_w > draw_floor ? e->min_w : draw_floor;
  int32_t min_h = e->min_h > draw_floor ? e->min_h : draw_floor;
  int32_t w = lv_obj_get_width(e->obj);
  int32_t h = lv_obj_get_height(e->obj);
  int32_t x = lv_obj_get_x_aligned(e->obj);
  int32_t y = lv_obj_get_y_aligned(e->obj);
  int32_t new_w = w + vect->x;
  int32_t new_h = h + vect->y;
  if (left)
    new_w = w - vect->x;
  if (top)
    new_h = h - vect->y;
  if (new_w < min_w)
    new_w = min_w;
  if (e->max_w > 0 && new_w > e->max_w)
    new_w = e->max_w;
  if (new_h < min_h)
    new_h = min_h;
  if (e->max_h > 0 && new_h > e->max_h)
    new_h = e->max_h;
  /* Anchor the opposite corner: a left/top edge move shifts the origin
   * by the actual (clamped) growth. */
  if (left)
    x += w - new_w;
  if (top)
    y += h - new_h;
  lv_obj_set_pos(e->obj, x, y);
  lv_obj_set_size(e->obj, new_w, new_h);
  proxy_apply_handle_ext(e);
}
/* O4: PRESS_LOST = END (final rect = wherever the box is). */
static void proxy_gesture_end(proxy_entry_t *e) {
  if (!e->gesture_active)
    return;
  e->gesture_active = false;
  proxy_emit(e, PROXY_PHASE_END);
}
static lv_point_t proxy_active_vect(void) {
  lv_point_t vect = {0, 0};
  lv_indev_t *indev = lv_indev_active();
  if (indev)
    lv_indev_get_vect(indev, &vect);
  return vect;
}
/* Glass overlay events — THE gesture surface in draggable/resizable
 * (the LVGL equivalent of jettison's pointer-events switching: without
 * it an interactive content child would eat the drag). MOVE reports are
 * NOT emitted here — the per-tick sweep is the one emission site for
 * rect changes; gestures emit only the START/END edges. */
static void proxy_glass_event_cb(lv_event_t *event) {
  proxy_entry_t *e = (proxy_entry_t *)lv_event_get_user_data(event);
  if (!e)
    return;
  bool movable = false;
  if (e->mode == (int32_t)ui_ProxyMode_PROXY_MODE_DRAGGABLE ||
      e->mode == (int32_t)ui_ProxyMode_PROXY_MODE_RESIZABLE)
    movable = true;
  switch (lv_event_get_code(event)) {
  case LV_EVENT_PRESSED:
    if (!movable)
      return;
    proxy_normalize_pos(e->obj);
    e->gesture_active = true;
    proxy_emit(e, PROXY_PHASE_START);
    break;
  case LV_EVENT_PRESSING:
    if (e->gesture_active && movable) {
      lv_point_t vect = proxy_active_vect();
      if (vect.x != 0 || vect.y != 0)
        proxy_drag_move(e, &vect);
    }
    break;
  case LV_EVENT_RELEASED:
  case LV_EVENT_PRESS_LOST:
    proxy_gesture_end(e);
    break;
  case LV_EVENT_INDEV_RESET:
    e->gesture_active = false; /* silent cancel — see setup_affordance */
    break;
  default:
    break;
  }
}
static void proxy_handle_event_cb(lv_event_t *event) {
  proxy_entry_t *e = (proxy_entry_t *)lv_event_get_user_data(event);
  if (!e)
    return;
  lv_obj_t *target = lv_event_get_current_target(event);
  int corner = -1;
  for (int i = 0; i < PROXY_HANDLE_COUNT; i++) {
    if (e->handles[i] == target)
      corner = i;
  }
  if (corner < 0)
    return;
  switch (lv_event_get_code(event)) {
  case LV_EVENT_PRESSED:
    if (e->mode != (int32_t)ui_ProxyMode_PROXY_MODE_RESIZABLE)
      return;
    proxy_normalize_pos(e->obj);
    e->gesture_active = true;
    proxy_emit(e, PROXY_PHASE_START);
    break;
  case LV_EVENT_PRESSING:
    if (e->gesture_active &&
        e->mode == (int32_t)ui_ProxyMode_PROXY_MODE_RESIZABLE) {
      lv_point_t vect = proxy_active_vect();
      if (vect.x != 0 || vect.y != 0)
        proxy_resize_move(e, corner, &vect);
    }
    break;
  case LV_EVENT_RELEASED:
  case LV_EVENT_PRESS_LOST:
    proxy_gesture_end(e);
    break;
  case LV_EVENT_INDEV_RESET:
    e->gesture_active = false; /* silent cancel — see setup_affordance */
    break;
  default:
    break;
  }
}
/* Align cells: RELEASED applies the snap so the END report (emitted
 * after) carries the post-align rect (LVGL sends RELEASED before
 * CLICKED, so CLICKED would order the report wrong); a press dragged
 * off the cell PRESS_LOSTs into a no-snap END. */
static void proxy_cell_event_cb(lv_event_t *event) {
  proxy_entry_t *e = (proxy_entry_t *)lv_event_get_user_data(event);
  if (!e)
    return;
  lv_obj_t *target = lv_event_get_current_target(event);
  int cell = -1;
  for (int i = 0; i < PROXY_CELL_COUNT; i++) {
    if (e->cells[i] == target)
      cell = i;
  }
  if (cell < 0)
    return;
  switch (lv_event_get_code(event)) {
  case LV_EVENT_PRESSED:
    if (e->mode != (int32_t)ui_ProxyMode_PROXY_MODE_ALIGNABLE)
      return;
    e->gesture_active = true;
    proxy_emit(e, PROXY_PHASE_START);
    break;
  case LV_EVENT_RELEASED:
    if (e->gesture_active) {
      lv_obj_set_align(e->obj, proxy_cell_aligns[cell]);
      lv_obj_set_pos(e->obj, 0, 0);
    }
    proxy_gesture_end(e);
    break;
  case LV_EVENT_PRESS_LOST:
    proxy_gesture_end(e);
    break;
  case LV_EVENT_INDEV_RESET:
    e->gesture_active = false; /* silent cancel — see setup_affordance */
    break;
  default:
    break;
  }
}
/* The "mode" binding observer: the INT subject is the mode's source of
 * truth (LVGL fires this once at attach, so the subject's initial value
 * wins over props.mode when bound). The sweep emits the SYNC carrying
 * the new mode — mode is a field of every report, no separate message. */
static void proxy_mode_observer_cb(lv_observer_t *observer,
                                   lv_subject_t *subject) {
  proxy_entry_t *e = (proxy_entry_t *)lv_observer_get_user_data(observer);
  if (!e || !e->obj)
    return;
  proxy_apply_mode(e, lv_subject_get_int(subject));
}
static void proxy_setup_affordance(lv_obj_t *obj, proxy_entry_t *e,
                                   lv_event_cb_t cb) {
  lv_obj_remove_style_all(obj);
  lv_obj_add_flag(obj, LV_OBJ_FLAG_FLOATING);
  lv_obj_add_flag(obj, LV_OBJ_FLAG_IGNORE_LAYOUT);
  lv_obj_add_flag(obj, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_remove_flag(obj, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_remove_flag(obj, LV_OBJ_FLAG_SCROLL_CHAIN);
  lv_obj_add_event_cb(obj, cb, LV_EVENT_PRESSED, e);
  lv_obj_add_event_cb(obj, cb, LV_EVENT_PRESSING, e);
  lv_obj_add_event_cb(obj, cb, LV_EVENT_RELEASED, e);
  lv_obj_add_event_cb(obj, cb, LV_EVENT_PRESS_LOST, e);
  /* Deleting the pressed affordance during a native hot reload delivers
   * INDEV_RESET, not PRESS_LOST. Cancel the gesture SILENTLY: no LVGL geometry
   * call is safe on a partially-destroyed tree, and the rebuild re-registers
   * the proxy + re-emits SYNC, which is the host's converge signal. */
  lv_obj_add_event_cb(obj, cb, LV_EVENT_INDEV_RESET, e);
}
/* Host-proxy assembly — runs in finalize, AFTER host_proxy_props
 * (field 41) decoded; content children (field 8) parented normally
 * during decode, so no staging is needed (the tabview precedent's
 * staging exists only because tab PAGES must exist first). All
 * renderer-created sub-objects are FLOATING + IGNORE_LAYOUT so author
 * content/layout is undisturbed. */
static void apply_host_proxy(widget_ctx_t *ctx) {
  lv_obj_t *obj = ctx->self;
  ui_WidgetNode *node = ctx->node;
  if (node->which_widget_props != ui_WidgetNode_host_proxy_props_tag) {
    LOG_ERROR("host_proxy node without host_proxy_props");
    ctx->error = -1;
    return;
  }
  ui_HostProxyProps *props = &node->widget_props.host_proxy_props;
  if (props->proxy_id[0] == '\0') {
    LOG_ERROR("host proxy with empty proxy_id");
    ctx->error = -1;
    return;
  }
  proxy_entry_t *e = alloc_proxy_entry();
  if (!e) {
    LOG_ERROR("proxy registry full (%d max)", MAX_PROXIES);
    ctx->error = -1;
    return;
  }
  memset(e, 0, sizeof(*e));
  e->obj = obj;
  strncpy(e->id, props->proxy_id, sizeof(e->id) - 1);
  e->id[sizeof(e->id) - 1] = '\0';
  e->min_w = props->min_w;
  e->min_h = props->min_h;
  e->max_w = props->max_w;
  e->max_h = props->max_h;
  e->handle_size = (int32_t)props->handle_size;
  e->z = props->z;
  /* Content overflow must never scroll the box out from under the host
   * element (D6 — clipping isolation is LVGL's default). */
  lv_obj_remove_flag(obj, LV_OBJ_FLAG_SCROLLABLE);
  /* The default look is attached at CREATE time (proxy_apply_default_style)
   * so wire-authored style groups — attached later during decode — outrank
   * it; see that helper for the full precedence rationale. */
  /* Glass overlay — created after the content children (props stream
   * after children), so it sits above them; PRESS_LOCK + cleared
   * scroll-chain keep a scrollable ancestor from stealing the drag. */
  e->glass = lv_obj_create(obj);
  if (!e->glass) {
    LOG_ERROR("host proxy glass allocation failed");
    ctx->error = -1;
    return;
  }
  proxy_setup_affordance(e->glass, e, proxy_glass_event_cb);
  lv_obj_add_flag(e->glass, LV_OBJ_FLAG_PRESS_LOCK);
  lv_obj_add_event_cb(obj, proxy_size_changed_cb, LV_EVENT_SIZE_CHANGED, e);
  lv_obj_set_size(e->glass, lv_pct(100), lv_pct(100));
  int32_t handle_px = proxy_handle_px(e);
  static const lv_align_t handle_aligns[PROXY_HANDLE_COUNT] = {
      LV_ALIGN_TOP_LEFT, LV_ALIGN_TOP_RIGHT, LV_ALIGN_BOTTOM_LEFT,
      LV_ALIGN_BOTTOM_RIGHT};
  for (int i = 0; i < PROXY_HANDLE_COUNT; i++) {
    lv_obj_t *handle = lv_obj_create(obj);
    if (!handle) {
      LOG_ERROR("host proxy handle allocation failed");
      ctx->error = -1;
      return;
    }
    proxy_setup_affordance(handle, e, proxy_handle_event_cb);
    lv_obj_add_flag(handle, LV_OBJ_FLAG_PRESS_LOCK);
    lv_obj_set_size(handle, handle_px, handle_px);
    lv_obj_align(handle, handle_aligns[i], 0, 0);
    lv_obj_set_style_bg_color(handle, lv_color_white(), 0);
    lv_obj_set_style_bg_opa(handle, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(handle, 1, 0);
    lv_obj_set_style_border_color(handle, lv_color_black(), 0);
    e->handles[i] = handle;
  }
  proxy_apply_handle_ext(e);
  for (int i = 0; i < PROXY_CELL_COUNT; i++) {
    lv_obj_t *cell = lv_obj_create(obj);
    if (!cell) {
      LOG_ERROR("host proxy align cell allocation failed");
      ctx->error = -1;
      return;
    }
    proxy_setup_affordance(cell, e, proxy_cell_event_cb);
    lv_obj_set_size(cell, lv_pct(33), lv_pct(33));
    lv_obj_align(cell, proxy_cell_aligns[i], 0, 0);
    lv_obj_set_style_border_width(cell, 1, 0);
    lv_obj_set_style_border_color(cell, lv_color_white(), 0);
    lv_obj_set_style_border_opa(cell, LV_OPA_50, 0);
    lv_obj_set_style_bg_color(cell, lv_color_white(), 0);
    lv_obj_set_style_bg_opa(cell, LV_OPA_10, 0);
    e->cells[i] = cell;
  }
  proxy_apply_mode(e, (int32_t)props->mode);
}
void proxy_report_sweep(void) {
  for (int i = 0; i < proxy_count; i++) {
    proxy_entry_t *e = &proxy_registry[i];
    if (!e->obj)
      continue;
    lv_area_t coords;
    lv_obj_get_coords(e->obj, &coords);
    bool visible = lv_obj_is_visible(e->obj);
    if (!e->reported || coords.x1 != e->last_rect.x1 ||
        coords.y1 != e->last_rect.y1 || coords.x2 != e->last_rect.x2 ||
        coords.y2 != e->last_rect.y2 || e->mode != e->last_mode ||
        visible != e->last_visible) {
      int32_t phase = PROXY_PHASE_SYNC;
      if (e->gesture_active)
        phase = PROXY_PHASE_MOVE;
      proxy_emit(e, phase);
    }
  }
}
/* Grid track templates must outlive decode (LVGL keeps the pointer):
 * bounded pool, reset per load. 13 = 12 tracks + LV_GRID_TEMPLATE_LAST. */
/* Sized for the demo-parity screen: every grid container costs two
 * templates (cols + rows) and the lv_demo_widgets recreation alone
 * carries seventeen grid containers. */
#define MAX_GRID_TEMPLATES 64
static int32_t grid_template_pool[MAX_GRID_TEMPLATES][13];
static int grid_template_count;
static int32_t *alloc_grid_template(const int32_t *tracks, pb_size_t count) {
  if (grid_template_count >= MAX_GRID_TEMPLATES || count > 12) {
    LOG_ERROR("grid template pool exhausted or track list too long");
    return NULL;
  }
  int32_t *slot = grid_template_pool[grid_template_count++];
  for (pb_size_t i = 0; i < count; i++)
    slot[i] = tracks[i];
  slot[count] = LV_GRID_TEMPLATE_LAST;
  return slot;
}
/* Tabview assembly — runs in finalize, AFTER tabview_props (field 38)
 * decoded and all children were staged (see the widget_ctx_t staging
 * note). Ordering inside follows lv_tabview.c constraints:
 * tab_bar_position FIRST (it re-applies the stored bar size on the new
 * axis), then tab_bar_size (it writes width OR height depending on the
 * current axis), then add_tab per name, then child distribution, and
 * set_active LAST with LV_ANIM_OFF (it calls lv_obj_update_layout, so
 * the frozen frame lands on the final scroll-snap position).
 *
 * NOTE: tab-bar slot children are REPARENTED into the bar, so theme
 * styles that dispatch on creation parentage (the tab-button branch,
 * the pages branch for plain lv_obj) do not apply to them — the slot is
 * meant for decor (images, labels) like the demo's logo/title. */
static void apply_tabview(widget_ctx_t *ctx) {
  lv_obj_t *tv = ctx->self;
  ui_WidgetNode *node = ctx->node;
  if (node->which_widget_props != ui_WidgetNode_tabview_props_tag) {
    LOG_ERROR("tabview node without tabview_props (%d staged children)",
              ctx->tab_staged_count);
    ctx->error = -1;
    goto cleanup;
  }
  {
    ui_TabviewProps *p = &node->widget_props.tabview_props;
    lv_obj_t *pages[MAX_TABVIEW_CHILDREN];
    lv_obj_t *bar;
    pb_size_t i;
    int j;
    pb_size_t page_idx = 0;
    if (p->tab_bar_position != 0) {
      lv_tabview_set_tab_bar_position(tv, (lv_dir_t)p->tab_bar_position);
    }
    if (p->tab_bar_size != 0) {
      lv_tabview_set_tab_bar_size(tv, p->tab_bar_size);
    }
    for (i = 0; i < p->tab_names_count; i++) {
      pages[i] = lv_tabview_add_tab(tv, p->tab_names[i]);
    }
    bar = lv_tabview_get_tab_bar(tv);
    if (p->tab_bar_pad_left != 0) {
      lv_obj_set_style_pad_left(bar, p->tab_bar_pad_left, 0);
    }
    for (j = 0; j < ctx->tab_staged_count; j++) {
      lv_obj_t *child =
          ctx->tab_staging ? lv_obj_get_child(ctx->tab_staging, 0) : NULL;
      if (!child) {
        LOG_ERROR("tabview staging missing child %d of %d", j,
                  ctx->tab_staged_count);
        ctx->error = -1;
        goto cleanup;
      }
      if (ctx->tab_in_bar[j]) {
        lv_obj_set_parent(child, bar);
      } else if (page_idx < p->tab_names_count) {
        lv_obj_set_parent(child, pages[page_idx++]);
      } else {
        LOG_ERROR("tabview has more content children than tab_names "
                  "(%d names)",
                  (int)p->tab_names_count);
        ctx->error = -1;
        goto cleanup;
      }
    }
    if (page_idx != p->tab_names_count) {
      LOG_ERROR("tabview content children (%d) != tab_names (%d)",
                (int)page_idx, (int)p->tab_names_count);
      ctx->error = -1;
      goto cleanup;
    }
    /* Activation is deferred until the whole screen is decoded — see the
     * pending_tabview queue note. */
    if (p->tab_names_count > 0) {
      if (pending_tabview_count >= MAX_PENDING_TABVIEW) {
        LOG_ERROR("pending tabview queue overflow (max %d)",
                  MAX_PENDING_TABVIEW);
        ctx->error = -1;
        goto cleanup;
      }
      pending_tabview_t *pt = &pending_tabview[pending_tabview_count++];
      pt->tabview = tv;
      pt->active_index = p->active_index;
    }
  }
cleanup:
  if (ctx->tab_staging) {
    lv_obj_delete(ctx->tab_staging);
    ctx->tab_staging = NULL;
  }
}
/* Append the styles this decode attached (ctx->added_styles) to the
 * node's uid-registry record — the in-place style-morph bookkeeping. */
static void record_added_styles(uint32_t uid, const widget_ctx_t *ctx) {
  if (uid == 0 || ctx->added_style_count == 0)
    return;
  uid_entry_t *entry = find_uid_entry(uid);
  if (!entry)
    return;
  for (int i = 0;
       i < ctx->added_style_count && entry->style_count < MAX_STYLES_PER_WIDGET;
       i++) {
    entry->styles[entry->style_count++] = ctx->added_styles[i];
  }
}
/* Finalize widget after all fields are decoded */
static void finalize_widget(widget_ctx_t *ctx) {
  lv_obj_t *obj = ensure_widget(ctx);
  if (!obj) {
    ctx->error = -1;
    return;
  }
  ui_WidgetNode *node = ctx->node;
  /* Mirror the codegen-assigned identity into user_data + the uid registry
   * (tree patching). Registration is ATOMIC: register_uid is consulted FIRST,
   * and only on SUCCESS does the identity propagate — user_data is set and
   * `registered_uid` becomes the key every downstream uid-keyed call uses.
   * A duplicate/overflow uid is a contract violation (the host is untrusted):
   * register_uid refuses, and the node then behaves exactly like a
   * legitimately-unidentified (uid==0) one — no stray user_data, no
   * cross-attributed style/gesture ownership stolen from the real holder, and
   * no second live object claiming one identity. ctx->error still propagates
   * so controls_load_ui reports non-OK. A morph (preset self, user_data
   * already set) keeps its existing registration. */
  uint32_t registered_uid = 0;
  if (node->uid != 0) {
    if (lv_obj_get_user_data(obj) != NULL) {
      /* Morph self: already registered by the original build. */
      registered_uid = node->uid;
    } else if (register_uid(node->uid, obj, (int32_t)node->type) == 0) {
      lv_obj_set_user_data(obj, (void *)(uintptr_t)node->uid);
      registered_uid = node->uid;
    } else {
      ctx->error = -1;
    }
  }
  if (node->x != 0 || node->y != 0) {
    lv_obj_set_pos(obj, node->x, node->y);
  }
  /* bare: strip theme/base styles, then re-apply the node's own
   * declarations — the style groups that streamed before this flag
   * (recorded during decode) AND the Layout flow/alignment, whose
   * create-time application lives in LOCAL styles the strip erased. */
  if (node->bare) {
    lv_obj_remove_style_all(obj);
    for (int i = 0; i < ctx->added_style_count; i++)
      lv_obj_add_style(obj, ctx->added_styles[i].style,
                       ctx->added_styles[i].selector);
    apply_node_layout(obj, node);
  }
  /* LVGL flag/state bitmasks — direct-cast (values parity-gated) */
  if (node->obj_flags != 0)
    lv_obj_add_flag(obj, (lv_obj_flag_t)node->obj_flags);
  if (node->obj_flags_clear != 0)
    lv_obj_remove_flag(obj, (lv_obj_flag_t)node->obj_flags_clear);
  if (node->states != 0)
    lv_obj_add_state(obj, (lv_state_t)node->states);
  if (node->scroll_dir != 0)
    lv_obj_set_scroll_dir(obj, (lv_dir_t)node->scroll_dir);
  /* Touch affordance (WidgetNode.hit_slop) — the ONE wire route to a hit box
   * larger than the drawn box, and it reaches ANY widget rather than riding a
   * per-class prop. Guarded on != 0 for two independent reasons, both real:
   * lv_obj_set_ext_click_area calls lv_obj_allocate_spec_attr, so an
   * unconditional apply would allocate that struct on EVERY node of every
   * tree; and under an UPDATE_PROPS morph a zero means "unchanged" because
   * the differ strips defaults — the same idiom the slider value and the
   * event bindings already use here.
   *
   * The guard is only SAFE because the two classes whose vendored
   * constructors set a pad of their own are zeroed at creation (renderer.h,
   * applied in ensure_widget). Without that, a node asking for no slop would
   * silently keep the inherited halo and this guard would be the bug. */
  if (node->hit_slop != 0)
    lv_obj_set_ext_click_area(obj, LV_DPX((int32_t)node->hit_slop));
  if (node->grid_col_dsc_count > 0 && node->grid_row_dsc_count > 0) {
    /* cols + rows are allocated as a consecutive PAIR (each grid container
     * costs exactly two slots, and nanopb caps each track list at 12 so the
     * "track list too long" arm never fires on a decoded node). So
     * grid_template_count is EVEN at every container boundary, and the pool
     * wall (grid_template_count >= MAX_GRID_TEMPLATES) always lands on the cols
     * alloc — never between cols and rows. A partial "cols allocated, rows
     * failed" split is therefore unreachable: on exhaustion BOTH allocs fail,
     * nothing is stranded, and the node degrades to non-grid with ctx->error
     * set (the load/patch then reports non-OK). The REPLACE/INSERT path is
     * additionally refused up front by the demand-aware grid check
     * (grid_demand_exceeds_pool) before it can reach this wall mid-subtree. */
    int32_t *cols =
        alloc_grid_template(node->grid_col_dsc, node->grid_col_dsc_count);
    int32_t *rows =
        alloc_grid_template(node->grid_row_dsc, node->grid_row_dsc_count);
    if (cols && rows) {
      lv_obj_set_grid_dsc_array(obj, cols, rows);
      lv_obj_set_layout(obj, LV_LAYOUT_GRID);
    } else {
      ctx->error = -1;
    }
  }
  apply_widget_text(obj, node->type, ctx->text);
  apply_widget_props(obj, node);
  /* Tabview: add tabs + distribute the staged children (needs ctx, not
   * just the node — the staging container lives there). */
  if (node->type == ui_WidgetType_WIDGET_TABVIEW) {
    apply_tabview(ctx);
  }
  /* Host proxy: register + assemble the interaction affordances (the
   * content children already parented during decode). */
  if (node->type == ui_WidgetType_WIDGET_HOST_PROXY) {
    apply_host_proxy(ctx);
  }
  /* Apply reactive bindings (text, value, checked) */
  if (ctx->binding_count > 0) {
    if (pending_bindings_count >= MAX_PENDING_BINDINGS) {
      LOG_ERROR("pending binding queue overflow (max %d)",
                MAX_PENDING_BINDINGS);
      ctx->error = -1;
    } else {
      pending_bindings_t *p = &pending_bindings[pending_bindings_count++];
      p->obj = obj;
      p->wtype = node->type;
      p->binding_count = ctx->binding_count;
      memcpy(p->bindings, ctx->bindings, sizeof(p->bindings));
      p->bind_format_count = ctx->bind_format_count;
      memcpy(p->bind_formats, ctx->bind_formats, sizeof(p->bind_formats));
    }
  }
  /* Apply conditional visibility (show-when) */
  if (node->has_visibility && node->visibility.subject[0] != '\0') {
    if (pending_visibility_count >= MAX_PENDING_VISIBILITY) {
      LOG_ERROR("pending visibility queue overflow (max %d)",
                MAX_PENDING_VISIBILITY);
      ctx->error = -1;
    } else {
      pending_visibility_t *p = &pending_visibility[pending_visibility_count++];
      p->obj = obj;
      p->vis = node->visibility;
    }
  }
  /* Apply reactive checked-state binding (checked_when) */
  if (node->has_checked_when && node->checked_when.subject[0] != '\0') {
    if (pending_checked_count >= MAX_PENDING_CHECKED) {
      LOG_ERROR("pending checked_when queue overflow (max %d)",
                MAX_PENDING_CHECKED);
      ctx->error = -1;
    } else {
      pending_checked_t *p = &pending_checked[pending_checked_count++];
      p->obj = obj;
      p->bind = node->checked_when;
    }
  }
  /* Apply reactive enabled-state binding (enabled_when) */
  if (node->has_enabled_when && node->enabled_when.subject[0] != '\0') {
    if (pending_enabled_count >= MAX_PENDING_ENABLED) {
      LOG_ERROR("pending enabled_when queue overflow (max %d)",
                MAX_PENDING_ENABLED);
      ctx->error = -1;
    } else {
      pending_enabled_t *p = &pending_enabled[pending_enabled_count++];
      p->obj = obj;
      p->bind = node->enabled_when;
    }
  }
  /* Apply reactive text-color binding (color_when) */
  if (node->has_color_when && node->color_when.when.subject[0] != '\0') {
    if (pending_color_count >= MAX_PENDING_COLOR) {
      LOG_ERROR("pending color_when queue overflow (max %d)",
                MAX_PENDING_COLOR);
      ctx->error = -1;
    } else {
      pending_color_t *p = &pending_color[pending_color_count++];
      p->obj = obj;
      p->bind = node->color_when;
    }
  }
  /* Belt to the UPDATE arm's has_event reject: attaching an event cb to an
   * ALREADY-LIVE object under morph would duplicate the callback (two
   * host_commands per click). The patch layer rejects event-carrying UPDATEs
   * before decode; if one ever reaches finalize anyway, error — never
   * double-attach. */
  if (morph_in_progress && node->has_event) {
    LOG_ERROR("morph carried an event binding (uid %u) — refusing the "
              "duplicate attach",
              (unsigned)node->uid);
    ctx->error = -1;
  } else if (node->has_event && (node->event.name[0] != '\0' ||
                                 node->event.set_subject[0] != '\0')) {
    /* Queue the set_subject for the batch-end resolve. The callback below
       * copies the NAME and resolves at fire time, so the attach itself needs
       * no registry — but a name that will NEVER resolve is a dead control,
       * and Screen.subjects streams after the tree, so the verdict can only
       * be reached at the drain (see pending_event_subject). */
    if (node->event.set_subject[0] != '\0') {
      if (pending_event_subject_count >= MAX_PENDING_EVENT_SUBJECT) {
        LOG_ERROR("pending event-subject queue overflow (max %d)",
                  MAX_PENDING_EVENT_SUBJECT);
        ctx->error = -1;
      } else {
        pending_event_subject_t *p =
            &pending_event_subject[pending_event_subject_count++];
        strncpy(p->subject, node->event.set_subject, sizeof(p->subject) - 1);
        p->subject[sizeof(p->subject) - 1] = '\0';
        p->uid = node->uid;
      }
    }
    event_cb_data_t *cb_data = malloc(sizeof(event_cb_data_t));
    if (cb_data) {
      /* Copy strings from proto decode buffer (owned by cb_data) */
      strncpy(cb_data->name, node->event.name, sizeof(cb_data->name) - 1);
      cb_data->name[sizeof(cb_data->name) - 1] = '\0';
      strncpy(cb_data->set_subject, node->event.set_subject,
              sizeof(cb_data->set_subject) - 1);
      cb_data->set_subject[sizeof(cb_data->set_subject) - 1] = '\0';
      cb_data->int_value = node->event.int_value;
      cb_data->include_widget_value = node->event.include_widget_value;
      cb_data->set_value = node->event.set_value;
      cb_data->toggle = node->event.toggle;
      cb_data->notify_host = node->event.notify_host;
      /* R5b: COPY the pre-encoded cmd.* template into the persistent
           * cb_data BEFORE the decode site's pb_release frees the nanopb
           * FT_POINTER copy. .present stays false (memset by malloc? no —
           * malloc is uninitialized, so set it explicitly) when absent. */
      cb_data->cmd.present = false;
      if (node->event.cmd) {
        if (cmd_spec_copy_from_proto(&cb_data->cmd, node->event.cmd) != 0)
          ctx->error = -1;
      }
      /* A crafted .pb carrying BOTH cmd and cmd_by_value violates the
           * mutual-exclusion contract the schema + emit layers enforce. The
           * host is untrusted (section 8), so surface it here (like the
           * OOB-index and count>16 LOG_ERRORs) instead of resolving it
           * silently; cmd_by_value still deterministically wins at emit. */
      if (node->event.cmd && node->event.cmd_by_value_count > 0)
        LOG_ERROR("event carries BOTH cmd and cmd_by_value (%u) — mutually "
                  "exclusive; cmd_by_value wins",
                  (unsigned)node->event.cmd_by_value_count);
      /* R5b cmd-by-value: COPY the FIXED templates the widget's int value
           * index-selects among into a malloc'd persistent array (the single
           * cmd is inline; a 16-entry array is too large to inline). Both
           * fields ALWAYS init NULL/0 (malloc is uninitialized) so a decode
           * failure or the no-by-value case frees safely and never mis-emits.
           */
      cb_data->cmd_by_value = NULL;
      cb_data->cmd_by_value_count = 0;
      if (node->event.cmd_by_value_count > 0 && node->event.cmd_by_value) {
        uint32_t bvn = node->event.cmd_by_value_count;
        if (bvn > CMD_PATCH_MAX_BY_VALUE) {
          LOG_ERROR("event cmd_by_value count %u exceeds max %u", (unsigned)bvn,
                    (unsigned)CMD_PATCH_MAX_BY_VALUE);
          ctx->error = -1;
        } else {
          cmd_spec_t *arr = malloc(sizeof(cmd_spec_t) * bvn);
          if (!arr) {
            ctx->error = -1;
          } else {
            bool ok = true;
            for (uint32_t i = 0; i < bvn; i++) {
              if (cmd_spec_copy_from_proto(&arr[i],
                                           &node->event.cmd_by_value[i]) != 0) {
                ok = false;
                ctx->error = -1;
                break;
              }
            }
            if (ok) {
              cb_data->cmd_by_value = arr;
              cb_data->cmd_by_value_count = bvn;
            } else {
              free(arr);
            }
          }
        }
      }
      /* Map proto EventTrigger enum to LVGL event code */
      switch (node->event.trigger) {
      case ui_EventTrigger_TRIGGER_VALUE_CHANGED:
        cb_data->trigger = LV_EVENT_VALUE_CHANGED;
        break;
      case ui_EventTrigger_TRIGGER_LONG_PRESSED:
        cb_data->trigger = LV_EVENT_LONG_PRESSED;
        break;
      default:
        cb_data->trigger = LV_EVENT_CLICKED;
        break;
      }
      lv_obj_add_event_cb(obj, button_event_cb, cb_data->trigger, cb_data);
      lv_obj_add_event_cb(obj, cleanup_event_data_cb, LV_EVENT_DELETE, cb_data);
    } else {
      /* OOM here would leave a dead, silently-unwired control (B2). Fail
           * the load loud instead of a green build with a non-responsive
           * widget. */
      LOG_ERROR("event_cb_data alloc failed — control would be inert");
      ctx->error = -1;
    }
  }
  /* R5b cmd-out: a gesture-surface (host-proxy) node carries the pre-encoded
   * gesture→cmd templates on WidgetNode.gestures (FT_POINTER). COPY them into
   * persistent storage and register them with the main.c drain BEFORE the
   * decode site's pb_release frees the nanopb copy. The controls_tick drain
   * matches each buffered gesture_decision_t to its GestureSpec.kind and emits
   * the patched cmd via cmd_patch_emit. */
  if (node->gestures_count > 0 && node->gestures) {
    cmd_gesture_spec_t specs[CMD_PATCH_MAX_GESTURES];
    uint32_t n = 0;
    for (pb_size_t i = 0; i < node->gestures_count; i++) {
      if (!node->gestures[i].has_cmd)
        continue;
      if (n >= CMD_PATCH_MAX_GESTURES) {
        /* More cmd-bearing gestures than the registry holds (B1). The
               * old loop truncated the surplus SILENTLY — a gesture that emits
               * nothing on the target. Fail the load loud instead. */
        LOG_ERROR("gesture spec count exceeds max %d — refusing load",
                  CMD_PATCH_MAX_GESTURES);
        ctx->error = -1;
        break;
      }
      specs[n].kind = (uint32_t)node->gestures[i].kind;
      if (cmd_spec_copy_from_proto(&specs[n].cmd, &node->gestures[i].cmd) !=
          0) {
        ctx->error = -1;
        continue;
      }
      n++;
    }
    /* Own the set by the REGISTERED uid so an incremental REMOVE of the
       * gesture surface clears it (ITEM 7 — unregister_subtree). A collided
       * node (registered_uid==0) never steals ownership from the real holder. */
    controls_gesture_specs_set(specs, n, registered_uid);
  }
  record_added_styles(registered_uid, ctx);
}
/* ================================================================
 * Subject declaration decode callback
 * ================================================================ */
static bool subjects_decode_cb(pb_istream_t *stream, const pb_field_t *field,
                               void **arg) {
  (void)field;
  (void)arg;
  if (subject_count >= MAX_SUBJECTS) {
    LOG_ERROR("subject registry full (%d max)", MAX_SUBJECTS);
    subject_overflow = true; /* surfaced as a load failure (return below) */
    /* Skip remaining bytes */
    uint8_t skip;
    while (stream->bytes_left > 0) {
      if (!pb_read(stream, &skip, 1))
        return false;
    }
    return true;
  }
  ui_SubjectDeclaration decl = ui_SubjectDeclaration_init_zero;
  if (!pb_decode(stream, ui_SubjectDeclaration_fields, &decl))
    return false;
  subject_entry_t *entry = &subject_registry[subject_count];
  strncpy(entry->name, decl.name, sizeof(entry->name) - 1);
  entry->name[sizeof(entry->name) - 1] = '\0';
  entry->type = (int)decl.type;
  if (decl.type == ui_SubjectType_SUBJECT_STRING) {
    /* Initialize string subject with caller-owned buffers */
    const char *initial = "";
    if (decl.which_initial == ui_SubjectDeclaration_string_initial_tag) {
      initial = decl.initial.string_initial;
    }
    strncpy(entry->str_buf, initial, SUBJECT_STRING_BUF_SIZE - 1);
    entry->str_buf[SUBJECT_STRING_BUF_SIZE - 1] = '\0';
    memset(entry->str_prev_buf, 0, SUBJECT_STRING_BUF_SIZE);
    lv_subject_init_string(&entry->subject, entry->str_buf, entry->str_prev_buf,
                           SUBJECT_STRING_BUF_SIZE, entry->str_buf);
  } else {
    /* INT subject */
    int32_t initial = 0;
    if (decl.which_initial == ui_SubjectDeclaration_int_initial_tag) {
      initial = decl.initial.int_initial;
    }
    lv_subject_init_int(&entry->subject, initial);
  }
  subject_count++;
  return true;
}
/* ================================================================
 * Binding + bind_formats map decode callbacks
 * ================================================================ */
static bool bindings_decode_cb(pb_istream_t *stream, const pb_field_t *field,
                               void **arg) {
  (void)field;
  widget_ctx_t *ctx = (widget_ctx_t *)*arg;
  ui_WidgetNode_BindingsEntry entry = ui_WidgetNode_BindingsEntry_init_zero;
  if (!pb_decode(stream, ui_WidgetNode_BindingsEntry_fields, &entry))
    return false;
  if (ctx->binding_count < MAX_BINDINGS_PER_WIDGET && entry.key[0] != '\0') {
    binding_entry_t *b = &ctx->bindings[ctx->binding_count];
    strncpy(b->key, entry.key, sizeof(b->key) - 1);
    b->key[sizeof(b->key) - 1] = '\0';
    strncpy(b->value, entry.value, sizeof(b->value) - 1);
    b->value[sizeof(b->value) - 1] = '\0';
    ctx->binding_count++;
  } else if (entry.key[0] != '\0') {
    /* A real binding dropped for lack of pool room is SILENT STATE LOSS — the
       * widget renders unbound. Fail the decode instead of lying green. */
    LOG_ERROR("widget bindings exceed MAX_BINDINGS_PER_WIDGET (%d)",
              MAX_BINDINGS_PER_WIDGET);
    ctx->error = -1;
  }
  return true;
}
static bool bind_formats_decode_cb(pb_istream_t *stream,
                                   const pb_field_t *field, void **arg) {
  (void)field;
  widget_ctx_t *ctx = (widget_ctx_t *)*arg;
  ui_WidgetNode_BindFormatsEntry entry =
      ui_WidgetNode_BindFormatsEntry_init_zero;
  if (!pb_decode(stream, ui_WidgetNode_BindFormatsEntry_fields, &entry))
    return false;
  if (ctx->bind_format_count < MAX_BINDINGS_PER_WIDGET &&
      entry.key[0] != '\0') {
    bind_format_entry_t *f = &ctx->bind_formats[ctx->bind_format_count];
    strncpy(f->key, entry.key, sizeof(f->key) - 1);
    f->key[sizeof(f->key) - 1] = '\0';
    strncpy(f->value, entry.value, sizeof(f->value) - 1);
    f->value[sizeof(f->value) - 1] = '\0';
    ctx->bind_format_count++;
  } else if (entry.key[0] != '\0') {
    LOG_ERROR("widget bind_formats exceed MAX_BINDINGS_PER_WIDGET (%d)",
              MAX_BINDINGS_PER_WIDGET);
    ctx->error = -1;
  }
  return true;
}
/* ================================================================
 * Apply bindings to widget after creation
 * ================================================================ */
static const char *find_bind_format(const bind_format_entry_t *formats,
                                    int count, const char *key) {
  for (int i = 0; i < count; i++) {
    if (strcmp(formats[i].key, key) == 0) {
      return formats[i].value;
    }
  }
  return NULL;
}
/* Custom observer for bar value binding (lv_bar_bind_value does not exist) */
static void bar_value_observer_cb(lv_observer_t *observer,
                                  lv_subject_t *subject) {
  lv_obj_t *bar = lv_observer_get_target_obj(observer);
  if (bar) {
    lv_bar_set_value(bar, lv_subject_get_int(subject), LV_ANIM_OFF);
  }
}
/* Custom observer for spinbox value binding (lv_spinbox_bind_value does not
 * exist) */
static void spinbox_value_observer_cb(lv_observer_t *observer,
                                      lv_subject_t *subject) {
  lv_obj_t *sb = lv_observer_get_target_obj(observer);
  if (sb) {
    lv_spinbox_set_value(sb, lv_subject_get_int(subject));
  }
}
/* SYNC C1: value->index dropdown binding. The subject holds the device enum
 * NUMBER; the options are 1-based (enum-options drops _UNSPECIFIED / :not-in),
 * so lv_dropdown_bind_value's number-as-index is off-by-one. Scan the decoded
 * option_values (same order as the options) for the entry whose value == the
 * subject int and select THAT index. Fires once on add for the initial value.
 */
static void dropdown_value_observer_cb(lv_observer_t *observer,
                                       lv_subject_t *subject) {
  lv_obj_t *dd = lv_observer_get_target_obj(observer);
  if (!dd)
    return;
  const dropdown_value_map_t *m = find_dropdown_value_map(dd);
  if (!m)
    return; /* no map (dropdown deleted mid-reload) — safe no-op */
  int32_t val = lv_subject_get_int(subject);
  for (pb_size_t i = 0; i < m->count; i++) {
    if (m->values[i] == val) {
      lv_dropdown_set_selected(dd, (uint32_t)i);
      return;
    }
  }
  /* value not among the options → leave the current selection unchanged */
}
static void apply_bindings(const pending_bindings_t *p) {
  lv_obj_t *obj = p->obj;
  if (!obj)
    return;
  for (int i = 0; i < p->binding_count; i++) {
    const char *key = p->bindings[i].key;
    const char *subject_name = p->bindings[i].value;
    subject_entry_t *entry = find_subject(subject_name);
    if (!entry) {
      /* B7/ITEM-8b: a binding to a never-declared subject is a dead
           * control (never reacts to state). Latch the load failure and skip
           * this binding; the load returns -1 rather than half-wired. */
      LOG_ERROR("binding '%s' references unknown subject '%s'", key,
                subject_name);
      load_resource_error = true;
      continue;
    }
    if (strcmp(key, "text") == 0) {
      /* Label text binding — requires format string for INT subjects */
      const char *fmt =
          find_bind_format(p->bind_formats, p->bind_format_count, "text");
      if (!fmt && entry->type == 0) {
        /* INT subject with no explicit format → default to "%d" */
        fmt = "%d";
      }
      lv_label_bind_text(obj, &entry->subject, fmt);
    } else if (strcmp(key, "value") == 0) {
      /* Value binding — dispatch by widget type */
      switch (p->wtype) {
      case ui_WidgetType_WIDGET_SLIDER:
        lv_slider_bind_value(obj, &entry->subject);
        break;
      case ui_WidgetType_WIDGET_ARC:
        lv_arc_bind_value(obj, &entry->subject);
        break;
      case ui_WidgetType_WIDGET_ROLLER:
        lv_roller_bind_value(obj, &entry->subject);
        break;
      case ui_WidgetType_WIDGET_DROPDOWN:
        /* SYNC C1: a value-bound dropdown resolves the enum NUMBER to the
               * option INDEX via its decoded option_values map; without a map
               * (no enum-value bind) fall back to the raw number-as-index. */
        if (find_dropdown_value_map(obj)) {
          lv_subject_add_observer_obj(&entry->subject,
                                      dropdown_value_observer_cb, obj, NULL);
        } else {
          lv_dropdown_bind_value(obj, &entry->subject);
        }
        break;
      case ui_WidgetType_WIDGET_BAR:
        /* lv_bar_bind_value does NOT exist — custom observer */
        lv_subject_add_observer_obj(&entry->subject, bar_value_observer_cb, obj,
                                    NULL);
        lv_bar_set_value(obj, lv_subject_get_int(&entry->subject), LV_ANIM_OFF);
        break;
      case ui_WidgetType_WIDGET_SPINBOX:
        /* lv_spinbox_bind_value does NOT exist — custom observer */
        lv_subject_add_observer_obj(&entry->subject, spinbox_value_observer_cb,
                                    obj, NULL);
        lv_spinbox_set_value(obj, lv_subject_get_int(&entry->subject));
        break;
      default:
        LOG_WARN("'value' binding not supported for widget type %d", p->wtype);
        break;
      }
    } else if (strcmp(key, "checked") == 0) {
      lv_obj_bind_checked(obj, &entry->subject);
    } else if (strcmp(key, "mode") == 0) {
      /* Host-proxy mode binding: the INT subject drives the mode
           * (the observer fires once at attach, so the subject's
           * initial value wins over props.mode). */
      if (p->wtype == ui_WidgetType_WIDGET_HOST_PROXY) {
        proxy_entry_t *proxy = find_proxy_by_obj(obj);
        if (proxy) {
          lv_subject_add_observer_obj(&entry->subject, proxy_mode_observer_cb,
                                      obj, proxy);
        } else {
          LOG_WARN("'mode' binding on an unregistered host proxy");
        }
      } else {
        LOG_WARN("'mode' binding only supported on host proxy "
                 "(widget type %d)",
                 p->wtype);
      }
    } else {
      LOG_WARN("unknown binding key '%s'", key);
    }
  }
}
/* ================================================================
 * Style property application
 * ================================================================ */
/* Fail-fast slot gate: a style value in the wrong oneof slot cannot render
 * the authored intent — reject the whole load instead of silently skipping
 * the property (closed-boundary doctrine; three silent-skip defects shipped
 * from one hand-authored screen before this gate existed). */
static bool slot_ok(const ui_StyleProperty *prop, pb_size_t want_tag) {
  if (prop->which_value == want_tag)
    return true;
  LOG_ERROR("style prop type %d: value in wrong oneof slot (got tag %u, want "
            "%u) — rejecting load",
            (int)prop->type, (unsigned)prop->which_value, (unsigned)want_tag);
  return false;
}
static bool apply_style_property(const ui_StyleProperty *prop,
                                 lv_style_t *style, const char *string_buf) {
  switch (prop->type) {
  /* ---- Background ---- */
  case ui_StylePropertyType_PROP_BG_COLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_bg_color(style, lv_color_make(prop->value.color_value.r,
                                                 prop->value.color_value.g,
                                                 prop->value.color_value.b));
      lv_style_set_bg_opa(style, LV_OPA_COVER);
    }
    break;
  case ui_StylePropertyType_PROP_BG_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_bg_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BG_GRAD_COLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_bg_grad_color(style,
                                 lv_color_make(prop->value.color_value.r,
                                               prop->value.color_value.g,
                                               prop->value.color_value.b));
    }
    break;
  case ui_StylePropertyType_PROP_BG_GRAD_DIR:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_bg_grad_dir(style, (lv_grad_dir_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BG_MAIN_STOP:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_bg_main_stop(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_BG_GRAD_STOP:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_bg_grad_stop(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_BG_MAIN_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_bg_main_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BG_GRAD_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_bg_grad_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BG_IMAGE_SRC:
    if (!slot_ok(prop, ui_StyleProperty_string_value_tag))
      return false;
    {
      const char *persistent = persist_bg_image_src(string_buf);
      if (persistent) {
        lv_style_set_bg_image_src(style, persistent);
      }
    }
    break;
  case ui_StylePropertyType_PROP_BG_IMAGE_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_bg_image_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BG_IMAGE_RECOLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_bg_image_recolor(style,
                                    lv_color_make(prop->value.color_value.r,
                                                  prop->value.color_value.g,
                                                  prop->value.color_value.b));
    }
    break;
  case ui_StylePropertyType_PROP_BG_IMAGE_RECOLOR_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_bg_image_recolor_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BG_IMAGE_TILED:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_bg_image_tiled(style, prop->value.uint_value != 0);
    }
    break;
  /* ---- Text ---- */
  case ui_StylePropertyType_PROP_TEXT_COLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_text_color(style, lv_color_make(prop->value.color_value.r,
                                                   prop->value.color_value.g,
                                                   prop->value.color_value.b));
    }
    break;
  case ui_StylePropertyType_PROP_TEXT_FONT:
    if (!slot_ok(prop, ui_StyleProperty_string_value_tag))
      return false;
    {
      lv_style_set_text_font(style, resolve_font(string_buf));
    }
    break;
  case ui_StylePropertyType_PROP_TEXT_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_text_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_TEXT_LETTER_SPACE:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_text_letter_space(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_TEXT_LINE_SPACE:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_text_line_space(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_TEXT_DECOR:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_text_decor(style, (lv_text_decor_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_TEXT_ALIGN:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_text_align(style, (lv_text_align_t)prop->value.uint_value);
    }
    break;
  /* ---- Border ---- */
  case ui_StylePropertyType_PROP_BORDER_COLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_border_color(style,
                                lv_color_make(prop->value.color_value.r,
                                              prop->value.color_value.g,
                                              prop->value.color_value.b));
    }
    break;
  case ui_StylePropertyType_PROP_BORDER_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_border_width(style, (int32_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BORDER_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_border_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BORDER_SIDE:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_border_side(style, (lv_border_side_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BORDER_POST:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_border_post(style, prop->value.uint_value != 0);
    }
    break;
  /* ---- Outline ---- */
  case ui_StylePropertyType_PROP_OUTLINE_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_outline_width(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_OUTLINE_COLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_outline_color(style,
                                 lv_color_make(prop->value.color_value.r,
                                               prop->value.color_value.g,
                                               prop->value.color_value.b));
    }
    break;
  case ui_StylePropertyType_PROP_OUTLINE_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_outline_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_OUTLINE_PAD:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_outline_pad(style, prop->value.int_value);
    }
    break;
  /* ---- Shadow (bundle) ---- */
  case ui_StylePropertyType_PROP_SHADOW:
    if (!slot_ok(prop, ui_StyleProperty_shadow_value_tag))
      return false;
    {
      lv_style_set_shadow_width(style, prop->value.shadow_value.width);
      lv_style_set_shadow_offset_x(style, prop->value.shadow_value.offset_x);
      lv_style_set_shadow_offset_y(style, prop->value.shadow_value.offset_y);
      lv_style_set_shadow_spread(style, prop->value.shadow_value.spread);
      lv_style_set_shadow_opa(style, prop->value.shadow_value.opa);
    }
    break;
  /* ---- Shadow (individual) ---- */
  case ui_StylePropertyType_PROP_SHADOW_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_shadow_width(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_SHADOW_OFFSET_X:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_shadow_offset_x(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_SHADOW_OFFSET_Y:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_shadow_offset_y(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_SHADOW_SPREAD:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_shadow_spread(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_SHADOW_COLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_shadow_color(style,
                                lv_color_make(prop->value.color_value.r,
                                              prop->value.color_value.g,
                                              prop->value.color_value.b));
    }
    break;
  case ui_StylePropertyType_PROP_SHADOW_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_shadow_opa(style, prop->value.uint_value);
    }
    break;
  /* ---- Radius ---- */
  case ui_StylePropertyType_PROP_RADIUS:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_radius(style, (int32_t)prop->value.uint_value);
    }
    break;
  /* ---- Size ---- */
  case ui_StylePropertyType_PROP_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_width(style, (int32_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_HEIGHT:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_height(style, (int32_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_MIN_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_min_width(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_MAX_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_max_width(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_MIN_HEIGHT:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_min_height(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_MAX_HEIGHT:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_max_height(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_LENGTH:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_length(style, prop->value.int_value);
    }
    break;
  /* ---- Position ---- */
  case ui_StylePropertyType_PROP_X:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_x(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_Y:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_y(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_ALIGN:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_align(style, (lv_align_t)prop->value.uint_value);
    }
    break;
  /* ---- Transform ---- */
  case ui_StylePropertyType_PROP_TRANSFORM_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_transform_width(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_TRANSFORM_HEIGHT:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_transform_height(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_TRANSLATE_X:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_translate_x(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_TRANSLATE_Y:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_translate_y(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_SCALE_X:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_transform_scale_x(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_SCALE_Y:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_transform_scale_y(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_ROTATION:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_transform_rotation(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_PIVOT_X:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_transform_pivot_x(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_PIVOT_Y:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_transform_pivot_y(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_SKEW_X:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_transform_skew_x(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_SKEW_Y:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_transform_skew_y(style, prop->value.int_value);
    }
    break;
  /* ---- Padding ---- */
  case ui_StylePropertyType_PROP_PAD_ALL:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_pad_all(style, (int32_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_PAD_GAP:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_pad_gap(style, (int32_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_PAD_HOR:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_pad_hor(style, (int32_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_PAD_VER:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_pad_ver(style, (int32_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_PAD_TOP:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_pad_top(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_PAD_BOTTOM:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_pad_bottom(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_PAD_LEFT:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_pad_left(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_PAD_RIGHT:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_pad_right(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_PAD_ROW:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_pad_row(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_PAD_COLUMN:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_pad_column(style, prop->value.int_value);
    }
    break;
  /* ---- Margin ---- */
  case ui_StylePropertyType_PROP_MARGIN_ALL:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_margin_top(style, (int32_t)prop->value.uint_value);
      lv_style_set_margin_bottom(style, (int32_t)prop->value.uint_value);
      lv_style_set_margin_left(style, (int32_t)prop->value.uint_value);
      lv_style_set_margin_right(style, (int32_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_MARGIN_TOP:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_margin_top(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_MARGIN_BOTTOM:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_margin_bottom(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_MARGIN_LEFT:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_margin_left(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_MARGIN_RIGHT:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_margin_right(style, prop->value.int_value);
    }
    break;
  /* ---- Image style ---- */
  case ui_StylePropertyType_PROP_IMAGE_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_image_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_IMAGE_RECOLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_image_recolor(style,
                                 lv_color_make(prop->value.color_value.r,
                                               prop->value.color_value.g,
                                               prop->value.color_value.b));
    }
    break;
  case ui_StylePropertyType_PROP_IMAGE_RECOLOR_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_image_recolor_opa(style, prop->value.uint_value);
    }
    break;
  /* ---- Line style ---- */
  case ui_StylePropertyType_PROP_LINE_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_line_width(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_LINE_DASH_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_line_dash_width(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_LINE_DASH_GAP:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_line_dash_gap(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_LINE_ROUNDED:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_line_rounded(style, prop->value.uint_value != 0);
    }
    break;
  case ui_StylePropertyType_PROP_LINE_COLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_line_color(style, lv_color_make(prop->value.color_value.r,
                                                   prop->value.color_value.g,
                                                   prop->value.color_value.b));
    }
    break;
  case ui_StylePropertyType_PROP_LINE_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_line_opa(style, prop->value.uint_value);
    }
    break;
  /* ---- Arc style ---- */
  case ui_StylePropertyType_PROP_ARC_WIDTH:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_arc_width(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_ARC_ROUNDED:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_arc_rounded(style, prop->value.uint_value != 0);
    }
    break;
  case ui_StylePropertyType_PROP_ARC_COLOR:
    if (!slot_ok(prop, ui_StyleProperty_color_value_tag))
      return false;
    {
      lv_style_set_arc_color(style, lv_color_make(prop->value.color_value.r,
                                                  prop->value.color_value.g,
                                                  prop->value.color_value.b));
    }
    break;
  case ui_StylePropertyType_PROP_ARC_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_arc_opa(style, prop->value.uint_value);
    }
    break;
  /* ---- Misc ---- */
  case ui_StylePropertyType_PROP_CLIP_CORNER:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_clip_corner(style, prop->value.uint_value != 0);
    }
    break;
  case ui_StylePropertyType_PROP_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_OPA_LAYERED:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_opa_layered(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_COLOR_FILTER_OPA:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_color_filter_opa(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_ANIM_DURATION:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_anim_duration(style, prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BLEND_MODE:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_blend_mode(style, (lv_blend_mode_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_BASE_DIR:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_base_dir(style, (lv_base_dir_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_ROTARY_SENSITIVITY:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_rotary_sensitivity(style, prop->value.uint_value);
    }
    break;
  /* ---- Flex ---- */
  case ui_StylePropertyType_PROP_FLEX_FLOW:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      uint32_t flow = prop->value.uint_value;
      if (flow > 0 && flow < FLEX_FLOW_LUT_SIZE) {
        lv_style_set_flex_flow(style, flex_flow_lut[flow]);
      }
    }
    break;
  case ui_StylePropertyType_PROP_FLEX_MAIN_PLACE:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_flex_main_place(style,
                                   (lv_flex_align_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_FLEX_CROSS_PLACE:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_flex_cross_place(style,
                                    (lv_flex_align_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_FLEX_TRACK_PLACE:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_flex_track_place(style,
                                    (lv_flex_align_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_FLEX_GROW:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_flex_grow(style, prop->value.uint_value);
    }
    break;
  /* ---- Grid cell ---- */
  case ui_StylePropertyType_PROP_GRID_CELL_COLUMN_POS:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_grid_cell_column_pos(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_GRID_CELL_COLUMN_SPAN:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_grid_cell_column_span(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_GRID_CELL_ROW_POS:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_grid_cell_row_pos(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_GRID_CELL_ROW_SPAN:
    if (!slot_ok(prop, ui_StyleProperty_int_value_tag))
      return false;
    {
      lv_style_set_grid_cell_row_span(style, prop->value.int_value);
    }
    break;
  case ui_StylePropertyType_PROP_GRID_CELL_X_ALIGN:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_grid_cell_x_align(style,
                                     (lv_grid_align_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_GRID_CELL_Y_ALIGN:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_grid_cell_y_align(style,
                                     (lv_grid_align_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_GRID_COLUMN_ALIGN:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_grid_column_align(style,
                                     (lv_grid_align_t)prop->value.uint_value);
    }
    break;
  case ui_StylePropertyType_PROP_GRID_ROW_ALIGN:
    if (!slot_ok(prop, ui_StyleProperty_uint_value_tag))
      return false;
    {
      lv_style_set_grid_row_align(style,
                                  (lv_grid_align_t)prop->value.uint_value);
    }
    break;
  default:
    LOG_WARN("unhandled style prop type %d — ignored", (int)prop->type);
    break;
  }
  return true;
}
/* ================================================================
 * Nested decode callbacks (innermost → outermost)
 * ================================================================ */
/* Callback: decode one StyleProperty within a StyleVariant. Properties
 * of a variant that is neither the base (index 0) nor the active
 * composite index are drained without allocating a pool style. */
static bool properties_decode_cb(pb_istream_t *stream, const pb_field_t *field,
                                 void **arg) {
  (void)field;
  style_variant_ctx_t *ctx = (style_variant_ctx_t *)*arg;
  ui_StyleProperty prop = ui_StyleProperty_init_zero;
  /* string_value is a fixed char[64] (max_size in .options) —
   * nanopb fills it automatically during decode, no callback needed. */
  if (!pb_decode(stream, ui_StyleProperty_fields, &prop))
    return false;
  uint32_t vidx = ctx->entry->variant_index;
  if (vidx != 0 && (int)vidx != get_composite_idx())
    return true; /* unwanted variant — drained, no pool allocation */
  if (!ctx->style) {
    ctx->style = alloc_style();
    if (!ctx->style)
      return false;
  }
  return apply_style_property(&prop, ctx->style, prop.value.string_value);
}
/* Attach a decoded group style to the widget under the group's state
 * selector and record it for the in-place style morph (tree patching). */
static void attach_group_style(style_group_ctx_t *ctx, lv_style_t *style) {
  lv_obj_add_style(ctx->obj, style, ctx->group->state_selector);
  if (ctx->wctx) {
    if (ctx->wctx->added_style_count < MAX_STYLES_PER_WIDGET) {
      added_style_t *rec =
          &ctx->wctx->added_styles[ctx->wctx->added_style_count++];
      rec->style = style;
      rec->selector = ctx->group->state_selector;
    } else {
      /* B6: past the per-widget cap the style still ATTACHES but is no
           * longer TRACKED, so a later style-morph UPDATE can only remove the
           * first 16 — the untracked surplus stacks and leaks. Fail the load
           * loud rather than ship a widget that morphs incorrectly. */
      LOG_ERROR("widget style count exceeds max %d — morph would leak",
                MAX_STYLES_PER_WIDGET);
      load_resource_error = true;
    }
  }
}
/* Callback: decode one sparse StyleVariant within a StyleGroup.
 * An entry matching the active composite index attaches immediately
 * (exact match); the base entry (index 0) is parked on the group ctx
 * and attaches at group end only when no exact match streamed. Other
 * entries were drained by properties_decode_cb. */
static bool variants_decode_cb(pb_istream_t *stream, const pb_field_t *field,
                               void **arg) {
  (void)field;
  style_group_ctx_t *ctx = (style_group_ctx_t *)*arg;
  ui_StyleVariant entry = ui_StyleVariant_init_zero;
  style_variant_ctx_t vctx;
  vctx.entry = &entry;
  vctx.style = NULL;
  entry.properties.funcs.decode = properties_decode_cb;
  entry.properties.arg = &vctx;
  if (!pb_decode(stream, ui_StyleVariant_fields, &entry))
    return false;
  if ((int)entry.variant_index == get_composite_idx()) {
    /* The complete prop set for the active composite (may be empty —
       * then there is nothing to attach, and the base must NOT apply). */
    ctx->exact_match = true;
    if (vctx.style)
      attach_group_style(ctx, vctx.style);
  } else if (entry.variant_index == 0) {
    ctx->base_style = vctx.style; /* NULL for an empty base prop set */
  }
  return true;
}
/* Callback: decode one StyleGroup and bind its variants to the widget */
static bool style_groups_decode_cb(pb_istream_t *stream,
                                   const pb_field_t *field, void **arg) {
  (void)field;
  widget_ctx_t *ctx = (widget_ctx_t *)*arg;
  lv_obj_t *obj = ensure_widget(ctx);
  if (!obj) {
    ctx->error = -1;
    return false;
  }
  ui_StyleGroup group = ui_StyleGroup_init_zero;
  style_group_ctx_t gctx;
  gctx.obj = obj;
  gctx.wctx = ctx;
  gctx.group = &group;
  gctx.base_style = NULL;
  gctx.exact_match = false;
  group.variants.funcs.decode = variants_decode_cb;
  group.variants.arg = &gctx;
  if (!pb_decode(stream, ui_StyleGroup_fields, &group))
    return false;
  if (gctx.exact_match) {
    /* An exact-match entry attached; a base style decoded before it
       * streamed is unused — release its prop allocations (the pool
       * slot itself is reclaimed only by a full reload, like all
       * group styles). */
    if (gctx.base_style)
      lv_style_reset(gctx.base_style);
  } else if (gctx.base_style) {
    /* No entry for the active composite index — it inherits the
       * base wholesale. */
    attach_group_style(&gctx, gctx.base_style);
  }
  return true;
}
/* Callback: decode one child WidgetNode and build it under the parent */
static bool children_decode_cb(pb_istream_t *stream, const pb_field_t *field,
                               void **arg) {
  (void)field;
  widget_ctx_t *parent_ctx = (widget_ctx_t *)*arg;
  /* Depth guard: a crafted deeply-nested tree must not recurse the C stack to
   * a crash. Refuse past the cap (real screens are shallow). */
  if (parent_ctx->depth >= MAX_DECODE_DEPTH) {
    LOG_ERROR("widget nesting exceeds MAX_DECODE_DEPTH (%d)", MAX_DECODE_DEPTH);
    parent_ctx->error = -1;
    return false;
  }
  lv_obj_t *parent = ensure_widget(parent_ctx);
  if (!parent) {
    parent_ctx->error = -1;
    return false;
  }
  /* Tabview children build under the hidden staging container (their tab
   * pages cannot exist yet — see the widget_ctx_t staging note). */
  if (parent_ctx->node->type == ui_WidgetType_WIDGET_TABVIEW) {
    if (!parent_ctx->tab_staging) {
      parent_ctx->tab_staging = lv_obj_create(parent_ctx->parent);
      if (parent_ctx->tab_staging) {
        /* Two calls: an OR'd value is not a member of
               * lv_obj_flag_t (analyzer EnumCastOutOfRange). */
        lv_obj_add_flag(parent_ctx->tab_staging, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(parent_ctx->tab_staging, LV_OBJ_FLAG_IGNORE_LAYOUT);
      }
    }
    if (!parent_ctx->tab_staging ||
        parent_ctx->tab_staged_count >= MAX_TABVIEW_CHILDREN) {
      LOG_ERROR("tabview staging unavailable or full (%d max)",
                MAX_TABVIEW_CHILDREN);
      parent_ctx->error = -1;
      return false;
    }
    parent = parent_ctx->tab_staging;
  }
  /* Fan-out guard: refuse before the child that would push this parent past
   * the uint16_t child_cnt bound. Checked against the LIVE count, so it holds
   * however the siblings arrived — one wide .pb, or a payload appending to a
   * parent that patches already grew. */
  if (lv_obj_get_child_count(parent) >= MAX_LIVE_CHILDREN) {
    LOG_ERROR("parent already holds %u children (max %d)",
              (unsigned)lv_obj_get_child_count(parent), MAX_LIVE_CHILDREN);
    parent_ctx->error = -1;
    return false;
  }
  ui_WidgetNode child = ui_WidgetNode_init_zero;
  widget_ctx_t child_ctx;
  child_ctx.parent = parent;
  child_ctx.self = NULL;
  child_ctx.node = &child;
  memset(child_ctx.text, 0, sizeof(child_ctx.text));
  child_ctx.error = 0;
  child_ctx.depth = parent_ctx->depth + 1;
  child_ctx.binding_count = 0;
  child_ctx.added_style_count = 0;
  child_ctx.bind_format_count = 0;
  child_ctx.tab_staging = NULL;
  child_ctx.tab_staged_count = 0;
  /* Wire up callbacks for the child's callback fields.
   * text is a fixed char[256] (max_size) — filled by nanopb automatically.
   * children, style_groups, bindings, bind_formats are pb_callback_t. */
  child.children.funcs.decode = children_decode_cb;
  child.children.arg = &child_ctx;
  child.style_groups.funcs.decode = style_groups_decode_cb;
  child.style_groups.arg = &child_ctx;
  child.bindings.funcs.decode = bindings_decode_cb;
  child.bindings.arg = &child_ctx;
  child.bind_formats.funcs.decode = bind_formats_decode_cb;
  child.bind_formats.arg = &child_ctx;
  if (!pb_decode(stream, ui_WidgetNode_fields, &child)) {
    parent_ctx->error = -1;
    return false;
  }
  /* Copy text from nanopb-filled char[256] to context for finalize_widget */
  strncpy(child_ctx.text, child.text, sizeof(child_ctx.text) - 1);
  child_ctx.text[sizeof(child_ctx.text) - 1] = '\0';
  finalize_widget(&child_ctx);
  /* Record the staged child's tab-bar slot flag (in_tab_bar is field 39 —
   * fully decoded by now), index-aligned with the staging child order. */
  if (parent_ctx->node->type == ui_WidgetType_WIDGET_TABVIEW) {
    parent_ctx->tab_in_bar[parent_ctx->tab_staged_count++] = child.in_tab_bar;
  }
  if (child_ctx.error) {
    parent_ctx->error = child_ctx.error;
  }
  /* Free the FT_POINTER submessages nanopb malloc'd for this child
   * (EventBinding.cmd, WidgetNode.gestures — R5a). finalize_widget has
   * already consumed whatever the build needs; on a pb_decode failure above
   * nanopb released them itself, so this only runs on the success path. */
  pb_release(ui_WidgetNode_fields, &child);
  /* LATCH and continue — deliberately NOT an abort.
   *
   * Aborting here (returning `child_ctx.error == 0`) was tried and reverted. It
   * looks like fail-fast, but `finalize_widget` latches ctx->error for a
   * DUPLICATE UID too (see register_uid's caller), and that case is contracted
   * to stay renderable: the collided node is left unidentified so no reconciler
   * can mis-target it, while the rest of the screen builds normally. Aborting
   * turned that into a TRUNCATED tree — every sibling after the collision
   * silently missing — which no test caught, because reload_cycle asserts uid
   * UNIQUENESS, not tree completeness.
   *
   * Making this an abort therefore requires first distinguishing a hard refusal
   * from a contained defect, which the bare -1 status cannot express. Until it
   * can, latching is the behavior the contract is written against. */
  return true;
}
/* ================================================================
 * Public API
 * ================================================================ */
int build_ui_from_proto_raw(const uint8_t *data, uint32_t len,
                            lv_obj_t *parent) {
  if (!data || len == 0 || !parent) {
    LOG_ERROR("invalid arguments: data=%p len=%u parent=%p", (const void *)data,
              (unsigned)len, (const void *)parent);
    return LOAD_ERR_ABORTED;
  }
  reset_style_pool();
  reset_subject_registry();
  ui_Screen screen = ui_Screen_init_zero;
  /* Bindings/visibility attach AFTER the decode (subjects stream after
   * the tree — see the pending queues); start each build empty. */
  pending_bindings_count = 0;
  pending_visibility_count = 0;
  pending_checked_count = 0;
  pending_enabled_count = 0;
  pending_color_count = 0;
  pending_event_subject_count = 0;
  pending_patch_subject_count = 0;
  pending_tabview_count = 0;
  grid_template_count = 0;
  scale_text_count = 0;
  bg_image_src_count = 0;
  btnmatrix_map_count = 0;
  line_point_count = 0;
  proxy_count = 0;
  uid_count = 0;
  dropdown_value_map_count = 0;
  load_resource_error = false;
  /* R5b: a full build starts from no gesture-surface cmd templates — the
   * gesture drain registers afresh from this build's gesture surfaces. */
  controls_gesture_specs_reset();
  /* Subject declarations (Screen field 2; registered during decode) */
  screen.subjects.funcs.decode = subjects_decode_cb;
  screen.subjects.arg = NULL;
  widget_ctx_t root_ctx;
  root_ctx.parent = parent;
  root_ctx.self = NULL;
  root_ctx.node = &screen.root;
  memset(root_ctx.text, 0, sizeof(root_ctx.text));
  root_ctx.error = 0;
  root_ctx.depth = 0;
  root_ctx.binding_count = 0;
  root_ctx.added_style_count = 0;
  root_ctx.bind_format_count = 0;
  root_ctx.tab_staging = NULL;
  root_ctx.tab_staged_count = 0;
  /* Set up callbacks on the root WidgetNode before decoding Screen.
   * text is a fixed char[256] (max_size) — filled by nanopb automatically.
   * children, style_groups, bindings, bind_formats are pb_callback_t. */
  screen.root.children.funcs.decode = children_decode_cb;
  screen.root.children.arg = &root_ctx;
  screen.root.style_groups.funcs.decode = style_groups_decode_cb;
  screen.root.style_groups.arg = &root_ctx;
  screen.root.bindings.funcs.decode = bindings_decode_cb;
  screen.root.bindings.arg = &root_ctx;
  screen.root.bind_formats.funcs.decode = bind_formats_decode_cb;
  screen.root.bind_formats.arg = &root_ctx;
  pb_istream_t stream = pb_istream_from_buffer(data, len);
  if (!pb_decode(&stream, ui_Screen_fields, &screen)) {
    /* A callback returned false (depth cap, fan-out cap, ensure_widget
     * failure) or the payload is malformed. Either way nanopb stopped
     * mid-stream, so the tree is TRUNCATED at the fault — this is the one
     * discriminator the caller needs, and it is free here: reaching any
     * later return means the decode ran to completion. */
    LOG_ERROR("UI AST decode failed: %s", PB_GET_ERROR(&stream));
    return LOAD_ERR_ABORTED;
  }
  if (!screen.has_root) {
    pb_release(ui_Screen_fields, &screen);
    return 0;
  }
  /* Copy text from nanopb-filled char[256] to context for finalize_widget */
  strncpy(root_ctx.text, screen.root.text, sizeof(root_ctx.text) - 1);
  root_ctx.text[sizeof(root_ctx.text) - 1] = '\0';
  finalize_widget(&root_ctx);
  /* Subjects are registered AND every node — the root included — is
   * finalized; flush the deferred attachments now. Children finalize
   * during streaming decode, but the ROOT finalizes only above, so a
   * flush placed before it would attach root-level bindings to a
   * half-initialized widget (no uid, no props, no styles). */
  for (int i = 0; i < pending_bindings_count; i++) {
    apply_bindings(&pending_bindings[i]);
  }
  for (int i = 0; i < pending_visibility_count; i++) {
    apply_visibility(pending_visibility[i].obj, &pending_visibility[i].vis);
  }
  for (int i = 0; i < pending_checked_count; i++) {
    apply_checked_when(pending_checked[i].obj, &pending_checked[i].bind);
  }
  for (int i = 0; i < pending_enabled_count; i++) {
    apply_enabled_when(pending_enabled[i].obj, &pending_enabled[i].bind);
  }
  for (int i = 0; i < pending_color_count; i++) {
    apply_color_when(pending_color[i].obj, &pending_color[i].bind);
  }
  for (int i = 0; i < pending_event_subject_count; i++) {
    apply_event_subject(&pending_event_subject[i]);
  }
  for (int i = 0; i < pending_patch_subject_count; i++) {
    apply_patch_subject(&pending_patch_subject[i]);
  }
  pending_bindings_count = 0;
  pending_visibility_count = 0;
  pending_checked_count = 0;
  pending_enabled_count = 0;
  pending_color_count = 0;
  pending_event_subject_count = 0;
  pending_patch_subject_count = 0;
  /* Every node (root included) is finalized and every style group is
   * attached — activate tabs against FINAL geometry (set_active calls
   * lv_obj_update_layout itself; see the pending_tabview queue note). */
  for (int i = 0; i < pending_tabview_count; i++) {
    lv_tabview_set_active(pending_tabview[i].tabview,
                          pending_tabview[i].active_index, LV_ANIM_OFF);
  }
  pending_tabview_count = 0;
  /* Free the root node's FT_POINTER submessages (EventBinding.cmd,
   * WidgetNode.gestures — R5a); finalize_widget + the pending flushes have
   * consumed everything the build needs. Child nodes were released in
   * children_decode_cb. */
  pb_release(ui_Screen_fields, &screen);
  /* A subject-registry overflow (arg-less decode, can't touch root_ctx) is a
   * load failure too — every binding to a dropped subject is a dead control.
   * load_resource_error carries the same signal from the deep no-ctx sites
   * (pool exhaustion / observer malloc / unresolved binding subject). */
  if (root_ctx.error == 0 && (subject_overflow || load_resource_error))
    return LOAD_ERR_DEFECTIVE;
  /* The decode completed (an abort would have returned above), so the tree is
   * COMPLETE. A latched error means finalize_widget degraded one or more NODES
   * — a duplicate uid, a proxy registry overflow, a malformed host_proxy — and
   * the screen must keep rendering. */
  return root_ctx.error == 0 ? 0 : LOAD_ERR_DEFECTIVE;
}
/* ================================================================
 * Tree-patch reconciler (controls_apply_patch)
 *
 * Decodes a ScreenPatch and applies its ops strictly sequentially
 * against the live tree. The differ (lvgl-codegen.patch) owns ALL
 * matching and morph-vs-replace policy; this side is a mechanical op
 * applier: UPDATE_PROPS re-enters the existing apply_* surface against
 * a preset live object, INSERT/REPLACE re-enter the existing streaming
 * builder (no second builder), MOVE is lv_obj_move_to_index, REMOVE is
 * a defocus-safe registry-coherent lv_obj_delete. Any failure aborts
 * the batch (the tree is then INDETERMINATE — the host must send a
 * full .pb before trusting the UI again).
 * ================================================================ */
/* Walk a doomed subtree: drop uid registrations and tombstone proxy
 * entries so the per-tick sweep never touches freed objects. */
static void unregister_subtree(lv_obj_t *obj) {
  /* user_data holds the node's uid (finalize_widget mirrors it). */
  void *uid_ud = lv_obj_get_user_data(obj);
  if (uid_ud != NULL) {
    /* If this node owns the gesture spec set, drop it before the uid
       * mapping goes (ITEM 7 — else the drain emits phantom cmds). No-op for
       * any non-owner uid, mirroring remove_proxy_entry below. */
    controls_gesture_specs_clear_owner((uint32_t)(uintptr_t)uid_ud);
    unregister_uid_obj(obj);
  }
  remove_proxy_entry(obj);
  unregister_dropdown_value_map(obj);
  uint32_t n = lv_obj_get_child_count(obj);
  for (uint32_t i = 0; i < n; i++)
    unregister_subtree(lv_obj_get_child(obj, i));
}
static void delete_patch_subtree(lv_obj_t *root) {
  /* Deleting the group-focused widget would unfreeze + auto-refocus a
   * sibling (lv_group_remove_obj) — the patch contract is 'leaves
   * nothing focused', matching full builds. */
  input_group_defocus_within(root);
  unregister_subtree(root);
  lv_obj_delete(root);
}
/* Every op that can change a container's content extent invalidates
 * the PARENT: LVGL invalidates the touched child's own area, but the
 * parent's AUTO scrollbar repaints only when the scrollbar's area is
 * itself invalidated — a morph that shrinks content would otherwise
 * leave a stale scrollbar strip on screen (full loads never hit this:
 * lv_obj_clean invalidates everything). Caught pixel-exactly by the
 * morph-parity dual oracle. */
static void invalidate_op_parent(lv_obj_t *parent) {
  if (parent)
    lv_obj_invalidate(parent);
}
/* Pool headroom guard: REPLACE/INSERT consume style/grid/string pool
 * slots reclaimed only by a full reload. Refuse the op LOUDLY before
 * exhaustion — the host's full reload resets the pools. */
#define PATCH_STYLE_HEADROOM 64
#define PATCH_GRID_HEADROOM 8
#define PATCH_UID_HEADROOM 32
#define PATCH_BINFONT_HEADROOM 2
static bool patch_pools_low(void) {
  bool low = false;
  if (style_pool_idx > MAX_STYLES - PATCH_STYLE_HEADROOM)
    low = true;
  if (grid_template_count > MAX_GRID_TEMPLATES - PATCH_GRID_HEADROOM)
    low = true;
  if (scale_text_count >= MAX_SCALE_TEXT_POOL)
    low = true;
  if (bg_image_src_count >= MAX_BG_IMAGE_SRCS)
    low = true;
  /* Line points are write-once like the scale-text pool: apply_line_points
   * runs on the UPDATE path as well as REPLACE, and the pool resets per LOAD
   * only, so every patch touching a line consumes a fresh slot and reclaims
   * none. Guarded here (the scale precedent) rather than left to the runtime
   * wall, so the op is refused BEFORE it mutates the tree.
   *
   * KNOW WHICH OPS THIS ACTUALLY REACHES. patch_pools_low has three callers:
   * INSERT and REPLACE consult it unconditionally, but the UPDATE_PROPS caller
   * sits inside `if (style_n > 0)`, so a STYLE-LESS UPDATE carrying line_props
   * never consults it and still hits the runtime wall in apply_line_points
   * (fail-loud, but only after the node is touched). That gap is narrow rather
   * than absent because :line_props is replace-on-change, so a conforming
   * differ sends every line change as REPLACE — which is guarded. A
   * non-conforming or crafted patch is what reaches the wall. */
  if (line_point_count >= MAX_LINE_POINT_POOL)
    low = true;
  /* A REPLACE/INSERT that adds nodes/fonts consumes uid-registry + binfont
   * slots reclaimed only by a full reload — refuse before exhaustion so the op
   * never half-registers a subtree the pool can't hold. */
  if (uid_count > MAX_UID_NODES - PATCH_UID_HEADROOM)
    low = true;
  if (loaded_binfont_count > MAX_BINFONTS - PATCH_BINFONT_HEADROOM)
    low = true;
  return low;
}
/* Pass-1 callback: count occurrences of a callback field and skip its
 * bytes (presence probe — no building). */
static bool count_skip_cb(pb_istream_t *stream, const pb_field_t *field,
                          void **arg) {
  (void)field;
  int *n = (int *)*arg;
  (*n)++;
  uint8_t skip;
  while (stream->bytes_left > 0) {
    if (!pb_read(stream, &skip, 1))
      return false;
  }
  return true;
}
/* Recursive pass-1 grid-demand probe: count grid containers (nodes carrying
 * BOTH col+row track templates — each costs two grid-template pool slots) in a
 * WidgetNode subtree. patch_pools_low's PATCH_GRID_HEADROOM is a FIXED margin
 * checked once at the op top, BEFORE the (arbitrarily large) subtree is
 * decoded, so a single REPLACE/INSERT whose subtree carries more grid
 * containers than the margin can blow straight through it and strand a
 * half-built subtree; this probe makes the check DEMAND-AWARE. Depth-guarded
 * exactly like children_decode_cb — a crafted deeply-nested payload must not
 * recurse the C stack to a crash. */
typedef struct {
  int grid_containers;
  int depth;
  bool bad; /* decode failure / depth cap → caller treats as "cannot verify" */
} grid_demand_ctx_t;
static bool grid_demand_cb(pb_istream_t *stream, const pb_field_t *field,
                           void **arg) {
  (void)field;
  grid_demand_ctx_t *parent = (grid_demand_ctx_t *)*arg;
  if (parent->depth >= MAX_DECODE_DEPTH) {
    LOG_ERROR("patch subtree nesting exceeds MAX_DECODE_DEPTH (%d)",
              MAX_DECODE_DEPTH);
    parent->bad = true;
    return false;
  }
  ui_WidgetNode node = ui_WidgetNode_init_zero;
  grid_demand_ctx_t child = {0, parent->depth + 1, false};
  node.children.funcs.decode = grid_demand_cb;
  node.children.arg = &child;
  /* style_groups/bindings/bind_formats stay unset — nanopb skips a callback
   * field whose decode fn is NULL (pb_close_string_substream drains the
   * bytes), and FT_POINTER submessages (event.cmd, gestures) are freed by the
   * pb_release below. */
  bool ok = pb_decode(stream, ui_WidgetNode_fields, &node);
  if (ok && node.grid_col_dsc_count > 0 && node.grid_row_dsc_count > 0)
    parent->grid_containers++;
  parent->grid_containers += child.grid_containers;
  if (!ok || child.bad)
    parent->bad = true;
  pb_release(ui_WidgetNode_fields, &node);
  return ok;
}
/* Total grid-template demand of a buffered TreePatchOp's node subtree, in
 * CONTAINERS (each = 2 pool slots). Returns -1 if the probe could not verify
 * (decode failure / depth cap) so the caller refuses the op rather than
 * decoding an unverifiable subtree. */
static int count_grid_demand(const uint8_t *buf, uint32_t len) {
  ui_TreePatchOp op = ui_TreePatchOp_init_zero;
  grid_demand_ctx_t ctx = {0, 0, false};
  op.node.children.funcs.decode = grid_demand_cb;
  op.node.children.arg = &ctx;
  pb_istream_t stream = pb_istream_from_buffer(buf, len);
  bool ok = pb_decode(&stream, ui_TreePatchOp_fields, &op);
  if (ok && op.has_node && op.node.grid_col_dsc_count > 0 &&
      op.node.grid_row_dsc_count > 0)
    ctx.grid_containers++;
  bool bad = !ok || ctx.bad;
  int total = ctx.grid_containers;
  pb_release(ui_TreePatchOp_fields, &op);
  return bad ? -1 : total;
}
/* Refuse a REPLACE/INSERT whose subtree's grid demand would outrun the pool,
 * BEFORE any delete/build. Returns true (op refused) after logging; the caller
 * returns PATCH_ERR_POOL (-4). */
static bool grid_demand_exceeds_pool(const uint8_t *buf, uint32_t len,
                                     const char *op_name) {
  int gd = count_grid_demand(buf, len);
  if (gd < 0 || grid_template_count + 2 * gd > MAX_GRID_TEMPLATES) {
    LOG_ERROR("%s refused: grid demand %d container(s) (%d slots) exceeds pool "
              "(count %d / max %d)",
              op_name, gd, 2 * gd, grid_template_count, MAX_GRID_TEMPLATES);
    return true;
  }
  return false;
}
/* Pass 2 for ops carrying a WidgetNode payload: decode the buffered op
 * again with the node's callbacks wired into the EXISTING streaming
 * builder. `morph_self` preset makes ensure_widget return the live
 * object (UPDATE); NULL builds fresh under `parent` (INSERT/REPLACE).
 * Returns 0 ok / negative patch error; *out gets the (re)built object. */
static int decode_op_node(const uint8_t *buf, uint32_t len, lv_obj_t *parent,
                          lv_obj_t *morph_self, lv_obj_t **out) {
  ui_TreePatchOp op = ui_TreePatchOp_init_zero;
  widget_ctx_t ctx;
  ctx.parent = parent;
  ctx.self = morph_self;
  ctx.node = &op.node;
  memset(ctx.text, 0, sizeof(ctx.text));
  ctx.error = 0;
  ctx.depth = 0; /* op-node is a subtree root; its children recurse from here */
  ctx.binding_count = 0;
  ctx.added_style_count = 0;
  ctx.bind_format_count = 0;
  ctx.tab_staging = NULL;
  ctx.tab_staged_count = 0;
  op.node.children.funcs.decode = children_decode_cb;
  op.node.children.arg = &ctx;
  op.node.style_groups.funcs.decode = style_groups_decode_cb;
  op.node.style_groups.arg = &ctx;
  op.node.bindings.funcs.decode = bindings_decode_cb;
  op.node.bindings.arg = &ctx;
  op.node.bind_formats.funcs.decode = bind_formats_decode_cb;
  op.node.bind_formats.arg = &ctx;
  pb_istream_t stream = pb_istream_from_buffer(buf, len);
  if (!pb_decode(&stream, ui_TreePatchOp_fields, &op)) {
    LOG_ERROR("patch op node decode failed: %s", PB_GET_ERROR(&stream));
    return -1; /* PATCH_ERR_DECODE */
  }
  int rc = 0;
  if (!op.has_node) {
    LOG_ERROR("patch op kind %d requires a node payload", (int)op.kind);
    rc = -5; /* PATCH_ERR_OP */
  } else {
    strncpy(ctx.text, op.node.text, sizeof(ctx.text) - 1);
    ctx.text[sizeof(ctx.text) - 1] = '\0';
    morph_in_progress = morph_self != NULL;
    finalize_widget(&ctx);
    /* Layout is otherwise applied only at create (ensure_widget) or after
       * a bare strip — a morphed node re-applies it from the op payload. */
    if (morph_self && !ctx.error)
      apply_node_layout(morph_self, &op.node);
    morph_in_progress = false;
    if (ctx.error)
      rc = -5; /* PATCH_ERR_OP */
    else if (out)
      *out = ctx.self;
  }
  /* Free the FT_POINTER submessages (EventBinding.cmd, WidgetNode.gestures —
   * R5a) nanopb malloc'd for op.node; finalize_widget consumed what the build
   * needs, and child nodes were released in children_decode_cb. The decode
   * failure above is auto-released by nanopb. */
  pb_release(ui_TreePatchOp_fields, &op);
  return rc;
}
/* Apply ONE buffered TreePatchOp. Returns 0 ok / negative error code
 * (see renderer.h PATCH_ERR_*). */
static int apply_one_op(const uint8_t *buf, uint32_t len) {
  /* Pass 1: scalar fields + presence probes; no building. */
  ui_TreePatchOp op = ui_TreePatchOp_init_zero;
  int style_n = 0;
  int child_n = 0;
  int binding_n = 0;
  op.node.children.funcs.decode = count_skip_cb;
  op.node.children.arg = &child_n;
  op.node.style_groups.funcs.decode = count_skip_cb;
  op.node.style_groups.arg = &style_n;
  op.node.bindings.funcs.decode = count_skip_cb;
  op.node.bindings.arg = &binding_n;
  op.node.bind_formats.funcs.decode = count_skip_cb;
  op.node.bind_formats.arg = &binding_n;
  pb_istream_t stream = pb_istream_from_buffer(buf, len);
  if (!pb_decode(&stream, ui_TreePatchOp_fields, &op)) {
    LOG_ERROR("patch op decode failed: %s", PB_GET_ERROR(&stream));
    return -1; /* PATCH_ERR_DECODE */
  }
  /* Pass 1 probes only scalars + counts; free the FT_POINTER submessages
   * (EventBinding.cmd, WidgetNode.gestures — R5a) nanopb malloc'd for op.node
   * so the per-op-kind early returns below never leak. The build pass
   * (decode_op_node) re-decodes and releases its own copy. */
  pb_release(ui_TreePatchOp_fields, &op);
  switch (op.kind) {
  case ui_PatchOpKind_PATCH_OP_REMOVE_NODE: {
    lv_obj_t *target = find_uid_obj(op.target_uid);
    if (!target) {
      LOG_ERROR("REMOVE: unknown uid %u", (unsigned)op.target_uid);
      return -3; /* PATCH_ERR_UNKNOWN_UID */
    }
    lv_obj_t *parent = lv_obj_get_parent(target);
    delete_patch_subtree(target);
    invalidate_op_parent(parent);
    return 0;
  }
  case ui_PatchOpKind_PATCH_OP_MOVE_NODE: {
    lv_obj_t *target = find_uid_obj(op.target_uid);
    if (!target) {
      LOG_ERROR("MOVE: unknown uid %u", (unsigned)op.target_uid);
      return -3;
    }
    if (op.parent_uid != 0) {
      lv_obj_t *parent = find_uid_obj(op.parent_uid);
      if (!parent || parent != lv_obj_get_parent(target)) {
        LOG_ERROR("MOVE: uid %u is not a child of parent uid %u",
                  (unsigned)op.target_uid, (unsigned)op.parent_uid);
        return -5;
      }
    }
    lv_obj_move_to_index(target, (int32_t)op.index);
    invalidate_op_parent(lv_obj_get_parent(target));
    return 0;
  }
  case ui_PatchOpKind_PATCH_OP_INSERT_NODE: {
    if (!op.has_node) {
      LOG_ERROR("INSERT: missing node payload");
      return -5;
    }
    if (patch_pools_low()) {
      LOG_ERROR("INSERT refused: pool headroom low (full reload "
                "resets the pools)");
      return -4; /* PATCH_ERR_POOL */
    }
    /* Demand-aware grid check: patch_pools_low's PATCH_GRID_HEADROOM is a
     * fixed margin, not this subtree's actual grid demand. Refuse before
     * building if the incoming node's grid containers would outrun the pool. */
    if (grid_demand_exceeds_pool(buf, len, "INSERT"))
      return -4;
    lv_obj_t *parent = find_uid_obj(op.parent_uid);
    if (!parent) {
      LOG_ERROR("INSERT: unknown parent uid %u", (unsigned)op.parent_uid);
      return -3;
    }
    /* INSERT is the ONE op that grows a parent's child count (REPLACE deletes
     * first, MOVE reorders, REMOVE shrinks, UPDATE forbids children), so it is
     * the one that can walk a parent to the uint16_t child_cnt wrap across
     * many patches. children_decode_cb bounds the node's own subtree; this
     * bounds the node's ATTACHMENT. A uid-less leaf registers in no pool, so
     * patch_pools_low cannot see this class at all. */
    if (lv_obj_get_child_count(parent) >= MAX_LIVE_CHILDREN) {
      LOG_ERROR("INSERT refused: parent uid %u already holds %u children "
                "(max %d)",
                (unsigned)op.parent_uid,
                (unsigned)lv_obj_get_child_count(parent), MAX_LIVE_CHILDREN);
      return -4; /* PATCH_ERR_POOL */
    }
    lv_obj_t *built = NULL;
    int rc = decode_op_node(buf, len, parent, NULL, &built);
    if (rc != 0)
      return rc;
    lv_obj_move_to_index(built, (int32_t)op.index);
    invalidate_op_parent(parent);
    return 0;
  }
  case ui_PatchOpKind_PATCH_OP_REPLACE_NODE: {
    if (!op.has_node) {
      LOG_ERROR("REPLACE: missing node payload");
      return -5;
    }
    if (patch_pools_low()) {
      LOG_ERROR("REPLACE refused: pool headroom low (full reload "
                "resets the pools)");
      return -4;
    }
    /* Demand-aware grid check BEFORE the delete: REPLACE tears the old subtree
     * down first and unconditionally, so a grid-exhaustion mid-build would
     * leave a half-built subtree with the old one already gone. Refuse here,
     * pre-patch state UNCHANGED, if the incoming subtree's grid demand would
     * outrun the pool. */
    if (grid_demand_exceeds_pool(buf, len, "REPLACE"))
      return -4;
    lv_obj_t *target = find_uid_obj(op.target_uid);
    if (!target) {
      LOG_ERROR("REPLACE: unknown uid %u", (unsigned)op.target_uid);
      return -3;
    }
    lv_obj_t *parent = lv_obj_get_parent(target);
    int32_t idx = lv_obj_get_index(target);
    /* Old subtree FIRST (its uids re-register from the new build). */
    delete_patch_subtree(target);
    lv_obj_t *built = NULL;
    int rc = decode_op_node(buf, len, parent, NULL, &built);
    if (rc != 0)
      return rc;
    lv_obj_move_to_index(built, idx);
    invalidate_op_parent(parent);
    return 0;
  }
  case ui_PatchOpKind_PATCH_OP_UPDATE_PROPS: {
    if (!op.has_node || child_n > 0) {
      LOG_ERROR("UPDATE: %s", op.has_node ? "non-empty children in payload"
                                          : "missing node payload");
      return -5;
    }
    if (op.node.type == ui_WidgetType_WIDGET_TABVIEW ||
        op.node.type == ui_WidgetType_WIDGET_HOST_PROXY) {
      LOG_ERROR("UPDATE: type %d is replace-only", (int)op.node.type);
      return -5;
    }
    /* The differ contract forbids event-carrying UPDATEs (`:event` is a
         * morph-invariant: an event change forces REPLACE, and unchanged
         * events are stripped from UPDATE payloads). Applying one would
         * lv_obj_add_event_cb AGAIN on the live object — a second callback,
         * so one click emits two host_commands. Reject, don't re-attach. */
    if (op.node.has_event) {
      LOG_ERROR("UPDATE: event-carrying payload (uid %u) — replace-only",
                (unsigned)op.target_uid);
      return -5;
    }
    /* Grid track templates are write-once pool slots — a grid change forces
     * REPLACE (the differ's replace-on-change-keys), never UPDATE. A payload
     * carrying grid dsc on an UPDATE would re-key the pool against a live
     * object; reject it (replace-only), mirroring the has_event guard above.
     * Defense-in-depth against a generator bug or a crafted payload (the host
     * is untrusted) — the renderer does not rely on generator discipline
     * alone. */
    if (op.node.grid_col_dsc_count > 0 || op.node.grid_row_dsc_count > 0) {
      LOG_ERROR("UPDATE: grid-template payload (uid %u) — replace-only",
                (unsigned)op.target_uid);
      return -5;
    }
    uid_entry_t *entry = find_uid_entry(op.target_uid);
    if (!entry) {
      LOG_ERROR("UPDATE: unknown uid %u", (unsigned)op.target_uid);
      return -3;
    }
    /* Widget-class check: the UPDATE's claimed type MUST match the type the
         * node was registered with. A mismatch means the patch addresses the
         * wrong widget class — decoding its props onto the live widget corrupts
         * the tree — so reject before touching it. */
    if (entry->widget_type != (int32_t)op.node.type) {
      LOG_ERROR("UPDATE: uid %u type mismatch (registered %d, patch %d)",
                (unsigned)op.target_uid, (int)entry->widget_type,
                (int)op.node.type);
      return -5;
    }
    lv_obj_t *target = entry->obj;
    if (style_n > 0) {
      /* Style morph: drop exactly the styles this node's builds
             * recorded, then the pass-2 decode attaches the new groups
             * (fresh pool slots; old slots wait for full reload). */
      if (patch_pools_low()) {
        LOG_ERROR("UPDATE style morph refused: pool headroom low");
        return -4;
      }
      for (int i = 0; i < entry->style_count; i++)
        lv_obj_remove_style(target, entry->styles[i].style,
                            entry->styles[i].selector);
      entry->style_count = 0;
    }
    int rc = decode_op_node(buf, len, lv_obj_get_parent(target), target, NULL);
    if (rc == 0)
      /* A morphed prop set can resize the node — the parent's
           * overflow/scrollbar state may flip with it. */
      invalidate_op_parent(lv_obj_get_parent(target));
    return rc;
  }
  default:
    LOG_ERROR("unknown patch op kind %d", (int)op.kind);
    return -5;
  }
}
typedef struct {
  const ui_ScreenPatch *patch; /* scalar fields decode before ops */
  uint32_t expected_base_hash;
  bool hash_checked;
  int rc;
  int op_index;
} patch_decode_ctx_t;
static bool patch_ops_decode_cb(pb_istream_t *stream, const pb_field_t *field,
                                void **arg) {
  (void)field;
  patch_decode_ctx_t *ctx = (patch_decode_ctx_t *)*arg;
  if (!ctx->hash_checked) {
    ctx->hash_checked = true;
    if (ctx->patch->base_hash != ctx->expected_base_hash) {
      LOG_ERROR("patch base_hash %u != loaded state %u — refusing",
                (unsigned)ctx->patch->base_hash,
                (unsigned)ctx->expected_base_hash);
      ctx->rc = -2; /* PATCH_ERR_BASE_HASH */
      return false;
    }
  }
  uint32_t len = (uint32_t)stream->bytes_left;
  uint8_t *buf = malloc(len);
  if (!buf) {
    LOG_ERROR("patch op buffer alloc failed (%u bytes)", (unsigned)len);
    ctx->rc = -1;
    return false;
  }
  if (!pb_read(stream, buf, len)) {
    free(buf);
    ctx->rc = -1;
    return false;
  }
  int rc = apply_one_op(buf, len);
  free(buf);
  if (rc != 0) {
    LOG_ERROR("patch aborted at op %d (rc %d) — tree is INDETERMINATE; "
              "send a full .pb",
              ctx->op_index, rc);
    ctx->rc = rc;
    return false;
  }
  ctx->op_index++;
  return true;
}
int apply_patch_from_proto_raw(const uint8_t *data, uint32_t len,
                               uint32_t expected_base_hash,
                               uint32_t *out_target_hash) {
  if (!data || len == 0) {
    LOG_ERROR("invalid patch arguments: data=%p len=%u", (const void *)data,
              (unsigned)len);
    return -1;
  }
  /* The patch re-enters the SAME deep load sites (finalize_widget +
   * the batch-end binding drain) that latch load_resource_error, so reset it
   * here too and check it at batch end — else a patch could silently degrade
   * exactly as a full load once did. */
  load_resource_error = false;
  ui_ScreenPatch patch = ui_ScreenPatch_init_zero;
  patch_decode_ctx_t ctx;
  ctx.patch = &patch;
  ctx.expected_base_hash = expected_base_hash;
  ctx.hash_checked = false;
  ctx.rc = 0;
  ctx.op_index = 0;
  patch.ops.funcs.decode = patch_ops_decode_cb;
  patch.ops.arg = &ctx;
  /* Ops queue bindings/visibility/checked/event-subject/tabview-activation
   * exactly like a full decode; start the queues empty and drain at batch end
   * (subjects are already live during a patch — never reset here). */
  pending_bindings_count = 0;
  pending_visibility_count = 0;
  pending_checked_count = 0;
  pending_enabled_count = 0;
  pending_color_count = 0;
  pending_event_subject_count = 0;
  pending_patch_subject_count = 0;
  pending_tabview_count = 0;
  pb_istream_t stream = pb_istream_from_buffer(data, len);
  if (!pb_decode(&stream, ui_ScreenPatch_fields, &patch)) {
    if (ctx.rc != 0)
      return ctx.rc; /* an op failed — already logged with context */
    LOG_ERROR("ScreenPatch decode failed: %s", PB_GET_ERROR(&stream));
    return -1;
  }
  /* Zero-op patch: the differ never emits one, but verify the hash
   * contract anyway (the ops callback is the usual check site). */
  if (!ctx.hash_checked && patch.base_hash != expected_base_hash) {
    LOG_ERROR("patch base_hash %u != loaded state %u — refusing",
              (unsigned)patch.base_hash, (unsigned)expected_base_hash);
    return -2;
  }
  /* Batch end: drain the deferred attachments (the full-decode flush). */
  for (int i = 0; i < pending_bindings_count; i++)
    apply_bindings(&pending_bindings[i]);
  for (int i = 0; i < pending_visibility_count; i++)
    apply_visibility(pending_visibility[i].obj, &pending_visibility[i].vis);
  for (int i = 0; i < pending_checked_count; i++)
    apply_checked_when(pending_checked[i].obj, &pending_checked[i].bind);
  for (int i = 0; i < pending_enabled_count; i++)
    apply_enabled_when(pending_enabled[i].obj, &pending_enabled[i].bind);
  for (int i = 0; i < pending_color_count; i++)
    apply_color_when(pending_color[i].obj, &pending_color[i].bind);
  for (int i = 0; i < pending_event_subject_count; i++)
    apply_event_subject(&pending_event_subject[i]);
  for (int i = 0; i < pending_patch_subject_count; i++)
    apply_patch_subject(&pending_patch_subject[i]);
  pending_bindings_count = 0;
  pending_visibility_count = 0;
  pending_checked_count = 0;
  pending_enabled_count = 0;
  pending_color_count = 0;
  pending_event_subject_count = 0;
  pending_patch_subject_count = 0;
  for (int i = 0; i < pending_tabview_count; i++) {
    lv_tabview_set_active(pending_tabview[i].tabview,
                          pending_tabview[i].active_index, LV_ANIM_OFF);
  }
  pending_tabview_count = 0;
  /* A deep load site (pool exhaustion / observer malloc / a binding to an
   * undeclared subject) latched during this patch's build or drain — the patch
   * applied but a resource could not be honored. Fail loud (PATCH_ERR_OP): the
   * caller marks the tree indeterminate and the host resends the full .pb. */
  if (load_resource_error) {
    LOG_ERROR("patch hit a load-resource failure — refusing (send full .pb)");
    return -5; /* PATCH_ERR_OP */
  }
  if (out_target_hash)
    *out_target_hash = patch.target_hash;
  return 0;
}
/* ================================================================
 * State update: decode StateUpdate and update subjects
 * ================================================================ */
static bool state_values_decode_cb(pb_istream_t *stream,
                                   const pb_field_t *field, void **arg) {
  (void)field;
  (void)arg;
  ui_SubjectValue sv = ui_SubjectValue_init_zero;
  if (!pb_decode(stream, ui_SubjectValue_fields, &sv))
    return false;
  subject_entry_t *entry = find_subject(sv.name);
  if (!entry)
    return true; /* unknown subject — skip silently */
  if (sv.which_value == ui_SubjectValue_int_value_tag && entry->type == 0) {
    /* Guard: only notify if value changed */
    if (lv_subject_get_int(&entry->subject) != sv.value.int_value) {
      lv_subject_set_int(&entry->subject, sv.value.int_value);
    }
  } else if (sv.which_value == ui_SubjectValue_string_value_tag &&
             entry->type == 1) {
    lv_subject_copy_string(&entry->subject, sv.value.string_value);
  } else {
    /* B8: the subject EXISTS but the update's value type does not match its
       * declared type — a producer/contract bug the best-effort stream would
       * otherwise swallow. WARN only (never fail: the state stream must
       * survive); an UNKNOWN subject stays silent (skipped above). Fires only
       * on a genuine type mismatch, so no cost on the correct-producer path. */
    LOG_WARN("state update for '%s': value type does not match subject type",
             sv.name);
  }
  return true;
}
int update_state_from_proto(const uint8_t *data, uint32_t len) {
  ui_StateUpdate update = ui_StateUpdate_init_zero;
  update.values.funcs.decode = state_values_decode_cb;
  update.values.arg = NULL;
  pb_istream_t stream = pb_istream_from_buffer(data, len);
  if (!pb_decode(&stream, ui_StateUpdate_fields, &update)) {
    LOG_ERROR("StateUpdate decode failed: %s", PB_GET_ERROR(&stream));
    return -1;
  }
  return 0;
}
#else
/* !HAS_NANOPB */
void proxy_report_sweep(void) { /* No proto decode — no proxies can exist. */ }
int build_ui_from_proto_raw(const uint8_t *data, uint32_t len,
                            lv_obj_t *parent) {
  reset_style_pool();
  (void)data;
  (void)len;
  lv_obj_t *label = lv_label_create(parent);
  lv_label_set_text(label, "UI AST: nanopb not linked");
  lv_obj_set_style_text_color(label, lv_color_hex(0xFF0000), LV_PART_MAIN);
  return 0;
}
int update_state_from_proto(const uint8_t *data, uint32_t len) {
  (void)data;
  (void)len;
  return 0;
}
int apply_patch_from_proto_raw(const uint8_t *data, uint32_t len,
                               uint32_t expected_base_hash,
                               uint32_t *out_target_hash) {
  (void)data;
  (void)len;
  (void)expected_base_hash;
  (void)out_target_hash;
  return -1; /* no proto decode — patches cannot apply */
}
#endif
/* HAS_NANOPB */
void renderer_cleanup(void) {
  reset_subject_registry();
  reset_style_pool();
  /* Free dynamically loaded binary fonts */
  for (int i = 0; i < loaded_binfont_count; i++) {
    lv_binfont_destroy(loaded_binfonts[i].font);
    loaded_binfonts[i].font = NULL;
    loaded_binfonts[i].name[0] = '\0';
  }
  loaded_binfont_count = 0;
}
