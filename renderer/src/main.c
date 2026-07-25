/**
 * LVGL Controls WASM Module — Generic Protobuf-Driven Renderer
 *
 * This WASM module provides a generic LVGL UI renderer. UI definitions
 * arrive as protobuf AST data at runtime via controls_load_ui(). The
 * module decodes the AST with nanopb and builds the LVGL widget tree.
 *
 * WASM exports:
 *   _initialize()                  — WASI reactor init
 *   controls_init(w, h) -> i32     — Init LVGL display at w×h
 *   controls_load_ui(ptr, len)     — Push protobuf UI AST (the tree is NOT laid
 *                                    out until the next refresh — see below)
 *   controls_apply_patch(ptr,len)  — Apply a ScreenPatch (partial tree
 *                                    update); nonzero = host full-reloads
 *   controls_update_state(ptr,len) — Push protobuf state
 *   controls_host_message(ptr,len) — Decode a ui.HostToWasm (a W3C pointer
 *                                    event or a lifecycle event); owns the
 *                                    bounded pointer table + capture-on-claim
 *                                    hit-test routing (LVGL widget vs the
 *                                    gesture FSM). Negative = decode/validate
 *                                    reject; >0 = a benign no-op class.
 *   controls_key_event(key, p)     — Enqueue a keypad key (lv_key_t or
 *                                    Unicode codepoint; p=1 press, 0 release)
 *   controls_text_input(ptr, len)  — Insert UTF-8 text into the focused
 *                                    textarea (paste path)
 *   controls_get_focused_text()    — NUL-terminated text of the focused
 *                                    textarea, or NULL (copy path)
 *   controls_tick(ms) -> i32       — Advance LVGL, returns 1 if changed
 *   controls_get_framebuffer()     — Get RGBA framebuffer pointer
 *   controls_abi_version() -> u32  — ABI/layout contract version (host gate)
 *   controls_fb_format() -> u32    — Framebuffer pixel format id (1=RGBA8888)
 *   controls_fb_width() -> u32     — Live framebuffer width  (px)
 *   controls_fb_height() -> u32    — Live framebuffer height (px)
 *   controls_fb_bpp() -> u32       — Framebuffer bytes per pixel (4)
 *   controls_set_breakpoint(bp)    — Set responsive breakpoint tier
 *   controls_set_theme_dark(dark)  — Set light/dark theme
 *   controls_set_theme_family(f)   — Set theme family (0..2); may return
 *                                    needs-full-reload (ABI v3)
 *   controls_set_dpi(dpi)          — Set display DPI
 *   controls_resize(w, h) -> i32  — Resize display + buffers
 *   controls_get_dirty_rect()     — Read+reset dirty rect (1=dirty, 0=clean)
 *   controls_get_dirty_rect_ptr() — Pointer to dirty rect bounds [x1,y1,x2,y2]
 *   controls_dump_tree() -> ptr    — JSON widget tree (type+coords+uid+
 *                                    label text+hidden flag+children)
 *   controls_destroy() -> i32      — Cleanup
 */
#include "commands.h"
#include "gesture.h"
#include "host_imports.h"
#include "log.h"
#include "lvgl.h"
#include "lvgl/src/core/lv_group_private.h"
/* obj_focus (defocus path) */
#include "lvgl/src/core/lv_obj_class_private.h"
/* lv_obj_class_t.name */
#include "lvgl/src/widgets/label/lv_label_private.h"
/* lv_label_t.dot_begin */
#include "renderer.h"
#include "svg_decoder.h"
#include "theme.h"
#include "theme_tokens.h"
#include "ui_input.pb.h"
/* ui_HostToWasm decode + ui_WasmToHost encode */
#include <pb_decode.h>
#include <pb_encode.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
/* RGBA framebuffer (host reads via controls_get_framebuffer) */
static uint8_t *framebuffer;
static uint32_t fb_width, fb_height;
static volatile int flush_happened;
/* ── ABI self-description (host↔guest dims/format handshake) ────────────────
 * The host reads `framebuffer` straight out of WASM linear memory at a stride
 * (fb_width*fb_height*4) and channel order it must AGREE on. Because the module
 * hot-reloads independently of the host, a rebuilt module with different
 * dims/format would be read at the wrong stride — silent pixel corruption. The
 * controls_abi_version/fb_* getters below let the host validate the contract at
 * module load/reload and fail loud instead. Bump CONTROLS_ABI_VERSION on any
 * change to the framebuffer layout contract OR the required host-import set
 * (a new env.* import is instantiation-MANDATORY — a host that has not linked
 * it cannot load the module at all, so hosts gain the import FIRST and gate on
 * this version to know it is consumed).
 *   v1 — initial fb-layout contract (RGBA8888 + dims getters).
 *   v2 — env.host_event joined the required import set (the named-event
 *        envelope lane — see host_imports.h). */
/* v3: adds the controls_set_theme_family export (an optional capability
 * signal consumers may gate on — the framebuffer contract is unchanged;
 * the bump versions the EXPORT SURFACE so a host can require the
 * theme-family control without probing). */
#define CONTROLS_ABI_VERSION 3u
/* Framebuffer pixel format id — memory BYTE order (framebuffer[i*4+0] is the
 * first listed channel); 0 reserved invalid so an uninitialized module is
 * rejected. Stable integers (Vulkan/wgpu style). The renderer emits RGBA8888:
 * flush_cb swizzles LVGL's ARGB8888 to RGBA for wgpu Rgba8Unorm (see flush_cb).
 */
#define CONTROLS_FB_FMT_UNKNOWN 0u
#define CONTROLS_FB_FMT_RGBA8888 1u
/* ALPHA CONTRACT: the framebuffer is straight (NON-premultiplied) RGBA and
 * color channels are DEFINED ONLY WHERE a > 0. LVGL's sw blend legally
 * leaves arbitrary color bytes under a == 0 pixels (measured around every
 * glyph's AA edge, 2026-07-19). Consumers MUST alpha-blend or flatten over
 * their backdrop; a raw alpha-ignoring copy renders that noise as visible
 * garbage (an in-fleet consumer's live-UI "tofu" — a bug that was first
 * misattributed to bare-root glyph resolution; the boundary_contracts
 * harness test pins the renderer's correctness). */
/* LVGL draw buffers (double buffered) */
static uint8_t *draw_buf1;
static uint8_t *draw_buf2;
/* Dirty rect accumulation (union of all flush areas per tick) */
static int32_t dirty_x1, dirty_y1, dirty_x2, dirty_y2;
static int dirty_valid;
static int32_t dirty_rect_out[4]; /* x1, y1, x2, y2 — read by host */
/* Pointer input state (polled by LVGL each tick). Populated by the
 * LVGL-WIDGET-owned branch of the pointer pipeline (controls_host_message);
 * indev_read_cb reads these globals on the next tick. The point deliberately
 * KEEPS its last value when no pointer is live: an off-screen "parked"
 * sentinel would be re-read every poll and trip LVGL's coordinate sanity
 * warnings forever. Phantom-hover cleanup is explicit instead — see
 * clear_press_hover. */
static int32_t pointer_x, pointer_y, pointer_pressed;
/* ── Bounded pointer table + gesture FSM (controls_host_message) ────────────
 * The shared controls.wasm OWNS gesture recognition: the host forwards raw W3C
 * pointer events; the WASM keeps a bounded pointer table, hit-tests on DOWN to
 * latch ownership (capture-on-claim, §4), and routes per owner — LVGL-WIDGET
 * pointers drive the indev globals above (button/slider/textarea fire
 * unchanged), VIDEO pointers feed the pure gesture.c FSM. R4 RUNS the FSM and
 * BUFFERS the decisions; the cmd-out (host_command) is R5. */
/* The 10-point-digitizer norm (docs/ui-nodes/README.md): functional demand is 2
 * (pan=1, pinch=2); the other 8 absorb palm/spurious contacts so a real pinch
 * finger is never dropped when palm contacts saturate first. */
#define GESTURE_MAX_POINTERS_TABLE 10
/* The FSM live-pointer store must be able to hold every VIDEO-owned pointer the
 * table can carry, or a 3rd+ pinch finger would drop SILENTLY inside gesture.c
 * (defeating the promote-on-lift the FSM depends on) before the table's own
 * LOUD DOWN-drop (HOSTMSG_DROP_OVERFLOW) ever fires. This is the compile-time
 * loud guard for that bound — the pure FSM stays log-free; the shell asserts.
 */
static_assert(GESTURE_MAX_POINTERS >= GESTURE_MAX_POINTERS_TABLE,
              "gesture FSM store must cover the shell pointer table");
/* Stale-pointer GC window (§7/§8): a slot whose last event is older than this
 * is force-released through the CANCEL path on the next tick — the only
 * recovery from a dropped UP/CANCEL (a leaked slot would wedge the FSM). */
#define POINTER_STALE_MS 2500u
/* Buffered gesture decisions for R5 (the cmd-out wave). onMove during a pinch
 * can emit many ratchet steps, so this is a generous ring the host drains. */
#define GESTURE_DECISION_BUFFER 64
/* Per-pointer ownership, latched at DOWN and sticky for the pointer's life. */
typedef enum {
  POINTER_OWNER_NONE = 0,
  /* unclaimed (free slot) */
  POINTER_OWNER_LVGL = 1,
  /* hit a clickable widget — drives indev globals */
  POINTER_OWNER_VIDEO = 2 /* video area — feeds gesture.c FSM */
} pointer_owner_t;
/* Fields ordered widest-first (the clang-tidy padding-optimal layout); the
 * semantic grouping is in the comments, not the declaration order. */
typedef struct {
  double x;            /* last NDC x */
  double y;            /* last NDC y */
  double down_x;       /* DOWN NDC x — the press's landing point */
  double down_y;       /* DOWN NDC y (clear_press_hover re-hit-tests here) */
  uint64_t event_time; /* last event_time, ms (GC clock) */
  uint32_t pointer_id; /* W3C pointerId — matched ONLY by linear scan */
  bool active;
  uint8_t owner; /* pointer_owner_t */
} pointer_slot_t;
static pointer_slot_t g_pointers[GESTURE_MAX_POINTERS_TABLE];
/* The video-FSM recognizer fed by VIDEO-owned pointers only. */
static gesture_recognizer_t g_gesture;
/* The buffered decisions (R5 drains these). Bounded ring: on overflow the
 * oldest decision is dropped (loud) — the FSM never blocks on a full buffer. */
static gesture_decision_t g_decisions[GESTURE_DECISION_BUFFER];
static uint32_t g_decision_count;
/* ── R5b cmd-out gesture templates ─────────────────────────────────────────
 * The gesture-surface's pre-encoded gesture→cmd templates, COPIED out of the
 * nanopb decode buffer by finalize_widget (renderer.c) before R5a's pb_release
 * frees it. The controls_tick drain matches each buffered gesture_decision_t to
 * the GestureSpec whose kind == decision.kind and emits the patched cmd via
 * cmd_patch_emit (NDC x/y verbatim for pan-end/tap/track; the pinch ±1 step as
 * a DELTA). One spec set (the most-recently-built gesture surface) — the test
 * fixture mounts a single surface; multi-surface routing is a host concern. */
static cmd_gesture_spec_t g_gesture_specs[CMD_PATCH_MAX_GESTURES];
static uint32_t g_gesture_spec_count;
/* The uid of the node that OWNS the current spec set (ITEM 7). The registry is
 * a singleton set — replaced wholesale by the most-recently-built gesture
 * surface — so ONE owner uid suffices. An incremental REMOVE of that node's
 * subtree clears the set (see controls_gesture_specs_clear_owner), else the
 * drain keeps matching stale templates and emits phantom device commands. */
static uint32_t g_gesture_spec_owner_uid;
/* High-water mark of every event_time the module has seen — the event-clock
 * the stale-pointer GC measures age against (NOT the max of currently-live
 * slots, which would never flag a lone leaked slot as stale, §8). */
static uint64_t g_last_event_time;
/* ── R5b HOST_REPORT debounce (hover / cursor feedback, §3+§8) ──────────────
 * On a pointer MOVE the WASM hit-test resolves the hovered widget + the cursor
 * the host should paint, and emits a ui.WasmToHost ONLY when that changes —
 * never every move. Two distinct reports (the WasmToHost oneof carries hover OR
 * cursor): the last-emitted values are cached here so a steady hover over one
 * widget produces exactly one hover + one cursor report, not a per-move flood.
 * The sentinels force a first MOVE to emit (a real hover_uid can be 0 =
 * nothing, and a real cursor is >= 1, so 0 is the never-yet-reported marker).
 */
static uint32_t g_last_hover_uid =
    UINT32_MAX; /* UINT32_MAX = not yet reported */
static bool g_last_hover_interactive;
static uint32_t g_last_cursor; /* 0 = not yet reported; else ui_CursorType */
/* Defined with the HOST_REPORT helpers (below the pointer pipeline); forward-
 * declared here because controls_load_ui (above them) resets the cache. */
static void reset_hover_report(void);
/* controls_host_message return codes. Negative = reject (decode/validate
 * failure, no state change); 0 = handled; positive = a benign no-op class the
 * host can distinguish (overflow drop, orphan event, idempotent re-seat). */
#define HOSTMSG_OK 0
#define HOSTMSG_ERR_DECODE (-1)
/* nanopb pb_decode failed */
#define HOSTMSG_ERR_VERSION (-2)
/* version != the current schema */
#define HOSTMSG_ERR_NO_EVENT (-3)
/* oneof not set */
#define HOSTMSG_ERR_PHASE (-4)
/* phase == UNSPECIFIED */
#define HOSTMSG_ERR_KIND (-5)
/* kind == UNSPECIFIED */
#define HOSTMSG_ERR_EVENT_TIME (-6)
/* event_time == 0 */
#define HOSTMSG_ERR_THEME (-7)
/* lifecycle theme == UNSPECIFIED */
#define HOSTMSG_DROP_OVERFLOW 1
/* DOWN with the table full — dropped */
#define HOSTMSG_NOOP_ORPHAN 2
/* MOVE/UP/CANCEL for an unknown id */
#define HOSTMSG_RESEAT 3
/* DOWN for an already-live id */
/* The host↔WASM channel schema version (ui.HostToWasm.version). A fail-fast
 * guard: the consumer checks the CURRENT value FIRST and rejects a mismatch —
 * no migration branch (docs/ui-nodes/README.md). */
#define UI_INPUT_SCHEMA_VERSION 1u
/* Gesture FSM ops — shared by the pointer pipeline (feed_gesture) and the
 * harness-only gesture_test_feed wire decoder. */
#define GESTURE_OP_DOWN 0
#define GESTURE_OP_MOVE 1
#define GESTURE_OP_UP 2
#define GESTURE_OP_WHEEL 3
#define GESTURE_OP_CANCEL 4
/* ── Keyboard input (keypad indev + ring queue) ────────────────────────────
 * The in-fleet native host and wasmtime harness drive these. Keys
 * arrive via controls_key_event as lv_key_t control values (see
 * lvgl/src/core/lv_group.h) or printable Unicode codepoints, which
 * lv_textarea's LV_EVENT_KEY handler inserts directly. */
#define KEY_QUEUE_CAP 64u
typedef struct {
  uint32_t key;
  uint32_t pressed;
} key_event_t;
static key_event_t key_queue[KEY_QUEUE_CAP];
static uint32_t key_q_head, key_q_tail; /* head == tail -> empty */
/* Group of focusable widgets. lv_group_set_default makes every widget whose
 * class carries group_def TRUE (textarea, spinbox, buttons, ...) join it at
 * creation; the bound keypad indev routes keys to the group-focused widget. */
static lv_group_t *input_group;
/* Composite subject: bp * 2 + theme_dark -> index 0-7
 * Non-static: referenced by renderer.c via extern. */
lv_subject_t subj_composite;
static lv_subject_t subj_channel_type;
/* Internal state for recomputing subj_composite */
static int32_t current_bp = 0;
static int32_t current_theme_dark = 0;
/* Theme family: 0=asgard (default) / 1=vanilla / 2=stock — see theme.h.
 * NOT part of the composite index (families share the variant styles);
 * a change re-inits the child theme and rebuilds the tree. */
static int32_t current_theme_family = 0;
static int32_t current_dpi = 160;
/* Cached UI protobuf data for rebuilding on composite change */
static uint8_t *last_ui_data = NULL;
static uint32_t last_ui_len = 0;
/* Tree-patch state: the FNV-1a-32 of the .pb the live tree currently
 * REPRESENTS (load_ui sets it; a successful patch advances it to the
 * patch's target hash), and the staleness flag — after a patch,
 * last_ui_data no longer matches the live tree, so composite rebuilds
 * from it would silently REVERT the UI (designed out per D6: the
 * setters return CONTROLS_NEEDS_FULL_RELOAD instead). */
static uint32_t current_pb_hash = 0;
static int last_ui_stale = 0;
/* Set when a patch ABORTS mid-batch (any failure other than the
 * provably-pre-mutation base-hash refusal): ops apply sequentially, so
 * earlier ops remain applied and the failing op may itself be partially
 * applied. While set, controls_apply_patch refuses every patch
 * (PATCH_ERR_INDETERMINATE) — only a full controls_load_ui clears it.
 * Partial application is thereby loud and non-compounding (D6 +
 * "Reconciler op-application contract" in
 * docs/lvgl-factory/10-TREE-PATCH-DESIGN.md). */
static int tree_indeterminate = 0;
static uint32_t fnv1a32(const uint8_t *data, uint32_t len) {
  uint32_t hash = 2166136261u;
  for (uint32_t i = 0; i < len; i++) {
    hash ^= data[i];
    hash *= 16777619u;
  }
  return hash;
}
/* Display handle — needed to re-init the default theme on theme switch */
static lv_display_t *display = NULL;
/* Empty + freeze the input group around tree teardown/build. Two LVGL
 * behaviors would otherwise add focus styling to a freshly built frame:
 * lv_group_remove_obj refocuses a sibling (and unfreezes) when the focused
 * widget is deleted, and lv_group_add_obj auto-focuses the first widget
 * added. The demo-parity differential asserts bit-equal first frames, so
 * builds must be focus-free; a pointer CLICK focuses a grouped widget at
 * interaction time (lv_indev.c indev_click_focus -> lv_group_focus_obj). */
static void input_group_begin_build(void) {
  if (!input_group)
    return;
  lv_group_remove_all_objs(input_group);
  lv_group_focus_freeze(input_group, true);
}
static void input_group_end_build(void) {
  if (!input_group)
    return;
  lv_group_focus_freeze(input_group, false);
}
/* Patch-time defocus (see renderer.h): when the focused widget is
 * inside `subtree_root`, replicate lv_group_remove_all_objs' defocus
 * semantics for ONE object — DEFOCUSED event + obj_focus = NULL — so
 * the widget's group removal during deletion neither unfreezes the
 * group nor auto-refocuses a sibling. */
void input_group_defocus_within(lv_obj_t *subtree_root) {
  if (!input_group)
    return;
  lv_obj_t *focused = lv_group_get_focused(input_group);
  if (!focused)
    return;
  for (lv_obj_t *p = focused; p != NULL; p = lv_obj_get_parent(p)) {
    if (p == subtree_root) {
      lv_obj_send_event(focused, LV_EVENT_DEFOCUSED, NULL);
      lv_obj_invalidate(focused);
      input_group->obj_focus = NULL;
      return;
    }
  }
}
/* Rebuild the widget tree from the cached .pb. Returns 0, or the
 * build_ui_from_proto_raw failure (-1) so a composite-triggered rebuild that
 * itself fails surfaces instead of leaving a half-built tree green (D2). */
static int rebuild_ui(void) {
  if (!last_ui_data || last_ui_len == 0)
    return 0;
  input_group_begin_build();
  lv_obj_clean(lv_screen_active());
  int rc =
      build_ui_from_proto_raw(last_ui_data, last_ui_len, lv_screen_active());
  input_group_end_build();
  return rc;
}
/* The default theme must track theme_dark: bare layout containers and
 * built-in widget chrome (slider tracks, switch knobs, dropdown lists)
 * take their colors from the active LVGL theme, not from token styles.
 * Without this, dark mode flips only token-styled surfaces and leaves
 * every theme-styled surface light.
 *
 * The theme font is pinned to montserrat_16: lv_demo_widgets at
 * DISP_LARGE re-inits the default theme with font_normal =
 * montserrat_16 (lv_demo_widgets_components.c), and the theme font
 * feeds class-dispatched metrics (the checkbox marker, screen-level
 * text) — the demo-parity differential needs both builds on the same
 * theme font. Widget text otherwise gets explicit fonts from the
 * codegen pipeline. */
static void apply_default_theme(void) {
  if (!display)
    return;
  /* The asgard family bakes the design-token accent into the stock parent's
   * color_primary: stock's own widget chrome (button fill, checkbox
   * indicator, slider fill, roller SELECTED, chart series) is painted from
   * the palette passed HERE, not from any child-theme field, so this is the
   * one lever that unifies widget chrome with the authored :accent-bg token.
   * Stock + vanilla families keep LVGL's stock BLUE/RED — their parity
   * lanes (vanilla-equals-stock, demo-parity) stay byte-identical. */
  bool asgard_family =
      (asgard_theme_family_t)current_theme_family == ASGARD_THEME_FAMILY_ASGARD;
  /* The accent is deliberately mode-invariant (tokens.edn :accent-bg —
   * white-on-accent clears the text floor in BOTH modes only for this hex),
   * so there is no dark/light fork here; the assert turns a future token
   * divergence into a build error at the one site that would silently
   * ignore it. */
  static_assert(THEME_ACCENT_DARK == THEME_ACCENT_LIGHT,
                "accent tokens diverged — re-fork the mode select here");
  lv_color_t primary = asgard_family ? lv_color_hex(THEME_ACCENT_DARK)
                                     : lv_palette_main(LV_PALETTE_BLUE);
  lv_theme_t *stock =
      lv_theme_default_init(display, primary, lv_palette_main(LV_PALETTE_RED),
                            current_theme_dark != 0, &lv_font_montserrat_16);
  /* The asgard CHILD theme layers the good-looking defaults over stock
   * (parent applies first — lv_theme.c); family selects asgard tokens,
   * the vanilla stock-restatement, or a pure-stock skip (theme.h). */
  lv_theme_t *child =
      asgard_theme_init(display, current_theme_dark != 0,
                        (asgard_theme_family_t)current_theme_family, stock);
  lv_display_set_theme(display, child);
  /* Transparent background: both active screen AND bottom layer must be
   * transparent (the controls overlay sits over video; LVGL 9.x removed
   * LV_COLOR_SCREEN_TRANSP — runtime opacity is the mechanism). Asserted
   * HERE, after the theme install, because lv_display_set_theme re-themes
   * the active screen via lv_theme_apply, which begins with
   * lv_obj_remove_style_all (lv_theme.c) — stripping LOCAL styles too. A
   * transparency set only at display-init silently dies on the first theme
   * (re)install and every pixel then composites against an opaque screen. */
  lv_obj_set_style_bg_opa(lv_screen_active(), LV_OPA_TRANSP, LV_PART_MAIN);
  lv_obj_set_style_bg_opa(lv_layer_bottom(), LV_OPA_TRANSP, LV_PART_MAIN);
}
/* Returns 0, or CONTROLS_NEEDS_FULL_RELOAD when a rebuild was needed
 * but last_ui_data is STALE (patched tree) — rebuilding from the cache
 * would silently revert the patched UI (D6). */
static int32_t update_composite(void) {
  int32_t new_idx = current_bp * 2 + current_theme_dark;
  int32_t old_idx = lv_subject_get_int(&subj_composite);
  lv_subject_set_int(&subj_composite, new_idx);
  /* Variant styles are decoded once at load_ui time using the composite
   * index as a filter — only the active variant's styles are allocated.
   * When the composite index changes, we must rebuild the widget tree
   * so the new variant's styles get decoded and applied. */
  if (new_idx != old_idx) {
    /* Theme lives in bit 0 of the composite index */
    if ((new_idx & 1) != (old_idx & 1))
      apply_default_theme();
    /* A composite change re-decodes the variant styles from the cached .pb.
       * Fresh cache → rebuild. A load IS present but the cache is STALE (a
       * patch mutated the tree) or UNAVAILABLE (load malloc failed, which sets
       * last_ui_stale) → the host must re-send the full .pb. Nothing loaded yet
       * (NULL cache, not stale) → a no-op (the harness sets the breakpoint
       * before the first load). */
    if (last_ui_data && !last_ui_stale) {
      /* D2: a composite-triggered rebuild that itself fails leaves a
           * half-built tree — signal the host to resend the full .pb rather
           * than return green over a broken UI. Mark the cache STALE (like
           * every other failed-build site) so a SUBSEQUENT composite change
           * takes the NEEDS_FULL_RELOAD branch below instead of re-running the
           * same failing rebuild — the D6a loud-AND-non-compounding invariant.
           */
      if (rebuild_ui() != 0) {
        last_ui_stale = 1;
        LOG_WARN("composite rebuild failed — host must send the full .pb");
        return CONTROLS_NEEDS_FULL_RELOAD;
      }
    } else if (last_ui_stale) {
      LOG_WARN("composite change but cached .pb is stale/unavailable — "
               "host must send the current full .pb");
      return CONTROLS_NEEDS_FULL_RELOAD;
    }
  }
  return 0;
}
/* Set the dark/light theme and restyle. Shared by the controls_set_theme_dark
 * export and the ui.Lifecycle theme path (controls_host_message). */
static int32_t set_theme_dark(int32_t dark) {
  current_theme_dark = (dark != 0) ? 1 : 0;
  return update_composite();
}
/**
 * LVGL display flush callback.
 * LVGL 9.x outputs ARGB8888 when color format is LV_COLOR_FORMAT_ARGB8888.
 * wgpu expects Rgba8Unorm -> must swizzle ARGB -> RGBA.
 * On little-endian WASM, ARGB8888 memory order is [B, G, R, A] (BGRA).
 */
static void
flush_cb(lv_display_t *disp, const lv_area_t *area,
         uint8_t *px_map) /* NOLINT(readability-non-const-parameter) —
                             signature must match lv_display_flush_cb_t */
{
  if (!framebuffer || !px_map) {
    lv_display_flush_ready(disp);
    return;
  }
  /* Clamp dirty area to framebuffer bounds */
  int32_t x1 = LV_MAX(area->x1, 0);
  int32_t y1 = LV_MAX(area->y1, 0);
  int32_t x2 = LV_MIN(area->x2, (int32_t)fb_width - 1);
  int32_t y2 = LV_MIN(area->y2, (int32_t)fb_height - 1);
  int32_t src_w = lv_area_get_width(area);
  int32_t w = x2 - x1 + 1;
  if (w <= 0 || y2 < y1) {
    lv_display_flush_ready(disp);
    return;
  }
  for (int32_t y = y1; y <= y2; y++) {
    for (int32_t x = x1; x <= x2; x++) {
      uint32_t src_idx = ((uint32_t)(y - area->y1) * (uint32_t)src_w +
                          (uint32_t)(x - area->x1)) *
                         4;
      uint32_t dst_idx = ((uint32_t)y * fb_width + (uint32_t)x) * 4;
      /* Swizzle BGRA -> RGBA for wgpu Rgba8Unorm */
      framebuffer[dst_idx + 0] = px_map[src_idx + 2]; /* R */
      framebuffer[dst_idx + 1] = px_map[src_idx + 1]; /* G */
      framebuffer[dst_idx + 2] = px_map[src_idx + 0]; /* B */
      framebuffer[dst_idx + 3] = px_map[src_idx + 3]; /* A */
    }
  }
  /* Accumulate dirty rect (union of all flush areas this tick) */
  if (!dirty_valid) {
    dirty_x1 = x1;
    dirty_y1 = y1;
    dirty_x2 = x2;
    dirty_y2 = y2;
    dirty_valid = 1;
  } else {
    if (x1 < dirty_x1)
      dirty_x1 = x1;
    if (y1 < dirty_y1)
      dirty_y1 = y1;
    if (x2 > dirty_x2)
      dirty_x2 = x2;
    if (y2 > dirty_y2)
      dirty_y2 = y2;
  }
  lv_display_flush_ready(disp);
  flush_happened = 1;
}
/**
 * LVGL 9.x display init.
 */
static lv_display_t *init_display(uint32_t width, uint32_t height) {
  lv_display_t *disp = lv_display_create(width, height);
  /* Set ARGB8888 color format (32-bit with alpha for transparent overlay) */
  lv_display_set_color_format(disp, LV_COLOR_FORMAT_ARGB8888);
  /* Allocate double draw buffers (64-row strips for PARTIAL mode) */
  uint32_t draw_rows = 64;
  uint32_t buf_size = width * draw_rows * 4;
  draw_buf1 = malloc(buf_size);
  draw_buf2 = malloc(buf_size);
  if (!draw_buf1 || !draw_buf2) {
    LOG_ERROR("draw buffer allocation failed (%u bytes)", buf_size);
    free(draw_buf1);
    free(draw_buf2);
    draw_buf1 = NULL;
    draw_buf2 = NULL;
    return NULL;
  }
  lv_display_set_buffers(disp, draw_buf1, draw_buf2, buf_size,
                         LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_set_flush_cb(disp, flush_cb);
  /* The controls overlay sits OVER video: the active screen is created
   * CLICKABLE by default (lv_obj_constructor), which would make the
   * capture-on-claim hit-test (controls_host_message) resolve every bare-bg
   * point to the screen and route it to LVGL. Clear it so a point that hits
   * no clickable widget falls through to NULL = the video gesture-surface
   * (§4 "bare bg over video → VIDEO-FSM"). */
  lv_obj_remove_flag(lv_screen_active(), LV_OBJ_FLAG_CLICKABLE);
  return disp;
}
/* LVGL input read callback (polled each tick) */
static void indev_read_cb(lv_indev_t *indev, lv_indev_data_t *data) {
  (void)indev;
  data->point.x = pointer_x;
  data->point.y = pointer_y;
  data->state =
      pointer_pressed ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
}
/* Keypad read callback: drain one queued key event per call;
 * continue_reading makes LVGL re-poll until the queue is empty, so every
 * press/release edge is observed even when several keys land in one tick. */
static void keypad_read_cb(lv_indev_t *indev, lv_indev_data_t *data) {
  (void)indev;
  if (key_q_head == key_q_tail) {
    data->state = LV_INDEV_STATE_RELEASED;
    return;
  }
  key_event_t ev = key_queue[key_q_head];
  key_q_head = (key_q_head + 1u) % KEY_QUEUE_CAP;
  data->key = ev.key;
  data->state = ev.pressed ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
  data->continue_reading = key_q_head != key_q_tail;
}
/*
 * === WASM Exports ===
 */
/* Max framebuffer dimension. Caps `width * height * 4` (computed in uint32)
 * well below overflow: 16384*16384*4 = 2^30 = 1 GiB < UINT32_MAX (~4 GiB). An
 * uncapped huge dim wrapped the uint32 size to a small value → calloc a tiny
 * buffer the renderer then wrote past (heap corruption). */
#define CONTROLS_MAX_DIM 16384u
int32_t controls_init(uint32_t width, uint32_t height) {
  if (width == 0 || height == 0 || width > CONTROLS_MAX_DIM ||
      height > CONTROLS_MAX_DIM)
    return -1;
  lv_init();
  fb_width = width;
  fb_height = height;
  framebuffer = calloc(width * height * 4, 1);
  if (!framebuffer)
    return -1;
  lv_display_t *disp = init_display(width, height);
  if (!disp)
    return -1;
  display = disp;
  apply_default_theme();
  /* Pointer table + gesture FSM start empty (controls_host_message owns them).
   * Statics survive a reactor re-init: a fresh controls_init must not inherit
   * the previous session's pointer state. */
  memset(g_pointers, 0, sizeof(g_pointers));
  gesture_reset(&g_gesture);
  g_decision_count = 0;
  g_last_event_time = 0;
  pointer_x = 0;
  pointer_y = 0;
  pointer_pressed = 0;
  /* Input device */
  lv_indev_t *indev = lv_indev_create();
  lv_indev_set_type(indev, LV_INDEV_TYPE_POINTER);
  lv_indev_set_read_cb(indev, indev_read_cb);
  /* Keyboard: a keypad indev draining the controls_key_event queue into
   * the default group. Widgets join the group at creation (class-level
   * group_def via lv_group_set_default); builds run with the group frozen
   * (input_group_begin_build) so nothing is focused until a pointer click
   * focuses a grouped widget — keys before that are dropped by design. */
  input_group = lv_group_create();
  lv_group_set_default(input_group);
  lv_indev_t *keypad = lv_indev_create();
  lv_indev_set_type(keypad, LV_INDEV_TYPE_KEYPAD);
  lv_indev_set_read_cb(keypad, keypad_read_cb);
  lv_indev_set_group(keypad, input_group);
  /* Initialize subjects */
  lv_subject_init_int(&subj_composite, 0);
  lv_subject_init_int(&subj_channel_type, 0);
  /* Register SVG image decoder (ThorVG-based) */
  svg_decoder_init();
  /* No UI built here — wait for controls_load_ui() call */
  return 0;
}
/**
 * Push a protobuf UI AST and rebuild the widget tree.
 *
 * TIMING CONTRACT — THE TREE HAS NO GEOMETRY UNTIL THE NEXT REFRESH.
 * This call builds the widget tree but does NOT lay it out. Layout runs inside
 * LVGL's display-refresh timer (lv_display_refr_timer -> lv_obj_update_layout,
 * lv_refr.c), whose period is LV_DEF_REFR_PERIOD (33 ms by default), and
 * controls_tick(ms) is the only clock a WASM host has. So between this call and
 * the first refresh, EVERY object still sits at its parent's content origin with
 * w = h = 0.
 *
 * The consequence a host must plan for: a pointer event delivered in that window
 * hit-tests against zero-area objects and is DROPPED SILENTLY — no error, no
 * return code, no event. A host that loads a screen and immediately synthesises a
 * click sees the click simply not happen.
 *
 * So: after controls_load_ui, tick past one refresh period BEFORE delivering
 * input. wasm_harness pins RENDER_TICKS * TICK_MS = 48 ms for exactly this
 * reason; a host driving its own clock should either match that or poll the real
 * post-condition (every node in controls_dump_tree has a non-degenerate rect),
 * which does not rot if LV_DEF_REFR_PERIOD or the host's tick size changes.
 *
 * Documented 2026-07-22 after a downstream consumer spent a day misdiagnosing a
 * silently-dropped click as a widget defect: the behaviour is correct, but the
 * contract was written down nowhere.
 */
int32_t controls_load_ui(uint32_t ptr, uint32_t len) {
  /* A full load is the patch chain's reset point: the cache becomes
   * fresh again, the current-state hash is the loaded bytes', and an
   * indeterminate tree (aborted patch) becomes determinate again. */
  current_pb_hash = fnv1a32((const uint8_t *)ptr, len);
  last_ui_stale = 0;
  tree_indeterminate = 0;
  /* Cache the protobuf data for rebuilding on composite index change
   * (breakpoint or theme switch). The host passes data from WASM linear
   * memory, so we must copy it to a persistent heap buffer. */
  if (last_ui_data)
    free(last_ui_data);
  last_ui_data = malloc(len);
  if (last_ui_data) {
    memcpy(last_ui_data, (const uint8_t *)ptr, len);
    last_ui_len = len;
  } else {
    /* No cache → a later composite (breakpoint/theme) change cannot re-decode
       * the variant styles from the .pb. Mark the cache stale (a NULL cache
       * cannot match the live tree) so update_composite signals
       * NEEDS_FULL_RELOAD rather than silently mis-rendering — distinct from a
       * fresh boot where NOTHING is loaded and the composite change is a no-op.
       */
    LOG_ERROR("last_ui_data malloc failed (%u bytes) — composite change will "
              "need a host full-reload",
              (unsigned)len);
    last_ui_len = 0;
    last_ui_stale = 1;
  }
  /* Lifecycle order matters:
   * 1. lv_obj_clean: destroy widgets (fires DELETE events, detaches observers)
   * 2. build_ui_from_proto_raw → reset_subject_registry: deinit subjects
   * Widgets must go first so observer callbacks don't fire on stale subjects.
   */
  input_group_begin_build();
  lv_obj_clean(lv_screen_active());
  /* Decode protobuf AST via nanopb and build widget tree.
   * renderer.c handles the actual decode + build. */
  int32_t status =
      build_ui_from_proto_raw((const uint8_t *)ptr, len, lv_screen_active());
  input_group_end_build();
  /* A fresh tree re-assigns uids, so any cached hover identity is stale —
   * invalidate the HOST_REPORT debounce so the next MOVE re-reports (§3). */
  reset_hover_report();
  /* A FAILED build (overflow / decode error) leaves last_ui_data caching a
   * screen that won't render right — mark it stale so a later composite change
   * asks the host to re-send rather than rebuilding the broken screen. */
  if (status != 0)
    last_ui_stale = 1;
  return status;
}
int32_t controls_update_state(uint32_t ptr, uint32_t len) {
  return update_state_from_proto((const uint8_t *)ptr, len);
}
/**
 * Apply a ScreenPatch (partial tree update) against the live widget
 * tree. Returns 0 on success or a negative PATCH_ERR_* code (see
 * renderer.h) — the host's signal to recover by sending the full .pb.
 * The input group is frozen for the batch (no focus churn); on success
 * the current-state hash advances to the patch's target hash and the
 * cached full .pb is marked STALE (composite setters then return
 * CONTROLS_NEEDS_FULL_RELOAD instead of silently reverting). On any
 * abort other than the pre-mutation base-hash refusal the tree is
 * marked INDETERMINATE: every further patch refuses with
 * PATCH_ERR_INDETERMINATE until a full controls_load_ui — partial
 * application is never silently compounded (doc 10 § D6a).
 */
int32_t controls_apply_patch(uint32_t ptr, uint32_t len) {
  if (tree_indeterminate) {
    LOG_ERROR("patch refused: tree is INDETERMINATE after a prior "
              "aborted patch — send a full .pb");
    return PATCH_ERR_INDETERMINATE;
  }
  uint32_t target_hash = 0;
  if (input_group)
    lv_group_focus_freeze(input_group, true);
  int rc = apply_patch_from_proto_raw((const uint8_t *)ptr, len,
                                      current_pb_hash, &target_hash);
  if (input_group)
    lv_group_focus_freeze(input_group, false);
  if (rc == 0) {
    current_pb_hash = target_hash;
    last_ui_stale = 1;
  } else if (rc != PATCH_ERR_BASE_HASH) {
    /* The base-hash refusal is checked before the first op and is the
       * only provably-pre-mutation failure. Every other abort may have
       * mutated the tree (prior ops applied; the failing op possibly
       * half-applied by the streaming build) — mark it indeterminate so
       * further patches refuse and composite setters demand the full
       * reload instead of rebuilding from a cache that no longer
       * matches the live tree. */
    tree_indeterminate = 1;
    last_ui_stale = 1;
  }
  return rc;
}
/* ── Pointer pipeline (controls_host_message) ──────────────────────────────
 * The bounded table is scanned linearly (NEVER indexed by the raw 32-bit id);
 * DOWN allocates/re-seats, MOVE/UP/CANCEL route by the latched owner. */
/* Find the live slot for `pointer_id`, or NULL. Linear scan over <=10. */
static pointer_slot_t *pointer_find(uint32_t pointer_id) {
  for (int i = 0; i < GESTURE_MAX_POINTERS_TABLE; i++) {
    if (g_pointers[i].active && g_pointers[i].pointer_id == pointer_id)
      return &g_pointers[i];
  }
  return NULL;
}
/* Claim a free slot, or NULL when the table is full (DOWN beyond MAX is
 * dropped — a live slot is NEVER evicted). */
static pointer_slot_t *pointer_alloc(void) {
  for (int i = 0; i < GESTURE_MAX_POINTERS_TABLE; i++) {
    if (!g_pointers[i].active)
      return &g_pointers[i];
  }
  return NULL;
}
/* Map a clamped NDC coord to framebuffer px. NDC is +x right, +y UP, so the
 * Y axis flips: [-1,1] -> [0,height] with 1.0 (top) at row 0. */
static void ndc_to_px(double ndc_x, double ndc_y, lv_point_t *out) {
  out->x = (int32_t)((ndc_x + 1.0) * 0.5 * (double)fb_width);
  out->y = (int32_t)((1.0 - ndc_y) * 0.5 * (double)fb_height);
}
/* Capture-on-claim hit-test (§4/§5): resolve the top clickable LVGL obj under
 * the NDC point. lv_indev_search_obj walks the active screen honoring HIDDEN +
 * LV_OBJ_FLAG_CLICKABLE (lv_obj_hit_test), exactly as the indev press path; a
 * STATIC host_proxy box has CLICKABLE cleared so the pointer falls through to
 * the video area (returns NULL). Returns LVGL when a clickable widget is hit,
 * else VIDEO. */
static pointer_owner_t hit_test_owner(double ndc_x, double ndc_y) {
  lv_point_t p;
  ndc_to_px(ndc_x, ndc_y, &p);
  lv_obj_t *hit = lv_indev_search_obj(lv_screen_active(), &p);
  return hit ? POINTER_OWNER_LVGL : POINTER_OWNER_VIDEO;
}
/* Clear the phantom hover a finished LVGL-owned press leaves behind.
 *
 * LVGL's press path sends LV_EVENT_HOVER_OVER to the widget it presses
 * (adding LV_STATE_HOVERED) WITHOUT recording it as the indev's last_hovered
 * (lv_indev.c updates last_hovered only when a PREVIOUS hover existed), and
 * every HOVER_LEAVE targets last_hovered — so a tap-style press (a DOWN with
 * no prior released-position move, the only stream this shell produces, since
 * an orphan MOVE is dropped by contract) ORPHANS the HOVERED state on the
 * tapped widget: no later event can clear it. Invisible under themes that
 * never style :hovered; visible since the asgard theme does. Candidate
 * upstream fix; until then the shell owns the cleanup.
 *
 * Re-run the SAME hit-test the press used, at the lift point AND the down
 * point (a press dragged off its widget hovers the DOWN-point widget), and
 * strip LV_STATE_HOVERED from what they find. Both lookups walk the LIVE
 * tree, so a widget deleted mid-press is simply not found — no stored-pointer
 * lifetime hazard, and LVGL-internal clickables (tab-bar buttons) with no uid
 * are covered too. Residual: a widget that MOVED between down and lift
 * (layout shift mid-press) can keep its hover; accepted until a
 * released-pointer-tracking hover contract supersedes this cleanup with real
 * positions. Second residual: deleting the pressed widget mid-press sets
 * LVGL's wait_until_release, whose release pass re-sends HOVER_OVER to the
 * widget under the point on the tick AFTER this strip — that hover stays
 * until the next press; the same supersession retires it. */
static void clear_press_hover(double down_x, double down_y, double up_x,
                              double up_y) {
  lv_obj_t *screen = lv_screen_active();
  if (!screen)
    return;
  lv_point_t p;
  ndc_to_px(up_x, up_y, &p);
  lv_obj_t *up_hit = lv_indev_search_obj(screen, &p);
  if (up_hit)
    lv_obj_remove_state(up_hit, LV_STATE_HOVERED);
  ndc_to_px(down_x, down_y, &p);
  lv_obj_t *down_hit = lv_indev_search_obj(screen, &p);
  if (down_hit && down_hit != up_hit)
    lv_obj_remove_state(down_hit, LV_STATE_HOVERED);
}
/* Drive the indev globals from the primary (first) LVGL-owned slot, or release
 * the press when no LVGL-owned pointer is live. indev_read_cb polls these on
 * the next tick, so button_event_cb / slider / textarea fire unchanged. */
static void indev_sync_from_table(void) {
  for (int i = 0; i < GESTURE_MAX_POINTERS_TABLE; i++) {
    if (g_pointers[i].active && g_pointers[i].owner == POINTER_OWNER_LVGL) {
      lv_point_t p;
      ndc_to_px(g_pointers[i].x, g_pointers[i].y, &p);
      pointer_x = p.x;
      pointer_y = p.y;
      pointer_pressed = 1;
      return;
    }
  }
  pointer_pressed = 0;
}
/* Append a decision to the R5 buffer; on overflow drop the OLDEST (loud) so the
 * FSM never blocks on a full ring. */
static void buffer_decision(const gesture_decision_t *d) {
  if (g_decision_count >= GESTURE_DECISION_BUFFER) {
    LOG_WARN("gesture decision buffer full (%u) — dropping oldest",
             GESTURE_DECISION_BUFFER);
    memmove(&g_decisions[0], &g_decisions[1],
            (GESTURE_DECISION_BUFFER - 1) * sizeof(g_decisions[0]));
    g_decision_count = GESTURE_DECISION_BUFFER - 1;
  }
  g_decisions[g_decision_count++] = *d;
}
/* ── R5b cmd-out gesture-template registry (renderer.h drain seam) ──────────*/
/* Clear the registered gesture→cmd templates (a full build starts empty). */
void controls_gesture_specs_reset(void) {
  g_gesture_spec_count = 0;
  g_gesture_spec_owner_uid = 0;
}
/* Replace the registered set with `count` specs (renderer-owned static array,
 * copied by value), OWNED by `owner_uid` (the building node's uid). A second
 * gesture surface in one build overwrites the first — the app mounts a single
 * surface, and the host owns the multi-surface routing the .pb does not encode.
 * The owner uid lets an incremental REMOVE of that node clear the set. */
void controls_gesture_specs_set(const cmd_gesture_spec_t *specs, uint32_t count,
                                uint32_t owner_uid) {
  if (count > CMD_PATCH_MAX_GESTURES) {
    LOG_WARN("gesture spec count %u exceeds max %u — truncating", count,
             CMD_PATCH_MAX_GESTURES);
    count = CMD_PATCH_MAX_GESTURES;
  }
  for (uint32_t i = 0; i < count; i++)
    g_gesture_specs[i] = specs[i];
  g_gesture_spec_count = count;
  g_gesture_spec_owner_uid = owner_uid;
}
/* Clear the spec set iff `uid` owns it (ITEM 7). Called from unregister_subtree
 * when a REMOVE patch tears down a gesture surface — a no-op for any other uid,
 * exactly like remove_proxy_entry. Without this, a removed surface's templates
 * stay live in the singleton registry and the drain keeps emitting them. */
void controls_gesture_specs_clear_owner(uint32_t uid) {
  if (g_gesture_spec_count > 0 && uid == g_gesture_spec_owner_uid) {
    g_gesture_spec_count = 0;
    g_gesture_spec_owner_uid = 0;
  }
}
/* The GestureSpec whose kind matches `kind`, or NULL. The host recognizer
 * decides a gesture_kind_t; the drain resolves it to its pre-encoded
 * template. */
static const cmd_gesture_spec_t *find_gesture_spec(gesture_kind_t kind) {
  for (uint32_t i = 0; i < g_gesture_spec_count; i++) {
    if (g_gesture_specs[i].kind == (uint32_t)kind)
      return &g_gesture_specs[i];
  }
  return NULL;
}
/* Drain the buffered gesture decisions into cmd-out (R5b §8). Per decision,
 * find the GestureSpec whose kind matches and emit the patched cmd:
 *   - PAN_END / TAP / TRACK carry the NDC point (x/y written verbatim);
 *   - PINCH carries a ±1 step (the DELTA varint slot).
 * A decision with no registered template (PAN_MOVE→Axis and optional host wheel
 * input have none) is dropped silently — there is nothing to send. The buffer
 * is cleared after the drain. */
static void drain_gesture_decisions(void) {
  for (uint32_t i = 0; i < g_decision_count; i++) {
    const gesture_decision_t *d = &g_decisions[i];
    const cmd_gesture_spec_t *spec = find_gesture_spec(d->kind);
    if (!spec)
      continue;
    (void)cmd_patch_emit(&spec->cmd, d->x, d->y, d->delta);
  }
  g_decision_count = 0;
}
/* Feed a VIDEO-owned sample to the FSM and buffer every decision emitted. */
static void feed_gesture(int op, uint32_t pointer_id, double x, double y,
                         uint64_t t_ms) {
  gesture_sample_t s = {
      .pointer_id = (int32_t)pointer_id, .x = x, .y = y, .t_ms = (int32_t)t_ms};
  gesture_decision_t out[GESTURE_MAX_DECISIONS];
  int32_t n = 0;
  switch (op) {
  case GESTURE_OP_DOWN:
    n = gesture_on_down(&g_gesture, &s, out);
    break;
  case GESTURE_OP_MOVE:
    n = gesture_on_move(&g_gesture, &s, out);
    break;
  case GESTURE_OP_UP:
    n = gesture_on_up(&g_gesture, &s, out);
    break;
  default:
    break;
  }
  /* ROI rubber-band: a mode-gated REINTERPRETATION of the pan stream, NOT a new
   * FSM phase. When an ROI-mode gesture surface is mounted (an ROI GestureSpec
   * is registered — the cheapest signal), a completed drag's PAN_END becomes a
   * 4-corner rect. gesture_on_up cleared has_start but left g_gesture.start
   * intact, so BOTH corners are readable here in one synchronous call:
   * g_gesture.start = the DOWN corner, out[i] = the UP corner. Emit the rect
   * DIRECTLY (down→up order; min/max ordering deferred to the consumer) instead
   * of buffering the single-point decision — a plain TAP in ROI-mode still
   * falls through to its own point-select spec via the normal drain (mirroring
   * jettison's tap→point-select vs drag→ROI-select split). GESTURE_KIND_ROI
   * never appears as a decision kind on the wire; it is only this lookup key. */
  const cmd_gesture_spec_t *roi_spec = find_gesture_spec(GESTURE_KIND_ROI);
  for (int32_t i = 0; i < n; i++) {
    if (roi_spec && out[i].kind == GESTURE_KIND_PAN_END) {
      (void)cmd_patch_emit_rect(&roi_spec->cmd, g_gesture.start.x,
                                g_gesture.start.y, out[i].x, out[i].y);
      continue;
    }
    buffer_decision(&out[i]);
  }
}
/* Release a slot through the CANCEL path: a VIDEO-owned pointer reaches the
 * FSM as a SILENT abort (gesture_on_cancel — no terminal, no last_tap seed,
 * the 2->1 pinch-drop generalized); an LVGL-owned pointer just drops out of
 * the indev sync. The slot is freed either way. CANCEL is a DISTINCT path from
 * UP: a dropped/aborted contact never fires a phantom pan-end (§7). */
static void pointer_release_cancel(pointer_slot_t *slot) {
  if (slot->owner == POINTER_OWNER_VIDEO) {
    gesture_sample_t s = {.pointer_id = (int32_t)slot->pointer_id,
                          .x = slot->x,
                          .y = slot->y,
                          .t_ms = (int32_t)slot->event_time};
    gesture_decision_t out[GESTURE_MAX_DECISIONS];
    (void)gesture_on_cancel(&g_gesture, &s, out);
  } else if (slot->owner == POINTER_OWNER_LVGL) {
    /* A cancelled/GC'd press still applied its press-hover — strip it. */
    clear_press_hover(slot->down_x, slot->down_y, slot->x, slot->y);
  }
  slot->active = false;
}
/* ── R5b HOST_REPORT: hover / cursor feedback (§3+§8) ───────────────────────
 * On a pointer MOVE the WASM resolves what is under the cursor and the cursor
 * the host should paint. The hit-test is the SAME capture-on-claim resolution
 * the routing path uses (lv_indev_search_obj over the active screen, honoring
 * HIDDEN + CLICKABLE): a clickable widget is hit, the bare video surface (a
 * STATIC host_proxy with CLICKABLE cleared) resolves to NULL. The cursor maps
 * by class — a textarea wants a TEXT caret, any other clickable wants a POINTER
 * affordance, and the bare surface keeps the DEFAULT cursor. Identity travels
 * as the WidgetNode.uid mirrored into lv_obj user_data (finalize_widget). */
/* Resolve the hovered widget under the clamped NDC point into a uid + the
 * cursor + interactivity. hovered_uid 0 = nothing (bare surface). */
static void compute_hover(double ndc_x, double ndc_y, uint32_t *out_uid,
                          bool *out_interactive, ui_CursorType *out_cursor) {
  lv_point_t p;
  ndc_to_px(ndc_x, ndc_y, &p);
  lv_obj_t *hit = lv_indev_search_obj(lv_screen_active(), &p);
  if (!hit) {
    *out_uid = 0;
    *out_interactive = false;
    *out_cursor = ui_CursorType_CURSOR_TYPE_DEFAULT;
    return;
  }
  /* user_data carries the codegen uid (finalize_widget mirrors it); a node with
   * no uid reads back 0, which is the same "nothing addressable" marker. */
  *out_uid = (uint32_t)(uintptr_t)lv_obj_get_user_data(hit);
  *out_interactive = true;
  /* A textarea (free-text field) asks for the TEXT caret; lv_spinbox derives
   * from textarea but is a stepped numeric, so the exact-class check keeps it
   * on the POINTER affordance (mirrors focused_textarea's exact-class
   * discipline).
   */
  if (lv_obj_check_type(hit, &lv_textarea_class))
    *out_cursor = ui_CursorType_CURSOR_TYPE_TEXT;
  else
    *out_cursor = ui_CursorType_CURSOR_TYPE_POINTER;
}
/* pb_encode a ui.WasmToHost and relay it to the host via host_report. The
 * envelope carries the version guard + the populated oneof arm. Returns the
 * host_report result, or -1 if the encode overflows the bounded buffer (never
 * expected — ui_WasmToHost_size is 16). */
static int32_t emit_wasm_to_host(const ui_WasmToHost *msg) {
  uint8_t buf[ui_WasmToHost_size];
  pb_ostream_t stream = pb_ostream_from_buffer(buf, sizeof(buf));
  if (!pb_encode(&stream, ui_WasmToHost_fields, msg)) {
    LOG_WARN("host_report encode failed: %s", PB_GET_ERROR(&stream));
    return -1;
  }
  return host_report((uint32_t)(uintptr_t)buf, (uint32_t)stream.bytes_written);
}
/* Emit the hover + cursor reports for the point, DEBOUNCED: a hover report
 * fires only when (uid, interactive) changes; a cursor report only when the
 * cursor changes. A steady hover over one widget therefore produces exactly one
 * of each, not a per-move flood (§3 change-gated). Two separate WasmToHost
 * messages because its report is a oneof (hover XOR cursor). */
static void report_hover_cursor(double ndc_x, double ndc_y) {
  uint32_t uid;
  bool interactive;
  ui_CursorType cursor;
  compute_hover(ndc_x, ndc_y, &uid, &interactive, &cursor);
  if (uid != g_last_hover_uid || interactive != g_last_hover_interactive) {
    ui_WasmToHost msg = ui_WasmToHost_init_zero;
    msg.version = UI_INPUT_SCHEMA_VERSION;
    msg.which_report = ui_WasmToHost_hover_tag;
    msg.report.hover.hovered_uid = uid;
    msg.report.hover.interactive = interactive;
    (void)emit_wasm_to_host(&msg);
    g_last_hover_uid = uid;
    g_last_hover_interactive = interactive;
  }
  if ((uint32_t)cursor != g_last_cursor) {
    ui_WasmToHost msg = ui_WasmToHost_init_zero;
    msg.version = UI_INPUT_SCHEMA_VERSION;
    msg.which_report = ui_WasmToHost_cursor_tag;
    msg.report.cursor.cursor = cursor;
    (void)emit_wasm_to_host(&msg);
    g_last_cursor = (uint32_t)cursor;
  }
}
/* Reset the hover/cursor debounce to the never-yet-reported sentinels — a fresh
 * build (uids change) and the whole-surface flush both invalidate the cache, so
 * the next MOVE re-reports from scratch. */
static void reset_hover_report(void) {
  g_last_hover_uid = UINT32_MAX;
  g_last_hover_interactive = false;
  g_last_cursor = 0;
}
/* The POINTER pipeline (steps 3-6): self-validate, table-maintain, route. */
static int32_t handle_pointer(const ui_PointerEvent *pe) {
  /* Self-validate at the boundary — nanopb strips buf.validate (§7). */
  if (pe->phase == ui_PointerPhase_POINTER_PHASE_UNSPECIFIED)
    return HOSTMSG_ERR_PHASE;
  if (pe->kind == ui_PointerKind_POINTER_KIND_UNSPECIFIED)
    return HOSTMSG_ERR_KIND;
  if (pe->event_time == 0)
    return HOSTMSG_ERR_EVENT_TIME;
  /* Clamp NDC to [-1,1] (a faithful host already does, but the host is
   * untrusted — the module validates every event). */
  double x = pe->x;
  double y = pe->y;
  if (x < -1.0)
    x = -1.0;
  else if (x > 1.0)
    x = 1.0;
  if (y < -1.0)
    y = -1.0;
  else if (y > 1.0)
    y = 1.0;
  /* Advance the event-clock high-water mark (the GC age reference). */
  if (pe->event_time > g_last_event_time)
    g_last_event_time = pe->event_time;
  switch (pe->phase) {
  case ui_PointerPhase_POINTER_PHASE_DOWN: {
    pointer_slot_t *live = pointer_find(pe->pointer_id);
    if (live) {
      /* Idempotent re-seat: update the sample, do NOT re-run onDown (a
             * duplicate DOWN must not flip a 1->2 transition into pinch). */
      live->x = x;
      live->y = y;
      live->down_x = x;
      live->down_y = y;
      live->event_time = pe->event_time;
      indev_sync_from_table();
      return HOSTMSG_RESEAT;
    }
    pointer_slot_t *slot = pointer_alloc();
    if (!slot) {
      /* DOWN beyond MAX — drop the NEW pointer; never evict a tracked
             * one (§7 overflow mitigation). */
      LOG_WARN("pointer table full (%d) — dropping DOWN id=%u",
               GESTURE_MAX_POINTERS_TABLE, pe->pointer_id);
      return HOSTMSG_DROP_OVERFLOW;
    }
    pointer_owner_t owner = hit_test_owner(x, y);
    slot->active = true;
    slot->pointer_id = pe->pointer_id;
    slot->x = x;
    slot->y = y;
    slot->down_x = x;
    slot->down_y = y;
    slot->event_time = pe->event_time;
    slot->owner = (uint8_t)owner;
    if (owner == POINTER_OWNER_VIDEO)
      feed_gesture(GESTURE_OP_DOWN, pe->pointer_id, x, y, pe->event_time);
    indev_sync_from_table();
    return HOSTMSG_OK;
  }
  case ui_PointerPhase_POINTER_PHASE_MOVE: {
    pointer_slot_t *slot = pointer_find(pe->pointer_id);
    if (!slot)
      return HOSTMSG_NOOP_ORPHAN; /* never implicit-insert */
    slot->x = x;
    slot->y = y;
    slot->event_time = pe->event_time;
    if (slot->owner == POINTER_OWNER_VIDEO)
      feed_gesture(GESTURE_OP_MOVE, pe->pointer_id, x, y, pe->event_time);
    indev_sync_from_table();
    /* HOST_REPORT: relay the hovered widget + cursor (change-gated, §3). A
         * MOVE on any owner can change what the host should paint under the
         * cursor, so this runs regardless of the routing owner. */
    report_hover_cursor(x, y);
    return HOSTMSG_OK;
  }
  case ui_PointerPhase_POINTER_PHASE_UP: {
    pointer_slot_t *slot = pointer_find(pe->pointer_id);
    if (!slot)
      return HOSTMSG_NOOP_ORPHAN;
    slot->x = x;
    slot->y = y;
    slot->event_time = pe->event_time;
    if (slot->owner == POINTER_OWNER_VIDEO)
      feed_gesture(GESTURE_OP_UP, pe->pointer_id, x, y, pe->event_time);
    else if (slot->owner == POINTER_OWNER_LVGL)
      clear_press_hover(slot->down_x, slot->down_y, x, y);
    slot->active = false;
    indev_sync_from_table();
    return HOSTMSG_OK;
  }
  case ui_PointerPhase_POINTER_PHASE_CANCEL: {
    pointer_slot_t *slot = pointer_find(pe->pointer_id);
    if (!slot)
      return HOSTMSG_NOOP_ORPHAN;
    pointer_release_cancel(slot);
    indev_sync_from_table();
    return HOSTMSG_OK;
  }
  default:
    return HOSTMSG_ERR_PHASE;
  }
}
/* The LIFECYCLE pipeline (step 7): theme restyle + the whole-surface flush. */
static int32_t handle_lifecycle(const ui_Lifecycle *lc) {
  if (lc->theme == ui_ThemeMode_THEME_MODE_UNSPECIFIED)
    return HOSTMSG_ERR_THEME;
  /* focused/visible == false doubles as the whole-surface FSM flush: reset the
   * pointer table + the recognizer (recovers from blur/tab-switch pointer loss
   * that a per-pointer CANCEL cannot cover, §7). Drop the indev press too. */
  if (!lc->focused || !lc->visible) {
    /* Any in-flight LVGL press dies here without an UP — strip the hover its
     * press applied before the table forgets which slots were live. */
    for (int i = 0; i < GESTURE_MAX_POINTERS_TABLE; i++) {
      if (g_pointers[i].active && g_pointers[i].owner == POINTER_OWNER_LVGL)
        clear_press_hover(g_pointers[i].down_x, g_pointers[i].down_y,
                          g_pointers[i].x, g_pointers[i].y);
    }
    memset(g_pointers, 0, sizeof(g_pointers));
    gesture_reset(&g_gesture);
    g_decision_count = 0;
    g_last_event_time = 0;
    pointer_pressed = 0;
    /* The cursor is no longer over anything — invalidate the hover cache so
       * the next MOVE re-reports from scratch (the host has likely repainted
       * the default cursor on blur anyway). */
    reset_hover_report();
  }
  return set_theme_dark(lc->theme == ui_ThemeMode_THEME_MODE_DARK ? 1 : 0);
}
/**
 * Decode a ui.HostToWasm and dispatch to the pointer or lifecycle pipeline.
 * `ptr`/`len` reference WASM linear memory (copied to a heap buffer before
 * decode, mirroring controls_load_ui). Returns HOSTMSG_OK (0) on success, a
 * negative HOSTMSG_ERR_* on decode/validate failure (no state change), or a
 * positive HOSTMSG_* class for a benign no-op (overflow drop / orphan event /
 * idempotent re-seat).
 */
int32_t controls_host_message(uint32_t ptr, uint32_t len) {
  uint8_t *buf = malloc(len ? len : 1u);
  if (!buf)
    return HOSTMSG_ERR_DECODE;
  memcpy(buf, (const uint8_t *)(uintptr_t)ptr, len);
  ui_HostToWasm msg = ui_HostToWasm_init_zero;
  pb_istream_t stream = pb_istream_from_buffer(buf, len);
  bool ok = pb_decode(&stream, ui_HostToWasm_fields, &msg);
  free(buf);
  if (!ok) {
    LOG_WARN("host_message decode failed: %s", PB_GET_ERROR(&stream));
    return HOSTMSG_ERR_DECODE;
  }
  /* Fail-fast version guard (§3): no migration branch. */
  if (msg.version != UI_INPUT_SCHEMA_VERSION) {
    LOG_WARN("host_message version mismatch: got %u, expected %u", msg.version,
             UI_INPUT_SCHEMA_VERSION);
    return HOSTMSG_ERR_VERSION;
  }
  switch (msg.which_event) {
  case ui_HostToWasm_pointer_tag:
    return handle_pointer(&msg.event.pointer);
  case ui_HostToWasm_lifecycle_tag:
    return handle_lifecycle(&msg.event.lifecycle);
  default:
    return HOSTMSG_ERR_NO_EVENT;
  }
}
/* Stale-pointer GC (§8): sweep slots whose last event_time is older than the
 * staleness window and force-release them through the CANCEL path — the only
 * recovery from a dropped UP/CANCEL. Called from controls_tick with the latest
 * event_time as `now` (the FSM is event-clocked, not render-clocked). */
static void pointer_gc_sweep(void) {
  uint64_t now = g_last_event_time;
  if (now == 0)
    return; /* no pointer event seen yet */
  bool swept = false;
  for (int i = 0; i < GESTURE_MAX_POINTERS_TABLE; i++) {
    if (g_pointers[i].active &&
        now - g_pointers[i].event_time > POINTER_STALE_MS) {
      LOG_WARN("stale pointer id=%u (age %llu ms) — force-release",
               g_pointers[i].pointer_id,
               (unsigned long long)(now - g_pointers[i].event_time));
      pointer_release_cancel(&g_pointers[i]);
      swept = true;
    }
  }
  if (swept)
    indev_sync_from_table();
}
/* ── Pointer-pipeline buffer readback (harness-only, additive) ──────────────
 * R5 will drain g_decisions into cmd-out; until then these exports let the
 * harness assert the ROUTING contract — that VIDEO-owned pointers reach the
 * FSM (decisions buffered) while LVGL-owned ones do not. No proto/LVGL
 * involvement; the host reads the gesture_decision_t array back the same way
 * as the gesture_test ABI. */
/* Count of buffered gesture decisions (the R5 drain queue depth). */
uint32_t controls_pointer_decisions_count(void) { return g_decision_count; }
/* Count of active pointer-table slots (the table-invariant oracle: overflow
 * drop, orphan no-op, idempotent re-seat, GC sweep, lifecycle flush). */
uint32_t controls_pointer_active_count(void) {
  uint32_t n = 0;
  for (int i = 0; i < GESTURE_MAX_POINTERS_TABLE; i++) {
    if (g_pointers[i].active)
      n++;
  }
  return n;
}
/* Pointer to the buffered gesture_decision_t array (g_decision_count entries).
 */
uint32_t controls_pointer_decisions_ptr(void) {
  return (uint32_t)(uintptr_t)g_decisions;
}
/* Clear the buffered decisions (a test reset between assertions). */
void controls_pointer_decisions_clear(void) { g_decision_count = 0; }
/* Harness-only: probe cmd_patch_emit's slot-bounds guard (R5b memory-safety
 * regression). Builds a one-patch cmd_spec_t with the caller's slot over a
 * `template_len`-byte zeroed template, then emits. Returns cmd_patch_emit's rc:
 * 0 when the slot is accepted (host_command fired — the harness sees one
 * captured command), -1 when rejected (no host_command). An out-of-bounds slot
 * — including a crafted byte_offset near UINT32_MAX whose `+byte_width` WRAPS —
 * MUST return -1 and emit NOTHING, never an OOB write past the scratch buffer.
 */
int32_t controls_cmd_patch_probe(uint32_t byte_offset, uint32_t byte_width,
                                 uint32_t template_len) {
  cmd_spec_t spec;
  memset(&spec, 0, sizeof(spec));
  spec.present = true;
  spec.template_len = template_len > CMD_PATCH_TEMPLATE_CAP
                          ? CMD_PATCH_TEMPLATE_CAP
                          : template_len;
  spec.patch_count = 1;
  spec.patches[0].byte_offset = byte_offset;
  spec.patches[0].byte_width = byte_width;
  spec.patches[0].kind = CMD_PATCH_KIND_NDC_X;
  spec.patches[0].wire_scale = 1;
  return cmd_patch_emit(&spec, 0.5, 0.0, 0);
}
/* Harness-only: feed a CRAFTED CmdSpec `.pb` (linear-memory ptr+len) through
 * the untrusted decode boundary (nanopb + cmd_spec_copy_from_proto). The
 * crafted-.pb complement to controls_cmd_patch_probe above, which hand-builds
 * the cmd_spec_t and so bypasses nanopb decode + the copy guard entirely.
 * Returns 0 accepted, -1 the copy rejected the slot bounds (an
 * overflowing/wrapping byte_offset + byte_width), -2 nanopb rejected the bytes
 * (root_template past the 128-byte cap, or > 2 patches). Delegates to
 * cmd_spec_decode_probe (renderer.c owns the static copy guard + the nanopb
 * machinery). */
int32_t controls_cmd_spec_decode_probe(uint32_t ptr, uint32_t len) {
  return cmd_spec_decode_probe((const uint8_t *)(uintptr_t)ptr, len);
}
/* ── Keyboard / clipboard ABI (additive) ───────────────────────────────────
 * Consumed by the in-fleet native host and wasmtime harness. No
 * wire/proto change — keys ride lv_key_t values and Unicode codepoints. */
/**
 * Enqueue a key event for the keypad indev. `key` is an lv_key_t control
 * value (lvgl/src/core/lv_group.h) or a printable Unicode codepoint;
 * `pressed` is 1 for press, 0 for release. Returns -1 when the queue is
 * full (the event is dropped loudly).
 */
int32_t controls_key_event(uint32_t key, uint32_t pressed) {
  uint32_t next = (key_q_tail + 1u) % KEY_QUEUE_CAP;
  if (next == key_q_head) {
    LOG_WARN("key queue full — dropping key %u (pressed=%u)", key, pressed);
    return -1;
  }
  key_queue[key_q_tail].key = key;
  key_queue[key_q_tail].pressed = pressed;
  key_q_tail = next;
  return 0;
}
/* Paste cap: the bounded staging buffer for controls_text_input. 4 KB of
 * UTF-8 is far beyond any plausible form field; larger pastes are rejected
 * loudly rather than truncated silently. */
#define TEXT_INPUT_CAP 4096u
/* The group-focused widget when it is exactly a textarea, else NULL.
 * Exact class check on purpose: lv_spinbox derives from lv_textarea but
 * owns its text as a formatted digit buffer — free-text paste into it (or
 * copying that buffer) would corrupt/mislead. */
static lv_obj_t *focused_textarea(void) {
  if (!input_group)
    return NULL;
  lv_obj_t *focused = lv_group_get_focused(input_group);
  if (!focused || !lv_obj_check_type(focused, &lv_textarea_class))
    return NULL;
  return focused;
}
/**
 * Insert UTF-8 text into the group-focused textarea (the paste path).
 * `ptr`/`len` reference WASM linear memory; the text need not be
 * NUL-terminated. Returns 0 on success (or empty input), -1 when the text
 * exceeds TEXT_INPUT_CAP or no textarea is focused.
 */
int32_t controls_text_input(uint32_t ptr, uint32_t len) {
  static char buf[TEXT_INPUT_CAP];
  if (len == 0)
    return 0;
  if (len >= TEXT_INPUT_CAP) {
    LOG_WARN("text input rejected: %u bytes exceeds cap %u", len,
             TEXT_INPUT_CAP);
    return -1;
  }
  lv_obj_t *ta = focused_textarea();
  if (!ta) {
    LOG_WARN("text input dropped: no textarea focused");
    return -1;
  }
  memcpy(buf, (const char *)(uintptr_t)ptr, len);
  buf[len] = '\0';
  lv_textarea_add_text(ta, buf);
  return 0;
}
/**
 * NUL-terminated text of the group-focused textarea (the copy path), or 0
 * (NULL) when no textarea is focused. v1 copies the WHOLE field — LVGL's
 * textarea has no selection API. lv_textarea_get_text returns the real
 * text even in password mode, which is what a copy should carry.
 */
uint32_t controls_get_focused_text(void) {
  lv_obj_t *ta = focused_textarea();
  if (!ta)
    return 0;
  return (uint32_t)(uintptr_t)lv_textarea_get_text(ta);
}
int32_t controls_tick(uint32_t elapsed_ms) {
  lv_tick_inc(elapsed_ms);
  flush_happened = 0;
  dirty_valid = 0;
  /* Stale-pointer GC BEFORE the timer handler: a force-released slot must drop
   * its indev press before indev_read_cb polls this tick (§8). */
  pointer_gc_sweep();
  lv_timer_handler();
  /* Host-proxy report sweep — AFTER lv_timer_handler so coords are
   * post-layout; change-guarded, at most one report per proxy per tick. */
  proxy_report_sweep();
  /* R5b cmd-out: drain the buffered gesture decisions into host_command
   * (per decision, match its GestureSpec.kind + emit the patched cmd). After
   * proxy_report_sweep so a gesture's geometry report and its command share a
   * tick ordering; the buffer is cleared by the drain. */
  drain_gesture_decisions();
  return flush_happened;
}
uint32_t controls_get_framebuffer(void) {
  return (uint32_t)(uintptr_t)framebuffer;
}
/* ── ABI self-description getters (see CONTROLS_ABI_VERSION) ────────────────
 * Read by the host at module load/reload to validate the framebuffer layout
 * contract before it reads pixels out of linear memory. */
uint32_t controls_abi_version(void) { return CONTROLS_ABI_VERSION; }
uint32_t controls_fb_format(void) { return CONTROLS_FB_FMT_RGBA8888; }
uint32_t controls_fb_width(void) { return fb_width; }
uint32_t controls_fb_height(void) { return fb_height; }
uint32_t controls_fb_bpp(void) { return 4u; }
/* ── Semantic tree dump (controls_dump_tree) ───────────────────────────────
 * Walks the active screen and emits a compact JSON tree (type + resolved
 * coords + label text + hidden/checked + layout-defect flags + children, plus
 * bp/theme_dark at the root) into a static buffer; the host reads the
 * NUL-terminated string from the returned pointer. This is the PRIMARY oracle
 * of the visual differential: the reference path and the proto path each dump
 * a tree and a JSON-Patch diff explains any divergence. The layout-defect
 * flags (clipped / overflow / scrollable_overflow / text_truncated) are
 * derived from resolved geometry, so both oracles agree on them. Geometry is
 * what alignment/layout resolves to; resolved styles are added in a later
 * phase. */
#define TREE_BUF_SIZE 131072u
static char tree_buf[TREE_BUF_SIZE];
/* Bounded JSON output sink — the ONE appending/escaping machinery, shared by
 * the tree dump (tree_buf) and the host_event envelope emitter (its own small
 * buffer). `truncated` records that an append was DROPPED for space: the tree
 * dump tolerates it (the host sees truncated JSON, as always), the envelope
 * emitter REFUSES to send (a clipped envelope is not valid JSON). */
typedef struct {
  char *buf;
  uint32_t cap;
  uint32_t pos;
  bool truncated;
} json_out_t;
static json_out_t tree_out = {tree_buf, TREE_BUF_SIZE, 0, false};
static void json_append(json_out_t *out, const char *s) {
  if (out->pos >= out->cap)
    return; /* already full — pos is invariant-bounded below */
  uint32_t len = (uint32_t)strlen(s);
  uint32_t avail = out->cap - 1u - out->pos; /* room before the NUL */
  if (len > avail) {
    out->truncated = true;
    return; /* bounded: stop appending (consumer decides what a clip means) */
  }
  memcpy(out->buf + out->pos, s, len);
  out->pos += len;
  out->buf[out->pos] = '\0';
}
/* Append a JSON-escaped string value (no quotes added), capping the input at
 * `max_chars`. esc[] is sized for the LARGEST cap any caller uses
 * (UI_EVENT_NAME_BUF × the 6-byte \u00xx worst case + NUL), so escaping is TOTAL
 * for every in-cap input — a control-char-dense name escapes fully, never clips
 * mid-name. The event tag passes UI_EVENT_NAME_BUF (the full dotted command-id,
 * one home shared with the decode + cache buffers, renderer.h); the trigger is a
 * short enum name; the tree dump keeps its compact 64-char cap. */
static void json_append_str(json_out_t *out, const char *s,
                            uint32_t max_chars) {
  char esc[UI_EVENT_NAME_BUF * 6u + 8u];
  uint32_t o = 0;
  for (uint32_t i = 0; s[i] != '\0' && i < max_chars && o + 8u < sizeof(esc);
       i++) {
    unsigned char c = (unsigned char)s[i];
    if (c == '"' || c == '\\') {
      esc[o++] = '\\';
      esc[o++] = (char)c;
    } else if (c < 0x20u) {
      o += (uint32_t)snprintf(esc + o, sizeof(esc) - o, "\\u%04x", (unsigned)c);
    } else {
      esc[o++] = (char)c;
    }
  }
  esc[o] = '\0';
  json_append(out, esc);
}
static void tree_append(const char *s) { json_append(&tree_out, s); }
static void tree_append_json_str(const char *s) {
  json_append_str(&tree_out, s, 64u); /* compact dump cap */
}
/* ── UI-event envelope (host_event — the named-event lane) ──────────────────
 * Build + relay the CLOSED envelope JSON for a fired EventBinding with a
 * nonempty name:
 *   {"v":1,"tag":...,"origin":...,"event":...,"seq":...,"value":...}
 * Exactly these six keys, in this order — the v1 contract (protogen carries
 * the JSON Schema + golden vectors; the host validates at its membrane).
 * `seq` counts EMITTED envelopes: consecutive from 1 per module INSTANCE
 * (deliberately not reset by controls_load_ui/controls_destroy — a session's
 * envelope stream stays strictly monotonic across screen reloads, so a host
 * gap-check spans the whole session). The stack buffer provably fits the worst
 * case: the tag escapes to at most UI_EVENT_NAME_BUF × 6 = 768 bytes, the
 * trigger (a fixed enum name, cap UI_EVENT_TRIGGER_CHARS) to ≤ 96, plus ~90 bytes of fixed
 * syntax/numbers = ≤ 954 < 1024; `truncated` is the belt — a clipped envelope is
 * REFUSED (-1, nothing sent), never invalid JSON. */
#define EVENT_ENVELOPE_BUF_SIZE 1024u
static uint32_t host_event_seq;
int32_t controls_emit_host_event(const char *tag, const char *trigger,
                                 uint32_t origin_uid, int32_t value) {
  if (!tag || tag[0] == '\0')
    return 0; /* unnamed events have no envelope — nothing to send */
  char buf[EVENT_ENVELOPE_BUF_SIZE];
  json_out_t out = {buf, sizeof(buf), 0, false};
  char num[48];
  json_append(&out, "{\"v\":1,\"tag\":\"");
  json_append_str(&out, tag,
                  UI_EVENT_NAME_BUF); /* the full dotted command-id */
  (void)snprintf(num, sizeof(num), "\",\"origin\":%u,\"event\":\"",
                 (unsigned)origin_uid);
  json_append(&out, num);
  json_append_str(&out, trigger ? trigger : "",
                  UI_EVENT_TRIGGER_CHARS); /* short enum name */
  (void)snprintf(num, sizeof(num), "\",\"seq\":%u,\"value\":%d}",
                 (unsigned)(host_event_seq + 1u), (int)value);
  json_append(&out, num);
  if (out.truncated) {
    LOG_ERROR("host_event envelope overflow (tag '%.16s') — refusing a "
              "clipped envelope",
              tag);
    return -1;
  }
  host_event_seq++;
  return host_event((uint32_t)(uintptr_t)buf, out.pos);
}
/* clipped: a child whose resolved box escapes the parent's content area is
 * clipped by it. Geometry only — fires even on scrollable parents, where
 * scrollable_overflow is the "fine, it scrolls" companion signal. */
static bool obj_clipped(const lv_obj_t *obj, const lv_area_t *coords) {
  const lv_obj_t *parent = lv_obj_get_parent(obj);
  if (parent == NULL)
    return false;
  lv_area_t pc;
  lv_obj_get_content_coords(parent, &pc);
  if (coords->x1 < pc.x1 || coords->y1 < pc.y1)
    return true;
  if (coords->x2 > pc.x2 || coords->y2 > pc.y2)
    return true;
  return false;
}
/* Content extends beyond the visible content box in some direction. Uses
 * LVGL's own scroll extent (the distance you would scroll to reveal the hidden
 * content) — already padding/border-aware, so it does not false-fire on a
 * child that merely sits near the padded edge. Whether the overflow is a defect
 * or a designed scroller is decided by the SCROLLABLE flag at the emit site:
 * overflow (clipped away) vs scrollable_overflow (reachable by scroll). */
static bool obj_content_overflows(const lv_obj_t *obj) {
  if (lv_obj_get_scroll_top(obj) > 0 || lv_obj_get_scroll_bottom(obj) > 0)
    return true;
  if (lv_obj_get_scroll_left(obj) > 0 || lv_obj_get_scroll_right(obj) > 0)
    return true;
  return false;
}
/* text_clipped: a CLIP-long-mode label whose text does not fit its content
 * box. CLIP keeps the box size and clips the glyphs out of it (no ellipsis,
 * unlike DOTS — so dot_begin never fires), which is the silent-truncation
 * case `text_truncated` cannot catch. Measure the natural (un-wrapped) text
 * extent with max_width = LV_COORD_MAX + LV_TEXT_FLAG_EXPAND: passing the box
 * width instead would wrap the text to fit and the comparison would invert
 * (a too-long line would measure as taller-but-narrow and read as "fits").
 * Both dimensions are checked: a single long line overflows in width, a
 * forced-tall block (embedded newlines) overflows in height. */
static bool label_text_clipped(const lv_obj_t *obj) {
  if (!lv_obj_check_type(obj, &lv_label_class))
    return false;
  if (lv_label_get_long_mode(obj) != LV_LABEL_LONG_MODE_CLIP)
    return false;
  const char *text = lv_label_get_text(obj);
  if (text == NULL || text[0] == '\0')
    return false;
  const lv_font_t *font = lv_obj_get_style_text_font(obj, LV_PART_MAIN);
  if (font == NULL)
    return false;
  int32_t letter_space = lv_obj_get_style_text_letter_space(obj, LV_PART_MAIN);
  int32_t line_space = lv_obj_get_style_text_line_space(obj, LV_PART_MAIN);
  lv_point_t size = {0, 0};
  lv_text_get_size(&size, text, font, letter_space, line_space, LV_COORD_MAX,
                   LV_TEXT_FLAG_EXPAND);
  lv_area_t cc;
  lv_obj_get_content_coords(obj, &cc);
  int32_t cw = lv_area_get_width(&cc);
  int32_t ch = lv_area_get_height(&cc);
  if (size.x > cw)
    return true;
  if (size.y > ch)
    return true;
  return false;
}
/* A scroll container reveals offscreen children by scrolling, so an object
 * sitting outside the display because a scrollable ancestor has it scrolled
 * away (or scroll-snaps to it — tabview inactive pages) is DESIGNED, not a
 * defect. Walk ancestors: any SCROLLABLE one with live scroll extent, or any
 * scroll-snap container whose child is SNAPPABLE, designs the off-display
 * placement. This is the exclusion that keeps `offscreen` off the tabview
 * inactive pages (see test_inactive_tab_content_offscreen). */
static bool obj_in_scroll_region(const lv_obj_t *obj) {
  const lv_obj_t *child = obj;
  const lv_obj_t *parent = lv_obj_get_parent(child);
  while (parent != NULL) {
    bool snappable = lv_obj_has_flag(child, LV_OBJ_FLAG_SNAPPABLE);
    bool snap_x = lv_obj_get_scroll_snap_x(parent) != LV_SCROLL_SNAP_NONE;
    bool snap_y = lv_obj_get_scroll_snap_y(parent) != LV_SCROLL_SNAP_NONE;
    if (snappable && (snap_x || snap_y))
      return true;
    if (lv_obj_has_flag(parent, LV_OBJ_FLAG_SCROLLABLE) &&
        obj_content_overflows(parent))
      return true;
    child = parent;
    parent = lv_obj_get_parent(child);
  }
  return false;
}
/* offscreen: the resolved object box lies (partly or wholly) outside the
 * display rectangle. Geometry only — a defect when the layout pushes a widget
 * past the screen edge, EXCLUDING children a scroll container designs off the
 * display (obj_in_scroll_region). */
static bool obj_offscreen(const lv_obj_t *obj, const lv_area_t *coords) {
  lv_display_t *disp = lv_display_get_default();
  if (disp == NULL)
    return false;
  int32_t hor = lv_display_get_horizontal_resolution(disp);
  int32_t ver = lv_display_get_vertical_resolution(disp);
  bool outside = false;
  if (coords->x1 < 0 || coords->y1 < 0)
    outside = true;
  if (coords->x2 > hor - 1 || coords->y2 > ver - 1)
    outside = true;
  if (!outside)
    return false;
  if (obj_in_scroll_region(obj))
    return false;
  return true;
}
/* squished: a flex/grid child collapsed below the size it asked for. Two
 * cases, both relative to the PARENT's layout (a min-size or grow request is
 * only meaningful when a layout resolves it): the resolved w/h fell under the
 * object's own min_width/min_height, OR it requested flex_grow but resolved to
 * a near-zero extent (the track had no room to give). Reported only when the
 * parent is FLEX or GRID, so a free-positioned widget never trips it. */
static bool obj_squished(const lv_obj_t *obj, const lv_area_t *coords) {
  const lv_obj_t *parent = lv_obj_get_parent(obj);
  if (parent == NULL)
    return false;
  uint16_t layout = lv_obj_get_style_layout(parent, LV_PART_MAIN);
  if (layout != LV_LAYOUT_FLEX && layout != LV_LAYOUT_GRID)
    return false;
  int32_t w = lv_area_get_width(coords);
  int32_t h = lv_area_get_height(coords);
  int32_t min_w = lv_obj_get_style_min_width(obj, LV_PART_MAIN);
  int32_t min_h = lv_obj_get_style_min_height(obj, LV_PART_MAIN);
  if (min_w > 0 && w < min_w)
    return true;
  if (min_h > 0 && h < min_h)
    return true;
  uint8_t grow = lv_obj_get_style_flex_grow(obj, LV_PART_MAIN);
  if (grow > 0 && w <= 0)
    return true;
  if (grow > 0 && h <= 0)
    return true;
  return false;
}
static void dump_obj(const lv_obj_t *obj, bool is_root) {
  lv_area_t a;
  lv_obj_get_coords(obj, &a);
  const lv_obj_class_t *cls = lv_obj_get_class(obj);
  const char *name = (cls && cls->name) ? cls->name : "unknown";
  char hdr[160];
  (void)snprintf(hdr, sizeof(hdr), "{\"type\":\"%s\",\"coords\":[%d,%d,%d,%d]",
                 name, (int)a.x1, (int)a.y1, (int)a.x2, (int)a.y2);
  tree_append(hdr);
  /* Ancestor-clip visibility: how many of this node's pixels survive every
   * ancestor's clip + the display bounds (lv_obj_area_is_visible — honors
   * OVERFLOW_VISIBLE, transforms, HIDDEN). Emitted ONLY when vis < total
   * (the emit-only-when-it-differs convention): a fully-visible node stays
   * silent, a clipped one reports its surviving pixel count, and 0 is the
   * occlusion signal the devcard invariant lane keys on. */
  {
    lv_area_t vis = a;
    int32_t total = lv_area_get_width(&a) * lv_area_get_height(&a);
    int32_t vpx = 0;
    if (lv_obj_area_is_visible((lv_obj_t *)obj, &vis))
      vpx = lv_area_get_width(&vis) * lv_area_get_height(&vis);
    if (vpx < total) {
      char vbuf[32];
      (void)snprintf(vbuf, sizeof(vbuf), ",\"vis_px\":%d", (int)vpx);
      tree_append(vbuf);
    }
  }
  /* Root carries the active breakpoint tier (0..3) + theme, so the layout
   * gate asserts "renders correctly at THIS tier", not merely "differs". */
  if (is_root) {
    char rb[48];
    (void)snprintf(rb, sizeof(rb), ",\"bp\":%d,\"theme_dark\":%d",
                   (int)current_bp, (int)current_theme_dark);
    tree_append(rb);
  }
  /* Codegen-assigned node identity (tree patching) — mirrored into
   * user_data by the renderer; emitted only when assigned, so
   * renderer-internal objects (and the reference module, which has no
   * codegen identity) stay uid-free. */
  uint32_t uid = (uint32_t)(uintptr_t)lv_obj_get_user_data((lv_obj_t *)obj);
  if (uid != 0) {
    char ubuf[24];
    (void)snprintf(ubuf, sizeof(ubuf), ",\"uid\":%u", (unsigned)uid);
    tree_append(ubuf);
  }
  if (lv_obj_check_type(obj, &lv_label_class)) {
    tree_append(",\"text\":\"");
    tree_append_json_str(lv_label_get_text(obj));
    tree_append("\"");
    /* DOTS long-mode truncation: dot_begin holds the ellipsis byte offset
       * and stays at its 0xFFFFFFFF sentinel (LV_LABEL_DOT_BEGIN_INV, a
       * .c-private macro — the literal is compared) until the label actually
       * ellipsized. CLIP-mode truncation needs text measurement and is left
       * to a later phase. */
    if (((const lv_label_t *)obj)->dot_begin != 0xFFFFFFFFu)
      tree_append(",\"text_truncated\":true");
    /* CLIP long-mode silently clips glyphs (no dot_begin); measure the
       * text against the content box (V-C3). */
    if (label_text_clipped(obj))
      tree_append(",\"text_clipped\":true");
  }
  /* Layout-defect flags, emitted only when set (the hidden/checked
   * convention). Derived from resolved geometry, so both oracles agree. */
  if (!is_root && obj_clipped(obj, &a))
    tree_append(",\"clipped\":true");
  bool overflows = obj_content_overflows(obj);
  bool scrollable = lv_obj_has_flag(obj, LV_OBJ_FLAG_SCROLLABLE);
  if (overflows && !scrollable)
    tree_append(",\"overflow\":true");
  if (overflows && scrollable)
    tree_append(",\"scrollable_overflow\":true");
  /* V-C3 geometry/measurement flags (emitted only when set). */
  if (!is_root && obj_offscreen(obj, &a))
    tree_append(",\"offscreen\":true");
  if (!is_root && obj_squished(obj, &a))
    tree_append(",\"squished\":true");
  /* Emitted only when set, so visible nodes stay compact and the
   * tree differ sees show-when / visibility state directly. */
  if (lv_obj_has_flag(obj, LV_OBJ_FLAG_HIDDEN))
    tree_append(",\"hidden\":true");
  /* Emitted only when set: the checked_when / radio-group oracle. */
  if (lv_obj_has_state(obj, LV_STATE_CHECKED))
    tree_append(",\"checked\":true");
  /* Emitted only when set: the enabled_when oracle (the reactive DISABLED
   * sibling of the checked line above). */
  if (lv_obj_has_state(obj, LV_STATE_DISABLED))
    tree_append(",\"disabled\":true");
  tree_append(",\"children\":[");
  uint32_t n = lv_obj_get_child_count(obj);
  for (uint32_t i = 0; i < n; i++) {
    if (i > 0)
      tree_append(",");
    dump_obj(lv_obj_get_child(obj, i), false);
  }
  tree_append("]}");
}
/* Dump the active screen's widget tree as JSON; returns a pointer to a static
 * NUL-terminated buffer in linear memory (host reads until NUL). */
uint32_t controls_dump_tree(void) {
  tree_out.pos = 0;
  tree_out.truncated = false;
  tree_buf[0] = '\0';
  dump_obj(lv_screen_active(), true);
  if (tree_out.truncated) {
    /* Fail LOUD: a flooded dump is structurally cut JSON, and the buffer is
     * full so nothing can be appended — instead the sentinel OVERWRITES the
     * tail, so a truncated dump always ENDS with ,"truncated":true. The host
     * checks that suffix BEFORE parsing; without it a cut dump reads as a
     * well-formed-looking tree with subtrees silently missing. */
    static const char sentinel[] = ",\"truncated\":true";
    const uint32_t slen = (uint32_t)(sizeof sentinel - 1u);
    uint32_t at = tree_out.pos < (TREE_BUF_SIZE - 1u - slen)
                      ? tree_out.pos
                      : (TREE_BUF_SIZE - 1u - slen);
    memcpy(tree_buf + at, sentinel, slen);
    tree_buf[at + slen] = '\0';
    tree_out.pos = at + slen;
  }
  return (uint32_t)(uintptr_t)tree_buf;
}
/**
 * Read and reset accumulated dirty rect.
 * Returns 1 if dirty (bounds written to dirty_rect_out), 0 if clean.
 */
int32_t controls_get_dirty_rect(void) {
  if (!dirty_valid)
    return 0;
  dirty_rect_out[0] = dirty_x1;
  dirty_rect_out[1] = dirty_y1;
  dirty_rect_out[2] = dirty_x2;
  dirty_rect_out[3] = dirty_y2;
  dirty_valid = 0;
  return 1;
}
uint32_t controls_get_dirty_rect_ptr(void) {
  return (uint32_t)(uintptr_t)dirty_rect_out;
}
int32_t controls_set_breakpoint(int32_t bp) {
  if (bp < 0)
    current_bp = 0;
  else if (bp > 3)
    current_bp = 3;
  else
    current_bp = bp;
  return update_composite();
}
int32_t controls_set_theme_dark(int32_t dark) { return set_theme_dark(dark); }
/* Select the theme family (0=asgard default / 1=vanilla / 2=stock). Theme
 * styles attach at widget-create time, so a change re-applies the theme and
 * rebuilds from the cached .pb — same contract as a composite change
 * (update_composite): stale/absent cache means the host must resend. An
 * out-of-range family is rejected loudly (fail-fast), never clamped. */
int32_t controls_set_theme_family(int32_t family) {
  if (family < 0 || family > 2)
    return -1;
  if (family == current_theme_family)
    return 0;
  current_theme_family = family;
  apply_default_theme();
  if (last_ui_data && !last_ui_stale) {
    if (rebuild_ui() != 0) {
      last_ui_stale = 1;
      LOG_WARN("family rebuild failed — host must send the full .pb");
      return CONTROLS_NEEDS_FULL_RELOAD;
    }
  } else if (last_ui_stale) {
    LOG_WARN("family change but cached .pb is stale/unavailable — "
             "host must send the current full .pb");
    return CONTROLS_NEEDS_FULL_RELOAD;
  }
  return 0;
}
int32_t controls_set_dpi(int32_t dpi) {
  current_dpi = dpi;
  lv_display_t *disp = lv_display_get_default();
  if (disp) {
    lv_display_set_dpi(disp, dpi);
  }
  return 0;
}
int32_t controls_resize(uint32_t width, uint32_t height) {
  if (width == fb_width && height == fb_height)
    return 0;
  if (width == 0 || height == 0 || width > CONTROLS_MAX_DIM ||
      height > CONTROLS_MAX_DIM)
    return -1;
  uint32_t fb_size = width * height * 4;
  uint32_t draw_rows = 64;
  uint32_t db_size = width * draw_rows * 4;
  /* Allocate the NEW set BEFORE freeing the old: a failed resize must leave the
   * live display intact. The old free-first path (to halve peak memory) left
   * the display pointing at freed draw buffers on an alloc failure → a
   * use-after-free on the next controls_tick render. Correctness over the 2x
   * transient memory. */
  uint8_t *new_fb = calloc(fb_size, 1);
  uint8_t *new_db1 = malloc(db_size);
  uint8_t *new_db2 = malloc(db_size);
  if (!new_fb || !new_db1 || !new_db2) {
    free(new_fb);
    free(new_db1);
    free(new_db2);
    /* Old buffers + display untouched — still renderable at the old size. */
    return -1;
  }
  free(framebuffer);
  free(draw_buf1);
  free(draw_buf2);
  framebuffer = new_fb;
  draw_buf1 = new_db1;
  draw_buf2 = new_db2;
  fb_width = width;
  fb_height = height;
  lv_display_t *disp = lv_display_get_default();
  lv_display_set_resolution(disp, (int32_t)width, (int32_t)height);
  lv_display_set_buffers(disp, draw_buf1, draw_buf2, db_size,
                         LV_DISPLAY_RENDER_MODE_PARTIAL);
  return 0;
}
/* ── Gesture FSM test ABI (harness-only, additive) ─────────────────────────
 * Thin wrappers driving the pure gesture.c FSM DIRECTLY (a SEPARATE recognizer
 * from g_gesture, the one the real controls_host_message pointer pipeline
 * feeds). Exported only so the wasmtime harness can exercise the ported
 * gesture-core.ts state machine 1:1 without the table/hit-test routing. No
 * LVGL/proto involvement; decisions land in a static buffer the host reads
 * back via gesture_decisions_ptr().
 *
 * Wire layout (host writes/reads matching structs; double => 8-byte aligned,
 * so each carries trailing/interior pad to a 32-byte stride):
 *   feed event       : { int32 op; int32 pointer_id; double x; double y;
 *                        int32 t_ms; int32 _pad; }            (32-byte stride)
 *   gesture_decision : { int32 kind; <pad4> double x; double y; int32 delta;
 *                        <pad4> }                             (32-byte stride)
 *   op uses the GESTURE_OP_* values above (0 down, 1 move, 2 up, 3 wheel
 *   [x = deltaY], 4 cancel). */
typedef struct {
  int32_t op;
  int32_t pointer_id;
  double x;
  double y;
  int32_t t_ms;
  int32_t pad;
} gesture_feed_event_t;
static gesture_recognizer_t gesture_test_state;
static gesture_decision_t gesture_test_decisions[GESTURE_MAX_DECISIONS];
static int32_t gesture_test_decision_count;
/* Reset the FSM and the captured-decision buffer to the initial state. */
void gesture_test_reset(void) {
  gesture_reset(&gesture_test_state);
  gesture_test_decision_count = 0;
}
/**
 * Feed a pointer/wheel event sequence. `events_ptr` points to `count`
 * gesture_feed_event_t in linear memory; the decisions emitted by the LAST
 * event are captured into gesture_test_decisions (the per-call output, exactly
 * as the TS handlers return). Returns the decision count of the last event.
 */
int32_t gesture_test_feed(uint32_t events_ptr, uint32_t count) {
  const gesture_feed_event_t *events =
      (const gesture_feed_event_t *)(uintptr_t)events_ptr;
  gesture_test_decision_count = 0;
  for (uint32_t i = 0; i < count; i++) {
    const gesture_feed_event_t *e = &events[i];
    gesture_sample_t s = {
        .pointer_id = e->pointer_id, .x = e->x, .y = e->y, .t_ms = e->t_ms};
    switch (e->op) {
    case GESTURE_OP_DOWN:
      gesture_test_decision_count =
          gesture_on_down(&gesture_test_state, &s, gesture_test_decisions);
      break;
    case GESTURE_OP_MOVE:
      gesture_test_decision_count =
          gesture_on_move(&gesture_test_state, &s, gesture_test_decisions);
      break;
    case GESTURE_OP_UP:
      gesture_test_decision_count =
          gesture_on_up(&gesture_test_state, &s, gesture_test_decisions);
      break;
    case GESTURE_OP_WHEEL:
      gesture_test_decision_count =
          gesture_on_wheel(&gesture_test_state, e->x, gesture_test_decisions);
      break;
    case GESTURE_OP_CANCEL:
      gesture_test_decision_count =
          gesture_on_cancel(&gesture_test_state, &s, gesture_test_decisions);
      break;
    default:
      break;
    }
  }
  return gesture_test_decision_count;
}
/* Pointer to the captured gesture_decision_t array (last event's output). */
uint32_t gesture_decisions_ptr(void) {
  return (uint32_t)(uintptr_t)gesture_test_decisions;
}
int32_t controls_destroy(void) {
  /* Idempotency guard: `display` is the init/destroy sentinel (NULL before
     controls_init, NULLed below). Without it a SECOND consecutive destroy
     reads lv_screen_active() off a NULL display — in wasm that is valid
     linear-memory offset-0 garbage (no fault), and the teardown walk spins
     forever in a cyclic list (root-caused 2026-07-19: jview's explicit
     destroy + destroy-on-Drop double-call burned CPU unbounded). Re-entry
     is loud-but-successful: warn and report already-destroyed. */
  if (!display) {
    LOG_WARN("controls_destroy re-entry (already destroyed) — no-op");
    return 0;
  }
  if (input_group)
    lv_group_remove_all_objs(input_group); /* No refocus during teardown */
  lv_obj_clean(lv_screen_active()); /* Widgets first (detach observers) */
  renderer_cleanup();               /* Then subjects + style pool */
  /* Then EVERYTHING ELSE controls_init created, in reverse dependency order.
     This function's contract is to undo controls_init, and it is exported so a
     host MAY re-init the same instance afterwards — so each unpaired resource
     is a per-cycle leak on exactly the path the export invites. Both hosts
     happen to drop the whole WASM instance today (linear memory goes with it),
     which is the only reason the omissions were invisible.

     - svg_decoder_deinit: unregisters the LVGL image decoder
       (lv_image_decoder_create is a bare list-insert with NO dedupe, so a
       second controls_init would register a DUPLICATE decoder with the same
       info_cb) and drops the ThorVG engine's refcount (tvg_engine_init is
       refcounted — the hazard is a count that never reaches zero, not a
       double init).
     - the indevs: controls_init does not store them, so they are walked.
       Deleted BEFORE the group they reference.
     - the group and the display: a leaked display is the worst of these — its
       refresh timer stays in the global timer list, still firing on every
       lv_timer_handler tick against a re-init's new state.
     Deleting the display also detaches it from draw_buf1/draw_buf2, so the
     frees below can never race a late flush into freed memory. */
  svg_decoder_deinit();
  lv_indev_t *indev = NULL;
  while ((indev = lv_indev_get_next(NULL)) != NULL)
    lv_indev_delete(indev);
  if (input_group) {
    lv_group_delete(input_group);
    input_group = NULL;
  }
  if (display) {
    lv_display_delete(display);
    display = NULL;
  }
  free(framebuffer);
  framebuffer = NULL;
  free(draw_buf1);
  draw_buf1 = NULL;
  free(draw_buf2);
  draw_buf2 = NULL;
  free(last_ui_data);
  last_ui_data = NULL;
  last_ui_len = 0;
  return 0;
}
