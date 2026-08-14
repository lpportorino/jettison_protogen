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
 *   controls_key_event(key, p)     — Enqueue a keypad key (lv_key_t, Unicode
 *                                    codepoint, or a modifier code >=
 *                                    0x01000000; p=1 press, 0 release)
 *   controls_text_input(ptr, len)  — Insert UTF-8 text into the focused
 *                                    textarea, REPLACING any selection (the
 *                                    paste path; refuses over-cap input)
 *   controls_get_focused_text()    — NUL-terminated text of the WHOLE focused
 *                                    textarea, or NULL (also NULL in password
 *                                    mode)
 *   controls_take_clipboard_request() — Drain the pending clipboard request
 *                                    (0 none, 1 copy, 2 cut, 3 paste)
 *   controls_get_clipboard_text()  — NUL-terminated bytes a copy/cut staged,
 *                                    or NULL (the selection-aware copy path)
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
#include "fonts.h"
/* the compiled B612Mono/Orbitron tables, for the text_font reverse map */
#include "gesture.h"
#include "host_imports.h"
#include "log.h"
#include "lvgl.h"
#include "lvgl/src/core/lv_group_private.h"
/* obj_focus (defocus path) */
#include "lvgl/src/core/lv_obj_class_private.h"
/* lv_obj_class_t.name */
#include "lvgl/src/core/lv_obj_draw_private.h"
/* lv_obj_get_ext_draw_size (the OVERFLOW_VISIBLE descent gate) */
#include "lvgl/src/misc/lv_text_private.h"
/* lv_text_encoded_get_byte_id (selection char index -> UTF-8 byte offset) */
#include "lvgl/src/widgets/label/lv_label_private.h"
/* lv_label_t.dot_begin */
#include "lvgl/src/widgets/slider/lv_slider_private.h"
/* lv_slider_t.{left,right}_knob_area — the knob's DRAWN rect (paint extent) */
#include "palette_observer.h"
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
 * theme-family control without probing).
 *
 * v4: controls_load_ui's nonzero status became CLASSIFIED (LOAD_ERR_* in
 * renderer.h) instead of a bare -1. No export changed and the framebuffer
 * contract is untouched, but the RETURN CONTRACT did, in a way a host can
 * act on: -1 now means specifically ABORTED (decode stopped, tree
 * truncated, and the module has torn the screen down itself), while -2
 * means DEFECTIVE (tree complete and still rendering, some node degraded).
 * A host treating any nonzero as "failed" stays correct — which is why this
 * is a version bump and not a break — but one that wants to distinguish
 * "show the operator nothing" from "show it, flagged" now can, and gates on
 * this version to know the distinction is real rather than assumed.
 *
 * v5: the text field became selection-aware. Two exports joined
 * (controls_take_clipboard_request, controls_get_clipboard_text) and
 * controls_key_event gained a MODIFIER code space at/above 0x01000000 — both
 * additive, and no import joined the required set, so an unchanged host still
 * loads and behaves as before. One behaviour DID change rather than being
 * added, which is why a host must be able to detect this version:
 * controls_get_focused_text now returns NULL for a PASSWORD field instead of
 * its cleartext. A host that showed that value, or put it on a clipboard, was
 * leaking the one thing the field exists to hide; a host that gates on v5
 * knows the refusal is the module's and not an empty field. */
#define CONTROLS_ABI_VERSION 5u
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
/* The gesture-template registry must hold one entry per DEFINED gesture kind,
 * plus one for the second direction of each kind whose decisions carry a step —
 * PINCH and WHEEL, per gesture_decision_t.delta. GESTURE_KIND_ROI is the highest
 * enumerator TODAY, so `+ 1` is the kind COUNT and `+ 3` is that sum.
 *
 * This exists because the bound has already gone stale once, silently: it was
 * derived from the five device gestures of the day, GESTURE_KIND_ROI was then
 * added as a sixth registrable lookup key, and nothing moved the number — a
 * surface binding every kind would have been REFUSED at load with no build,
 * gate or test able to say why.
 *
 * WHAT THIS ASSERT DOES AND DOES NOT CATCH. It catches the bound being LOWERED.
 * It does NOT catch a new gesture kind, and a previous version of this comment
 * claimed it did. The anchor is a NAME: a kind added AFTER GESTURE_KIND_ROI
 * leaves ROI at 6, so the arithmetic is unchanged and the assert stays silently
 * true — the exact staleness the paragraph above says it exists to prevent,
 * recurring one enumerator later. The runtime is not loud either:
 * controls_gesture_specs_set logs a warning and TRUNCATES to the bound, so the
 * dropped templates are a warning in a log rather than a refusal.
 *
 * gesture_kind_bound_guard below is what actually catches a new kind. */
static_assert(CMD_PATCH_MAX_GESTURES >= GESTURE_KIND_ROI + 3,
              "gesture registry must cover every gesture kind plus a second "
              "direction for each stepped kind");
/* ADDING A GESTURE KIND MUST FAIL THE BUILD, and this is what makes it. The
 * switch is exhaustive over gesture_kind_t and carries NO default, so -Wswitch
 * (in -Wall, fatal under -Werror) refuses to compile the moment an enumerator
 * appears that no case names. There is nothing to run and nothing to call: the
 * body is unreachable by design and the function exists only so the compiler
 * has somewhere to check exhaustiveness.
 *
 * When it fires, the fix is not to add a case here and move on. Re-derive
 * CMD_PATCH_MAX_GESTURES first — one entry per kind, plus one more for each
 * kind whose decisions carry a step — then update the assert above, then add
 * the case. The case is the LAST step, because adding it first silences the
 * only thing telling you the bound moved. */
[[maybe_unused]] static void gesture_kind_bound_guard(gesture_kind_t k) {
  switch (k) {
  case GESTURE_KIND_PAN_MOVE:
  case GESTURE_KIND_PAN_END:
  case GESTURE_KIND_TAP:
  case GESTURE_KIND_TRACK:
  case GESTURE_KIND_PINCH:
  case GESTURE_KIND_WHEEL:
  case GESTURE_KIND_ROI:
    break;
  }
}
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
 * the GestureSpec that ANSWERS it — kind equal AND delta_sign admitting the
 * decision's step — and emits the patched cmd via cmd_patch_emit (NDC x/y
 * verbatim for pan-end/tap/track; the pinch ±1 step either patched as a DELTA
 * or selecting one of a POSITIVE/NEGATIVE template pair). One spec set (the
 * most-recently-built gesture surface) — the test fixture mounts a single
 * surface; multi-surface routing is a host concern. */
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
/* Defined with the gesture affordances (below the pointer pipeline); forward-
 * declared here because controls_init (above them) drops the previous
 * session's object slots. */
static void gesture_affordance_forget(void);
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
/* ── Modifiers ride the EXISTING key ABI ───────────────────────────────────
 * The host sends bare keys, so `Ctrl+C` and `c` are the same event unless
 * something carries the chord. Three shapes were available; this is the third,
 * and it is chosen because the other two each pay a cost this one does not.
 *
 *   (a) A modifier BITMASK beside every key — controls_key_event(key, pressed,
 *       mods). It changes a SIGNATURE every host already calls, on the hot
 *       path, to carry a value that is redundant on all but a handful of
 *       events; and it cannot express a modifier edge at all, so a host that
 *       presses Ctrl and presses nothing else has no event to send.
 *   (b) The host resolves the chord and calls semantic entry points
 *       (copy/cut/paste/select-all). The module gets simpler and every host
 *       re-implements the key policy — so a new chord is an N-host change, and
 *       two hosts disagreeing about what Ctrl+A means is undetectable here.
 *
 * A modifier IS a key: it has a press and a release, and its ORDER against the
 * other keys is the whole of its meaning. So it travels as one, through
 * controls_key_event unchanged — no new signature, no new export, no new
 * import, and the existing ring queue supplies the ordering for free.
 *
 * The code space is provably free rather than conventionally free: every
 * lv_key_t control value is <= 127 (LV_KEY_DEL, lvgl/src/core/lv_group.h) and
 * the largest Unicode codepoint is 0x10FFFF, so nothing at or above 0x01000000
 * can be either. CTRL is the only modifier defined, because it is the only one
 * this module's chords consult; the encoding extends without moving anything. */
#define CONTROLS_KEY_MOD_MIN 0x01000000u
#define CONTROLS_KEY_CTRL 0x01000000u
/* Held-modifier state. Cleared by the lifecycle blur/hide flush, which already
 * exists to recover the pointer table from a focus loss — without that a host
 * that loses focus mid-chord never delivers the release, and the module would
 * treat every later keystroke as a command. */
static uint32_t mod_ctrl_held;
/* Defined with the clipboard machinery below (it needs focused_textarea).
 * Returns 1 when the event is a modifier edge, a Ctrl-chord, or a delete that
 * a selection has already answered — i.e. must NOT reach LVGL. */
static int key_consumed_before_lvgl(uint32_t key, uint32_t pressed);
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
  /* THE ACCENT TOKENS HAVE DIVERGED, deliberately, and this is the fork the
   * former static_assert demanded. They are no longer one value: no ink
   * reaches §6.2's governing 6:1 on a single shared fill, so each mode takes
   * the pole that clears both the ink floor and button-vs-card (tokens.edn
   * :accent-bg carries the derivation). color_primary is per-theme-instance
   * and this call already runs once per mode, so the select is simply the
   * mode. */
  lv_color_t primary =
      asgard_family ? lv_color_hex(current_theme_dark != 0 ? THEME_ACCENT_DARK
                                                           : THEME_ACCENT_LIGHT)
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
    /* The draw-stream census window is ONE composite. The root dump reports a
     * single theme_dark, and a palette rule selects one token table from it,
     * so records drawn under the previous composite would be judged against
     * the wrong table. Measured before this clear existed: an in-place
     * dark->light switch left every probed card's dump carrying
     * dark-exclusive colours under theme_dark=0. Restyling invalidates the
     * whole screen, so the next tick refills this honestly. */
    palette_observer_clear();
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
  /* Drain past every event the modifier layer consumes, so a chord never
   * surfaces to LVGL as a printable character. The loop (rather than the
   * single pop this used to be) is what keeps a consumed event from costing a
   * poll: with one modifier edge per call, a Ctrl+C would otherwise need three
   * LVGL reads — and the LAST of them would report the stale RELEASED state
   * that an empty queue reports, indistinguishable from a real key release. */
  while (key_q_head != key_q_tail) {
    key_event_t ev = key_queue[key_q_head];
    key_q_head = (key_q_head + 1u) % KEY_QUEUE_CAP;
    if (key_consumed_before_lvgl(ev.key, ev.pressed))
      continue;
    data->key = ev.key;
    data->state = ev.pressed ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
    data->continue_reading = key_q_head != key_q_tail;
    return;
  }
  data->state = LV_INDEV_STATE_RELEASED;
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
  /* Read-only second draw unit: records descriptor colours in evaluate_cb,
   * never creates or takes a task.  Registering it moves SW dispatch off
   * LVGL's one-unit fast path, so pixel neutrality is a required golden proof
   * for every change that keeps this line. */
  palette_observer_init();
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
  /* The affordance objects belonged to the PREVIOUS session's screen, which
   * controls_destroy deleted along with the display. The DELETE callback
   * already NULLed these, but a re-init that inherited a stale pointer would
   * hand LVGL a freed object, so the reset is stated rather than assumed. */
  gesture_affordance_forget();
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
  /* One census per full load. PARTIAL-strip repeats are merged by the observer
   * and any bounded-buffer overflow is reported by
   * controls_dump_draw_palette. */
  palette_observer_clear();
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
  /* A FAILED build leaves last_ui_data caching a screen that won't render
   * right — mark it stale so a later composite change asks the host to re-send
   * rather than rebuilding the broken screen. */
  if (status != 0)
    last_ui_stale = 1;
  /* Teardown is scoped to LOAD_ERR_ABORTED, and the scoping is the whole
   * point. The decode stopped mid-stream there, so what reached the screen is
   * debris that happens to precede the fault; leaving it up shows the operator
   * a truncated screen indefinitely, with no signal that it is truncated.
   *
   * LOAD_ERR_DEFECTIVE must NOT be torn down: the decode ran to completion, so
   * the tree is whole and only individual nodes are degraded (canonically a
   * duplicate codegen uid — the collided node is left unidentified on purpose
   * and everything else is correct). Blanking it destroys a working screen.
   *
   * This exact teardown was once applied to ANY nonzero status and had to be
   * reverted, because the status could not express which case had fired. It is
   * reintroduced now only because it can. reload_cycle.rs's non-vacuity guard
   * is what catches a regression to the blanking behaviour: a uid uniqueness
   * assertion over an EMPTY tree proves nothing, so blanking makes that test
   * fail rather than silently pass. */
  if (status == LOAD_ERR_ABORTED)
    lv_obj_clean(lv_screen_active());
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
 * else VIDEO.
 *
 * READ THE PREDICATE AS "NO CLICKABLE OBJECT TOOK THE POINT", NEVER AS "THE
 * POINT IS OVER THE PROXY". The paragraph above names the proxy because that
 * is the case the routing was designed around, and it has since been misread
 * as the whole rule. lv_indev_search_obj returns the topmost CLICKABLE object
 * and falls back to the nearest clickable ANCESTOR when no descendant hits, so
 * a non-clickable label/image/line/spinner is claimed by whatever clickable
 * container encloses it; VIDEO is reached only when the WHOLE chain up to the
 * screen is non-clickable. lv_obj_create sets CLICKABLE on every object, so an
 * ordinary container root shields its entire subtree and the VIDEO branch is
 * unreachable beneath it — while on a gesture screen the root IS the STATIC
 * proxy, and there every point is over the surface by construction.
 *
 * SO A CLEARED CLICKABLE IS A DELIBERATE POINTER-TRANSPARENCY DECLARATION, NOT
 * AN ACCIDENT, and three sites make one for that reason. Resolving ownership
 * from the TOPMOST object instead — walking the tree ignoring CLICKABLE, then
 * asking renderer_proxy_root whether the object found is the video surface —
 * breaks all three, each of which states its own reason where it clears the
 * flag: a STATIC proxy stops falling through to the clickable sibling BENEATH
 * it (proxy_apply_mode, renderer.c); a target overlay becomes the dead zone
 * its construction comment exists to prevent (renderer.c); and a second finger
 * landing on a live drag's affordance is routed away from the recognizer,
 * against property 2 of the affordance header below. Measured, not argued:
 * that substitution reds exactly two tests in
 * wasm_harness/tests/visual_regression.rs —
 * host_proxy::static_mode_clicks_through_to_the_button_beneath and
 * pointer_routing::second_finger_on_the_drag_affordance_still_joins_the_pinch
 * — with every neighbouring pointer test still green.
 *
 * WHAT THE PREDICATE GENUINELY DOES NOT ANSWER is WHERE the video is: a point
 * over no proxy at all still reads VIDEO. The shield above is what keeps that
 * off every ordinary screen, so it is not a live defect here, and the registry
 * behind renderer_proxy_root is what an answer would have to consult — a
 * question about the reported proxy RECT, never about the topmost object. */
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
/* Does an entry selecting `sel` answer a decision whose step is `delta`? ANY
 * answers every step; the two signed selectors answer only their own side. An
 * UNDEFINED selector answers NOTHING — the same disposition ui_GestureSpec.kind
 * already carries for an undefined kind (see
 * tools/renderer-gen/edn/ui-ast-constraint-dispositions.edn): a value outside
 * the vocabulary can select no spec, so the entry is inert rather than
 * silently taken as one of the three. */
static bool delta_sign_admits(uint32_t sel, int32_t delta) {
  switch (sel) {
  case CMD_PATCH_DELTA_SIGN_ANY:
    return true;
  case CMD_PATCH_DELTA_SIGN_POSITIVE:
    return delta > 0;
  case CMD_PATCH_DELTA_SIGN_NEGATIVE:
    return delta < 0;
  default:
    return false;
  }
}
/* The registered spec that answers decision `d` — kind equal AND delta_sign
 * admitting `d->delta` — or NULL when none does (which the drain reads as
 * "nothing to send"). THE DECISION IS THE ARGUMENT, not the kind, because the
 * step is half the key: a pinch surface registers one entry per direction when
 * the two directions are different commands, and resolving by kind alone would
 * hand both directions to whichever entry the wire happened to list first.
 *
 * The load refuses a kind carrying two entries that can both answer one
 * decision (renderer.c), so at most one entry here can match and the scan order
 * is not a contract. */
static const cmd_gesture_spec_t *
gesture_spec_for_decision(const gesture_decision_t *d) {
  for (uint32_t i = 0; i < g_gesture_spec_count; i++) {
    if (g_gesture_specs[i].kind == (uint32_t)d->kind &&
        delta_sign_admits(g_gesture_specs[i].delta_sign, d->delta))
      return &g_gesture_specs[i];
  }
  return NULL;
}
/* The first spec registered under `kind`, WHATEVER step it selects — the MODE
 * PROBE, and a deliberately different question from the one above.
 *
 * Its callers ask whether an ROI-mode surface is MOUNTED, and neither holds a
 * decision to discriminate on: an ROI rect is a reinterpreted PAN_END whose
 * corners carry no step at all, and the affordance sync runs on FSM state
 * between decisions. Answering a real decision with this function would
 * reinstate exactly the first-match collapse the pair was split to remove. */
static const cmd_gesture_spec_t *gesture_mode_spec(gesture_kind_t kind) {
  for (uint32_t i = 0; i < g_gesture_spec_count; i++) {
    if (g_gesture_specs[i].kind == (uint32_t)kind)
      return &g_gesture_specs[i];
  }
  return NULL;
}
/* Drain the buffered gesture decisions into cmd-out (R5b §8). Per decision,
 * find the GestureSpec that ANSWERS it — kind equal and delta_sign admitting
 * its step — and emit the patched cmd:
 *   - PAN_END / TAP / TRACK carry the NDC point (x/y written verbatim);
 *   - PINCH carries a ±1 step, EITHER patched into a signed leaf through the
 *     DELTA varint slot (one ANY entry) OR selecting between two patch-free
 *     templates (a POSITIVE + NEGATIVE pair), which is the only shape available
 *     when the two directions are different EMPTY commands.
 * A decision no registered template answers is dropped silently — there is
 * nothing to send. PAN_MOVE is the live instance: it reaches here and no
 * surface registers a template for it yet. WHEEL never reaches here at all,
 * whatever is registered — gesture_on_wheel is called only from the harness
 * seam (gesture_test_feed, over its own FSM state), and feed_gesture's switch
 * has no WHEEL arm. The buffer is cleared after the drain. */
static void drain_gesture_decisions(void) {
  for (uint32_t i = 0; i < g_decision_count; i++) {
    const gesture_decision_t *d = &g_decisions[i];
    const cmd_gesture_spec_t *spec = gesture_spec_for_decision(d);
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
   * never appears as a decision kind on the wire; it is only this lookup key —
   * which is why this is the MODE probe and not the decision resolver: there is
   * no ROI decision to carry a step. */
  const cmd_gesture_spec_t *roi_spec = gesture_mode_spec(GESTURE_KIND_ROI);
  for (int32_t i = 0; i < n; i++) {
    if (roi_spec && out[i].kind == GESTURE_KIND_PAN_END) {
      (void)cmd_patch_emit_rect(&roi_spec->cmd, g_gesture.start.x,
                                g_gesture.start.y, out[i].x, out[i].y);
      continue;
    }
    buffer_decision(&out[i]);
  }
}
/* ── Gesture affordances — the drag drawn from the FSM's own state ──────────
 * The recognizer classifies and emits, but an operator dragging to slew or to
 * mark an ROI saw NOTHING until the device moved. These are that feedback: a
 * start ANCHOR at the retained down point, plus ONE of two down->current
 * shapes — a rubber-band BAND for the drag that becomes a rect, an AIM line
 * for the drag that becomes a continuous slew.
 *
 * THREE PROPERTIES, each held by construction rather than by discipline:
 *
 * 1. IT IS A PURE FUNCTION OF FSM STATE, APPLIED ONCE PER TICK. There is one
 *    call site (controls_tick), so no event path can forget to update it and
 *    none can forget to tear it down. A pointer-up that never arrives is
 *    therefore already covered: the stale-pointer GC force-releases the slot
 *    through CANCEL, the FSM falls to idle, and the very next sync finds no
 *    drag and deletes the objects. Nothing keeps them alive.
 *
 * 2. IT IS NEVER A HIT TARGET. lv_obj_hit_test gates on LV_OBJ_FLAG_CLICKABLE
 *    and returns false without it (lv_obj_pos.c), so lv_indev_search_obj never
 *    returns one of these — which is the SAME search hit_test_owner uses to
 *    latch pointer ownership. Clearing that flag is what keeps a point over the
 *    affordance resolving to the video surface (and so to the FSM) instead of
 *    being claimed by LVGL. Without it the affordance would silently break the
 *    very gesture it draws: a second finger landing on it would be routed away
 *    from the recognizer and the pinch it should start would never happen.
 *
 * 3. THE DRAWN RECT AND THE EMITTED RECT ARE ONE RECT. The down corner comes
 *    from gesture_drag_origin, which reads `g_gesture.start` — the same field
 *    feed_gesture's ROI emit reads — and the far corner from the primary's live
 *    table slot, which is the same value handle_pointer hands the FSM on the
 *    very same call. Both are mapped through the same ndc_to_px. So at any
 *    instant the band drawn IS the rect a release at that instant would send.
 *
 * WHERE THE CURRENT POINT COMES FROM, and why not the FSM: outside a pinch the
 * recognizer never re-stores a moved sample, so it holds where the drag BEGAN
 * and not where the finger IS (gesture.h, gesture_drag_origin). The shell's
 * pointer table does hold it — handle_pointer writes slot->x/y from the same
 * clamped values it then feeds the FSM — so the two facts are read from the two
 * homes that own them rather than one being duplicated into the other.
 *
 * The affordance is gated on a REGISTERED gesture spec set: with no gesture
 * surface mounted a drag emits nothing, and drawing feedback for a gesture that
 * does nothing is a lie. The BAND-vs-AIM choice uses gesture_mode_spec(ROI) —
 * the same discriminator feed_gesture already uses to decide whether a PAN_END
 * becomes a rect — so the shape drawn cannot disagree with the command sent.
 *
 * Objects are CREATED when a drag commits and DELETED when it ends, rather than
 * kept and toggled hidden like the host-proxy affordances: a permanently
 * present hidden node would ride in every dump of every gesture surface, and
 * "it must not survive its gesture" is a stronger claim when there is nothing
 * left to survive.
 *
 * Colours are deliberately THEME-IMMUNE (white on black), exactly like the
 * host-proxy handles and align cells: this is drawn over host-composited video
 * the theme knows nothing about, so a token-derived tint would be a claim about
 * a backdrop the renderer cannot see. */
typedef enum {
  GESTURE_AFFORDANCE_ANCHOR = 0,
  GESTURE_AFFORDANCE_BAND = 1,
  GESTURE_AFFORDANCE_AIM = 2,
  GESTURE_AFFORDANCE_PART_COUNT = 3
} gesture_affordance_part_t;
/* Odd, so the square centres exactly on the down PIXEL rather than straddling
 * two of them — the anchor's whole job is to say where the drag began. */
#define GESTURE_ANCHOR_PX 9
#define GESTURE_BAND_BORDER_PX 2
#define GESTURE_AIM_WIDTH_PX 3
static lv_obj_t *g_affordance[GESTURE_AFFORDANCE_PART_COUNT];
/* The AIM line's two points. lv_line KEEPS the array pointer rather than
 * copying (lv_line.c line_set_points), so it must outlive every draw — a
 * caller-owned local would dangle. One line exists at a time, so one array. */
static lv_point_precise_t g_aim_points[2];
static const char *const
    gesture_affordance_names[GESTURE_AFFORDANCE_PART_COUNT] = {"anchor", "band",
                                                               "aim"};
/* The affordance name for `obj`, or NULL — the dump's declaration of its own
 * composition, for the same reason renderer_proxy_part exists: these objects
 * are built with bare lv_obj_create/lv_line_create, never reach finalize_widget
 * and so carry no uid, and a geometry rule that sees only rectangles cannot
 * tell a deliberate overlay from an accidental collision. */
static const char *gesture_affordance_part(const lv_obj_t *obj) {
  for (int i = 0; i < GESTURE_AFFORDANCE_PART_COUNT; i++) {
    if (g_affordance[i] == obj)
      return gesture_affordance_names[i];
  }
  return NULL;
}
/* Forget a slot whose object LVGL is deleting. The teardown paths that reach
 * these objects are not all ours — lv_obj_clean on a full load, on a composite
 * rebuild, and on the LOAD_ERR_ABORTED teardown all delete every screen child —
 * so the pointer is dropped by the DELETE event rather than by each of those
 * remembering to. */
static void gesture_affordance_deleted_cb(lv_event_t *ev) {
  lv_obj_t *obj = lv_event_get_target(ev);
  for (int i = 0; i < GESTURE_AFFORDANCE_PART_COUNT; i++) {
    if (g_affordance[i] == obj)
      g_affordance[i] = NULL;
  }
}
/* Drop one part (a no-op when it does not exist). The slot is cleared BEFORE
 * the delete so the DELETE callback cannot observe a half-torn state. */
static void gesture_affordance_drop(gesture_affordance_part_t part) {
  lv_obj_t *obj = g_affordance[part];
  if (!obj)
    return;
  g_affordance[part] = NULL;
  lv_obj_delete(obj);
}
static void gesture_affordance_clear(void) {
  for (int i = 0; i < GESTURE_AFFORDANCE_PART_COUNT; i++)
    gesture_affordance_drop((gesture_affordance_part_t)i);
}
/* Drop the slots WITHOUT deleting — the re-init case only, where the objects
 * died with the previous display and deleting through a stale pointer is
 * exactly the hazard. Never a substitute for gesture_affordance_clear: on a
 * live display it would leak the objects it forgot. */
static void gesture_affordance_forget(void) {
  for (int i = 0; i < GESTURE_AFFORDANCE_PART_COUNT; i++)
    g_affordance[i] = NULL;
}
/* The part's fixed look, applied ONCE at creation — the sync then moves it and
 * nothing else, so a live drag costs geometry rather than a style rewrite per
 * frame. White on black throughout: see the section header for why this is
 * deliberately theme-immune. */
static void gesture_affordance_style(lv_obj_t *obj,
                                     gesture_affordance_part_t part) {
  switch (part) {
  case GESTURE_AFFORDANCE_ANCHOR:
    lv_obj_set_style_radius(obj, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(obj, lv_color_white(), 0);
    lv_obj_set_style_bg_opa(obj, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(obj, 1, 0);
    lv_obj_set_style_border_color(obj, lv_color_black(), 0);
    lv_obj_set_style_border_opa(obj, LV_OPA_COVER, 0);
    break;
  case GESTURE_AFFORDANCE_BAND:
    /* A near-transparent fill rather than none: the outline alone can vanish
     * over matching video, and a covering fill would hide what is being
     * selected — which is the one thing the operator is looking at. */
    lv_obj_set_style_border_width(obj, GESTURE_BAND_BORDER_PX, 0);
    lv_obj_set_style_border_color(obj, lv_color_white(), 0);
    lv_obj_set_style_border_opa(obj, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(obj, lv_color_white(), 0);
    lv_obj_set_style_bg_opa(obj, LV_OPA_10, 0);
    break;
  case GESTURE_AFFORDANCE_AIM:
    lv_obj_set_style_line_width(obj, GESTURE_AIM_WIDTH_PX, 0);
    lv_obj_set_style_line_color(obj, lv_color_white(), 0);
    lv_obj_set_style_line_opa(obj, LV_OPA_COVER, 0);
    lv_obj_set_style_line_rounded(obj, true, 0);
    break;
  default:
    break;
  }
}
/* Create `part` if absent and return it, or NULL if allocation failed. The
 * flags are the host-proxy affordance set MINUS clickability: FLOATING +
 * IGNORE_LAYOUT keep an authored flex/grid layout from moving an overlay it
 * does not own, and the cleared CLICKABLE is property 2 above. */
static lv_obj_t *gesture_affordance_get(gesture_affordance_part_t part) {
  if (g_affordance[part])
    return g_affordance[part];
  lv_obj_t *parent = lv_screen_active();
  if (!parent)
    return NULL;
  lv_obj_t *obj = (part == GESTURE_AFFORDANCE_AIM) ? lv_line_create(parent)
                                                   : lv_obj_create(parent);
  if (!obj) {
    LOG_ERROR("gesture affordance '%s' allocation failed",
              gesture_affordance_names[part]);
    return NULL;
  }
  lv_obj_remove_style_all(obj);
  lv_obj_add_flag(obj, LV_OBJ_FLAG_FLOATING);
  lv_obj_add_flag(obj, LV_OBJ_FLAG_IGNORE_LAYOUT);
  lv_obj_remove_flag(obj, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_remove_flag(obj, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_remove_flag(obj, LV_OBJ_FLAG_SCROLL_CHAIN);
  lv_obj_add_event_cb(obj, gesture_affordance_deleted_cb, LV_EVENT_DELETE,
                      NULL);
  gesture_affordance_style(obj, part);
  /* Explicit rather than relying on creation order: the affordance is drawn
   * OVER the surface it describes, and a later incremental patch must not be
   * able to paint the drag away. */
  lv_obj_move_foreground(obj);
  g_affordance[part] = obj;
  return obj;
}
/* Place `obj` so its INCLUSIVE box is exactly [x1,y1]..[x1+w-1,y1+h-1] in
 * display px. lv_obj_set_pos is content-relative, so the parent's content
 * origin is subtracted rather than assumed to be zero — the same normalization
 * proxy_normalize_pos does, and the reason the band's coords in the dump equal
 * the px the emitted NDC maps to. */
static void gesture_affordance_place(lv_obj_t *obj, int32_t x1, int32_t y1,
                                     int32_t w, int32_t h) {
  lv_obj_t *parent = lv_obj_get_parent(obj);
  if (!parent)
    return;
  lv_area_t content;
  lv_obj_get_content_coords(parent, &content);
  lv_obj_set_align(obj, LV_ALIGN_TOP_LEFT);
  lv_obj_set_pos(obj, x1 - content.x1, y1 - content.y1);
  lv_obj_set_size(obj, w, h);
}
static void gesture_affordance_anchor(const lv_point_t *down) {
  lv_obj_t *obj = gesture_affordance_get(GESTURE_AFFORDANCE_ANCHOR);
  if (!obj)
    return;
  gesture_affordance_place(obj, down->x - GESTURE_ANCHOR_PX / 2,
                           down->y - GESTURE_ANCHOR_PX / 2, GESTURE_ANCHOR_PX,
                           GESTURE_ANCHOR_PX);
}
/* The rubber-band, drawn over the SAME two corners the ROI command carries.
 * Normalized (min/max) because a box cannot have negative extent, while the
 * command relays the corners in DRAG order — the two describe one rect, and the
 * consumer owns the ordering (cmd_patch_emit_rect). The +1s are LVGL's
 * inclusive-coordinate convention: a box at x1 of width w ends at x1+w-1, so a
 * band spanning both corner PIXELS is one wider than their difference. */
static void gesture_affordance_band(const lv_point_t *down,
                                    const lv_point_t *cur) {
  lv_obj_t *obj = gesture_affordance_get(GESTURE_AFFORDANCE_BAND);
  if (!obj)
    return;
  int32_t x1 = LV_MIN(down->x, cur->x);
  int32_t y1 = LV_MIN(down->y, cur->y);
  int32_t w = LV_ABS(cur->x - down->x) + 1;
  int32_t h = LV_ABS(cur->y - down->y) + 1;
  gesture_affordance_place(obj, x1, y1, w, h);
}
/* The slew aim: a line from the anchor to the finger, so direction and
 * magnitude are both visible on a gesture that emits a continuous command and
 * never a rect. lv_line's points are relative to its own origin and its
 * self-size takes their MAXIMUM, so they are offset to the bounding box's
 * top-left (never negative) and the size is set explicitly. */
static void gesture_affordance_aim(const lv_point_t *down,
                                   const lv_point_t *cur) {
  lv_obj_t *obj = gesture_affordance_get(GESTURE_AFFORDANCE_AIM);
  if (!obj)
    return;
  int32_t x1 = LV_MIN(down->x, cur->x);
  int32_t y1 = LV_MIN(down->y, cur->y);
  g_aim_points[0].x = (lv_value_precise_t)(down->x - x1);
  g_aim_points[0].y = (lv_value_precise_t)(down->y - y1);
  g_aim_points[1].x = (lv_value_precise_t)(cur->x - x1);
  g_aim_points[1].y = (lv_value_precise_t)(cur->y - y1);
  gesture_affordance_place(obj, x1, y1, LV_ABS(cur->x - down->x) + 1,
                           LV_ABS(cur->y - down->y) + 1);
  /* Re-set every sync — unlike the look, the points ARE geometry: the array
   * changed in place, and this call is what refreshes the self size and
   * invalidates the old span. */
  lv_line_set_points(obj, g_aim_points, 2);
}
/* The live drag's two corners in display px, or false when no drag is live.
 * The FSM answers "has a drag committed, and where did it begin"; the pointer
 * table answers "where is that pointer now" — see the section header. A primary
 * whose slot has gone is reported as NO drag rather than half-read. */
static bool gesture_drag_px(lv_point_t *down, lv_point_t *cur) {
  int32_t primary = 0;
  double dx = 0.0;
  double dy = 0.0;
  if (!gesture_drag_origin(&g_gesture, &primary, &dx, &dy))
    return false;
  const pointer_slot_t *slot = pointer_find((uint32_t)primary);
  if (!slot)
    return false;
  ndc_to_px(dx, dy, down);
  ndc_to_px(slot->x, slot->y, cur);
  return true;
}
/* Re-derive the whole affordance set from the recognizer. THE ONE ENTRY POINT
 * (controls_tick) — see the header block for why that is what makes teardown
 * unforgettable. */
static void gesture_affordance_sync(void) {
  lv_point_t down;
  lv_point_t cur;
  if (g_gesture_spec_count == 0 || !gesture_drag_px(&down, &cur)) {
    gesture_affordance_clear();
    return;
  }
  gesture_affordance_anchor(&down);
  if (gesture_mode_spec(GESTURE_KIND_ROI)) {
    gesture_affordance_drop(GESTURE_AFFORDANCE_AIM);
    gesture_affordance_band(&down, &cur);
  } else {
    gesture_affordance_drop(GESTURE_AFFORDANCE_BAND);
    gesture_affordance_aim(&down, &cur);
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
    /* Held modifiers die with the focus for the same reason the pointer table
     * does: the release edge is never delivered for a key that was down when
     * the surface lost focus, so a surviving Ctrl would silently turn every
     * later keystroke into a command. */
    mod_ctrl_held = 0;
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
 * (root_template past the 128-byte cap, or more patches than
 * ui_CmdSpec.patches holds — 8 today; renderer.h carries the full return
 * contract and points at the generated header for the bound). Delegates to
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
/* The live selection as NORMALISED CHARACTER indices, or false when nothing is
 * selected. The indices are CHARACTER offsets, not byte offsets — LVGL says so
 * at lv_label_get_letter_on ("Expressed in character index and not byte index
 * (different in UTF-8)"), and every caller here that needs bytes converts
 * through lv_text_encoded_get_byte_id against the REAL text. Getting that wrong
 * is silent: it is a no-op for ASCII and splits a codepoint the moment the
 * field holds anything else. */
static bool ta_selection_range(lv_obj_t *ta, uint32_t *start, uint32_t *end) {
  lv_obj_t *label = lv_textarea_get_label(ta);
  if (!label)
    return false;
  uint32_t s = lv_label_get_text_selection_start(label);
  uint32_t e = lv_label_get_text_selection_end(label);
  if (s == LV_LABEL_TEXT_SELECTION_OFF || e == LV_LABEL_TEXT_SELECTION_OFF ||
      s == e)
    return false;
  *start = s < e ? s : e;
  *end = s < e ? e : s;
  return true;
}
/* Delete the selected range, if any. LVGL tracks and PAINTS a selection but
 * edits right past it — lv_textarea_delete_char removes the one character
 * before the cursor and then CLEARS the selection, and lv_textarea_add_char
 * never consults it at all — so range deletion does not exist upstream and is
 * supplied here.
 *
 * It is built from LVGL's own single-character delete rather than a cut of the
 * label buffer, because in password mode the label holds BULLETS while the real
 * text lives in a parallel buffer; only this path keeps the two in step, and it
 * maintains the cursor and fires VALUE_CHANGED as a caller would expect. The
 * loop is bounded by the field's own length. */
static void ta_delete_selection(lv_obj_t *ta) {
  uint32_t s, e;
  if (!ta_selection_range(ta, &s, &e))
    return;
  lv_textarea_set_cursor_pos(ta, e);
  for (uint32_t i = s; i < e; i++)
    lv_textarea_delete_char(ta);
  lv_textarea_clear_selection(ta);
}
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
  /* A paste REPLACES the selection — the behaviour every text field has, and
   * one LVGL does not implement (lv_textarea_add_text inserts at the cursor and
   * leaves the selected range in place, so without this a paste over a
   * selection would duplicate rather than replace). */
  ta_delete_selection(ta);
  /* max_length is the SECOND cap, and unlike TEXT_INPUT_CAP above LVGL enforces
   * it by SILENT TRUNCATION: lv_textarea_add_text falls into a per-character
   * loop whenever a limit is set, and each character past the limit is dropped
   * with no signal to the caller. A field that swallowed half a paste and
   * reported success is exactly the failure the staging cap already refuses, so
   * it is refused here on the same terms — measured against the field's length
   * in CHARACTERS, which is the unit max_length is expressed in. */
  uint32_t max_len = lv_textarea_get_max_length(ta);
  if (max_len > 0) {
    uint32_t have = lv_text_get_encoded_length(lv_textarea_get_text(ta));
    uint32_t add = lv_text_get_encoded_length(buf);
    if (have + add > max_len) {
      LOG_WARN("text input rejected: %u+%u chars exceeds max_length %u", have,
               add, max_len);
      return -1;
    }
  }
  lv_textarea_add_text(ta, buf);
  return 0;
}
/**
 * NUL-terminated text of the WHOLE group-focused textarea, or 0 (NULL) when no
 * textarea is focused or the field is in PASSWORD mode.
 *
 * This used to document itself as "the copy path", copying the whole field
 * because "LVGL's textarea has no selection API", and returning the real text
 * in password mode because "that is what a copy should carry". All three are
 * retired. The selection API exists and is compiled in; the copy path is now
 * controls_take_clipboard_request/controls_get_clipboard_text below, which
 * carries the SELECTION; and a field whose whole purpose is to keep its
 * contents off the screen must not hand them to a clipboard, so password mode
 * refuses here on the same terms the chord path refuses.
 */
uint32_t controls_get_focused_text(void) {
  lv_obj_t *ta = focused_textarea();
  if (!ta)
    return 0;
  if (lv_textarea_get_password_mode(ta)) {
    LOG_WARN("focused-text read refused: field is in password mode");
    return 0;
  }
  return (uint32_t)(uintptr_t)lv_textarea_get_text(ta);
}
/* ── Clipboard: the module decides WHAT, the host moves the BYTES ───────────
 * A new env.* import is instantiation-MANDATORY (see CONTROLS_ABI_VERSION), so
 * a module that PUSHED clipboard bytes to the host would fail to load in every
 * host that had not yet linked it. The transfer is therefore a PULL, in the
 * idiom the framebuffer and dirty-rect already use: the module resolves the
 * chord, performs whatever edit is local, and leaves a REQUEST plus a payload
 * for the host to drain on its next tick.
 *
 * Cut stashes BEFORE it deletes, so the host's read is independent of when it
 * drains — the alternative (host reads the selection, then asks the module to
 * delete it) makes the ordering of two host calls load-bearing for correctness.
 * Paste carries no payload: the module cannot read the system clipboard, so the
 * request means "call controls_text_input with the clipboard", which is the
 * existing export and already refuses over-cap input. */
#define CONTROLS_CLIP_NONE 0u
#define CONTROLS_CLIP_COPY 1u
#define CONTROLS_CLIP_CUT 2u
#define CONTROLS_CLIP_PASTE 3u
static uint32_t clip_request = CONTROLS_CLIP_NONE;
static char clip_text[TEXT_INPUT_CAP];
/* Stash the copy payload: the SELECTION when there is one, else the whole
 * field. "Else the whole field" is the sensible no-selection answer because it
 * preserves what the previous whole-field copy did for a host whose operator
 * hit copy without selecting — a caret-only copy that yielded nothing would be
 * a silent no-op. Returns false when there is nothing to carry. */
static bool clip_stash_from(lv_obj_t *ta) {
  const char *txt = lv_textarea_get_text(ta);
  if (!txt)
    return false;
  uint32_t s, e;
  uint32_t b0 = 0;
  uint32_t b1 = (uint32_t)strlen(txt);
  if (ta_selection_range(ta, &s, &e)) {
    /* CHARACTER indices -> BYTE offsets, against the real text. */
    b0 = lv_text_encoded_get_byte_id(txt, s);
    b1 = lv_text_encoded_get_byte_id(txt, e);
  }
  if (b1 <= b0)
    return false;
  uint32_t n = b1 - b0;
  if (n >= sizeof(clip_text)) {
    LOG_WARN("clipboard copy refused: %u bytes exceeds cap %u", n,
             (uint32_t)sizeof(clip_text));
    return false;
  }
  memcpy(clip_text, txt + b0, n);
  clip_text[n] = '\0';
  return true;
}
/**
 * Drain the pending clipboard request (0 none, 1 copy, 2 cut, 3 paste) and
 * clear it. The host polls this once per tick alongside the dirty rect. For 1
 * and 2 the payload is controls_get_clipboard_text(); for 3 the host is being
 * asked to hand its clipboard to controls_text_input().
 */
uint32_t controls_take_clipboard_request(void) {
  uint32_t r = clip_request;
  clip_request = CONTROLS_CLIP_NONE;
  return r;
}
/**
 * NUL-terminated bytes the last copy or cut staged, or 0 (NULL) when empty.
 * Valid until the next clipboard request is resolved.
 */
uint32_t controls_get_clipboard_text(void) {
  return clip_text[0] ? (uint32_t)(uintptr_t)clip_text : 0;
}
/* Resolve one Ctrl-chord against the focused textarea. */
static void resolve_ctrl_chord(uint32_t key) {
  lv_obj_t *ta = focused_textarea();
  if (!ta)
    return;
  /* Case-fold: a host that reports the shifted letter for Ctrl+Shift+C must
   * reach the same chord, and lv_key_t control values are all below 'A'. */
  uint32_t k = (key >= 'A' && key <= 'Z') ? key - 'A' + 'a' : key;
  bool pwd = lv_textarea_get_password_mode(ta);
  switch (k) {
  case 'a':
    /* Select-all is safe in a password field: it selects bullets, and every
     * path that would EXPORT them refuses separately. */
    lv_textarea_set_cursor_pos(ta, LV_TEXTAREA_CURSOR_LAST);
    lv_label_set_text_selection_start(lv_textarea_get_label(ta), 0);
    lv_label_set_text_selection_end(
        lv_textarea_get_label(ta),
        lv_text_get_encoded_length(lv_textarea_get_text(ta)));
    lv_obj_invalidate(ta);
    break;
  case 'c':
    if (pwd) {
      LOG_WARN("copy refused: field is in password mode");
      break;
    }
    if (clip_stash_from(ta))
      clip_request = CONTROLS_CLIP_COPY;
    break;
  case 'x':
    if (pwd) {
      /* Refusing the DELETE too, not just the export: a cut whose copy half
       * was refused would destroy text the operator can never paste back. */
      LOG_WARN("cut refused: field is in password mode");
      break;
    }
    if (clip_stash_from(ta)) {
      clip_request = CONTROLS_CLIP_CUT;
      ta_delete_selection(ta);
    }
    break;
  case 'v':
    clip_request = CONTROLS_CLIP_PASTE;
    break;
  default:
    break;
  }
}
static int key_consumed_before_lvgl(uint32_t key, uint32_t pressed) {
  if (key >= CONTROLS_KEY_MOD_MIN) {
    if (key == CONTROLS_KEY_CTRL)
      mod_ctrl_held = pressed ? 1u : 0u;
    else
      LOG_WARN("unknown modifier key %u ignored", key);
    return 1;
  }
  if (mod_ctrl_held) {
    /* While Ctrl is held every key is a COMMAND, never text — so the release is
     * swallowed with the press, and an unmapped chord types nothing rather than
     * inserting a stray character. */
    if (pressed)
      resolve_ctrl_chord(key);
    return 1;
  }
  if (!pressed)
    return 0;
  /* Typing over a selection REPLACES it. LVGL does not: lv_textarea_add_char
   * inserts at the cursor with the selected range left in place, and
   * lv_textarea_delete_char removes ONE character and then clears the
   * selection — so without this, select-all followed by a keystroke appends to
   * the text it was supposed to overwrite, and backspace over a selection eats
   * one character instead of the range. */
  lv_obj_t *ta = focused_textarea();
  uint32_t s, e;
  if (!ta || !ta_selection_range(ta, &s, &e))
    return 0;
  bool is_delete = (key == LV_KEY_BACKSPACE || key == LV_KEY_DEL);
  bool is_text = (key == LV_KEY_ENTER || key >= 0x20u) && key != LV_KEY_DEL;
  if (!is_delete && !is_text)
    return 0; /* navigation and the rest keep LVGL's own behaviour */
  ta_delete_selection(ta);
  /* A delete key's whole effect WAS the range removal; forwarding it too would
   * take an extra character the operator never selected. A text key still has
   * its insert to do, so it goes on to LVGL. */
  return is_delete ? 1 : 0;
}
int32_t controls_tick(uint32_t elapsed_ms) {
  lv_tick_inc(elapsed_ms);
  flush_happened = 0;
  dirty_valid = 0;
  /* Stale-pointer GC BEFORE the timer handler: a force-released slot must drop
   * its indev press before indev_read_cb polls this tick (§8). */
  pointer_gc_sweep();
  /* Gesture affordances BEFORE the timer handler so a drag's feedback lays out
   * and paints in the SAME tick its pointer event arrived in — and AFTER the GC
   * sweep, so a force-released contact's affordance is gone in that same tick
   * rather than lingering one frame past its gesture. */
  gesture_affordance_sync();
  lv_timer_handler();
  /* Host-proxy report sweep — AFTER lv_timer_handler so coords are
   * post-layout; change-guarded, at most one report per proxy per tick. */
  proxy_report_sweep();
  /* R5b cmd-out: drain the buffered gesture decisions into host_command (per
   * decision, find the GestureSpec that ANSWERS it + emit the patched cmd). After
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
 * flags (clipped / overflow / scrollable_overflow / text_truncated /
 * text_wrapped) are derived from resolved geometry, so both oracles agree on
 * them. Resolved STYLE (colour + opacity) rides alongside — see the block
 * comment above
 * obj_effective_opa, which is also where each key's absence is defined,
 * because they do not all mean the same thing when missing. */
#define TREE_BUF_SIZE 131072u
static char tree_buf[TREE_BUF_SIZE];
/* The per-label text budget in the dump, in BYTES.
 *
 * THE NUMBER IS A BUDGET DECISION, NOT AN INHERITED CONSTANT, so the argument
 * is recorded rather than the value alone. Everything the dump emits shares the
 * ONE fixed tree_buf above, and per-node text is the only contributor that
 * scales with what a screen SAYS rather than with how it is built — so this cap
 * is what decides how many nodes a complete dump can carry.
 *
 * Measured through the harness on a label node placed off-canvas (type +
 * coords + vis_px + clipped + clickable + paint_bound + backdrop_unresolved +
 * children): 234 dump bytes, of which this cap is 64. Raising it to the field's
 * own wire bound — ui.WidgetNode.text is 255 bytes, so a cap there could never
 * bind and the dump would be lossless — takes that node to 425 bytes and cuts
 * the labels a complete dump can carry from roughly 560 to roughly 310.
 *
 * That trade goes the wrong way, and the two failures are not comparable. A cap
 * that binds costs the TAIL OF ONE LABEL, and (with the boundary cut below)
 * costs it as a valid prefix. Flooding tree_buf costs the consumer THE WHOLE
 * TREE: the sentinel makes it loud, and the only sound consumer response to a
 * truncated dump is to discard every node in it, because the emitted bytes are
 * a clean prefix on some byte counts and interleaved on others (the harness's
 * `truncated_dump_never_parses` records that measurement). Trading a local
 * lossy read for a total one buys nothing, and the headroom is what pays for
 * the deep screens the dump exists to describe. So the cap stays — as a named
 * constant carrying its budget, rather than as a literal at the call site.
 *
 * WHAT THE CAP STILL DOES NOT BUY, stated so nobody reads its survival as a
 * clean bill: a consumer cannot tell a label that was exactly this long from
 * one that was cut here, so a dumped text of exactly this many bytes means "at
 * least this long" and no more. Closing that needs the dump to SAY it was cut,
 * which is a new key and therefore a change to the dump contract — a different
 * change from this one, with its own consumers to carry. */
#define TREE_TEXT_CAP 64u
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
/* esc[] must hold the worst case of the WIDEST cap any caller passes, so it is
 * sized off that maximum rather than off one caller's constant — the two are
 * independent and either may move. */
#define JSON_ESC_MAX_CAP                                                       \
  (UI_EVENT_NAME_BUF > TREE_TEXT_CAP ? UI_EVENT_NAME_BUF : TREE_TEXT_CAP)
/* Back a byte-length cut off to the nearest UTF-8 codepoint boundary at or
 * below it: given `s` and a proposed cut of `cut` bytes, return the largest
 * length ≤ cut that does not end inside a character.
 *
 * THE ONE HOME FOR THIS, and both byte caps in the dump path call it. A cut
 * chosen by arithmetic — a cap, a buffer offset — knows nothing about the bytes
 * it lands on, and label text is not ASCII: the devcard corpus alone renders
 * `°`, `±`, `…`, `–` and `→` (tools/devcards/corpus/spec.edn), two and three
 * bytes each. Half a character is not UTF-8, so a host that decodes the dump as
 * a string REFUSES IT WHOLE rather than receiving it slightly wrong; at the
 * sentinel site the bytes destroyed are the truncation signal's own, so the
 * signal dies of the truncation it exists to report.
 *
 * A continuation byte (10xxxxxx) is never the first byte of a character, so a
 * cut landing on one splits the character that began before it. Walking back
 * over at most THREE is total for well-formed input — four bytes is the
 * longest UTF-8 sequence, so a cut can sit at most three bytes inside one — and
 * bounding it there is also what keeps malformed input from walking the buffer:
 * a cut that is still on a continuation byte after three steps came from bytes
 * that were not UTF-8 to begin with, and no choice of cut makes them so. */
static uint32_t utf8_trim_to_boundary(const char *s, uint32_t cut) {
  for (uint32_t back = 0; back < 3u && cut > 0u; back++) {
    if (((unsigned char)s[cut] & 0xC0u) != 0x80u)
      break;
    cut--;
  }
  return cut;
}
/* Append a JSON-escaped string value (no quotes added), capping the input at
 * `max_bytes` — BYTES, and the parameter is named for what the loop below
 * counts, because a name promising CHARACTERS is precisely the misreading the
 * boundary cut exists to prevent. esc[] is sized for the LARGEST cap any caller uses
 * (JSON_ESC_MAX_CAP × the 6-byte \u00xx worst case + NUL), so escaping is TOTAL
 * for every in-cap input — a control-char-dense name escapes fully, never clips
 * mid-name. The event tag passes UI_EVENT_NAME_BUF (the full dotted command-id,
 * one home shared with the decode + cache buffers, renderer.h); the trigger is a
 * short enum name; the tree dump passes TREE_TEXT_CAP.
 *
 * The cut is taken ONCE, up front, and backed off to a codepoint boundary, so
 * the emitted value is always a valid UTF-8 PREFIX of the input. */
static void json_append_str(json_out_t *out, const char *s,
                            uint32_t max_bytes) {
  char esc[JSON_ESC_MAX_CAP * 6u + 8u];
  uint32_t n = 0;
  while (s[n] != '\0' && n < max_bytes)
    n++;
  n = utf8_trim_to_boundary(s, n);
  uint32_t o = 0;
  for (uint32_t i = 0; i < n && o + 8u < sizeof(esc); i++) {
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
  json_append_str(&tree_out, s, TREE_TEXT_CAP);
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
/* An INVERTED content box: padding (plus border) exceeds the widget's own
 * size, so lv_obj_get_content_coords returns x1 > x2 or y1 > y2. It is not a
 * rectangle, and every geometry comparison against it yields nonsense in the
 * SAME direction — everything looks outside it, because there is no inside.
 *
 * Reachable rather than theoretical, and the threshold is PER WIDGET CLASS
 * because the space is pad PLUS border and the stock theme pads the two axes
 * differently. For an lv_obj the card style is PAD_DEF 24 + BORDER_WIDTH 2, so
 * the space is 26 and any box under 52px in a dimension inverts. Measured on
 * lv_obj/default/small under family 1: a 40x40 parent whose content box comes
 * back [26 26 13 13].
 *
 * A BUTTON IS NOT THE EXAMPLE TO REACH FOR, though it is the tempting one.
 * lv_theme_default sets pad_hor = PAD_DEF but pad_ver = PAD_SMALL, so
 * lv_button/default/medium at [0 0 79 35] has content box [24 14 55 21] —
 * VALID, not inverted. Its label at [24 14 61 31] overruns that box while
 * staying inside the button's coords, which is a content-box FIT failure this
 * function never sees and never should: the corpus declares it.
 *
 * ONE CALLER ONLY, and that is deliberate: `obj_clipped`, to choose its
 * comparison box. It is NOT used to decline a verdict — `obj_overflow_dirs`
 * carries the argument for why an inverted box must not suppress an overflow
 * report, and the guard that once did so there was removed.
 *
 * THE INVERSION ITSELF IS A REAL AND CURRENTLY UNREPORTED FACT — a widget
 * whose padding exceeds its size has no room for content by construction, and
 * no dump key says so. That is a NAMED GAP rather than something this predicate
 * closes: it only stops one comparison from being made against a box that has
 * no inside. Emitting it is a separate change owing its own evidence, and the
 * cards that would fire cannot fix it: authoring pad on the widget under test
 * would defeat the theme fallthrough that IS the object under test.
 * corpus/spec.edn's unstyled law does permit raw pad-all slots, so it is the
 * fallthrough and not the law that forbids it here. */
static bool content_box_inverted(const lv_area_t *content) {
  return content->x1 > content->x2 || content->y1 > content->y2;
}
/* clipped: a child whose resolved box escapes the box its parent would clip it
 * to. Geometry only — fires even on scrollable parents, where
 * scrollable_overflow is the "fine, it scrolls" companion signal.
 *
 * The comparison box is the parent's CONTENT area, which is the layout-fit
 * question and the stricter of the two available boxes — EXCEPT when that box
 * is inverted, where it answers nothing and the parent's own coords are used
 * instead. Coords are what LVGL actually clips a child to (the same box
 * `descend_gate` documents for the pointer path, absent
 * LV_OBJ_FLAG_OVERFLOW_VISIBLE), so the fallback is this key's own name rather
 * than a weaker approximation of it.
 *
 * Scoped to the inverted case ON PURPOSE: on any parent whose content box IS a
 * box, the comparison is exactly what it was, so this cannot relax the clause
 * on a well-formed tree. The dock's real defect — a 30px icon button whose
 * glyph label ran to x2 133 against the button's own x2 123 — escapes COORDS
 * and still fires under the fallback. */
static bool obj_clipped(const lv_obj_t *obj, const lv_area_t *coords) {
  const lv_obj_t *parent = lv_obj_get_parent(obj);
  if (parent == NULL)
    return false;
  lv_area_t pc;
  lv_obj_get_content_coords(parent, &pc);
  if (content_box_inverted(&pc))
    pc = parent->coords;
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
 * overflow (clipped away) vs scrollable_overflow (reachable by scroll).
 *
 * The AXES are reported separately (obj_overflow_dirs) so a "this card is a
 * deliberate scroller" declaration CAN be scoped to the direction it actually
 * designed — a single boolean would make such a declaration a per-card mute for
 * every axis at once.
 *
 * AND THE SCOPING IS EXERCISED, not merely available: `devcards.invariants`
 * decodes this string into an axis SET (`scroll-axis-spellings`) and a
 * `:designed-flags` entry for `scrollable_overflow` MUST name its axes, matched
 * by equality. So an entry declaring "ver" on a node that starts reporting
 * "both" matches nothing — the flag stays live and the entry is reported
 * mis-scoped. Both of protogen's own such entries carry `:axes #{:ver}`, and
 * their wrapper's horizontal slack is exactly zero (content box 176 - 2*8 = 160
 * = the textarea's own width), which is the regression that narrowing exists to
 * catch.
 *
 * NOTE WHICH SPELLING IS NOT A VOCABULARY WORD: three strings, two axes. "both"
 * decodes to the set of the other two and can never be named by a declaration,
 * or it would be an axis no single-axis node could ever equal. */
#define OVERFLOW_DIR_HOR 1u
#define OVERFLOW_DIR_VER 2u
/* An object with NOTHING to scroll to: no self content and no visible child.
 *
 * This guard exists because LVGL's extent goes POSITIVE on such a node purely
 * from an inverted content box, and the result is not evidence of anything.
 * lv_obj_get_scroll_bottom (lv_obj_scroll.c) returns
 * LV_MAX(child_extent, self_h) where
 *   self_h = lv_obj_get_self_height(obj) - (height - space_top - space_bottom)
 * With no visible children the child term is LV_COORD_MIN, so the extent IS
 * the self term; and for a plain lv_obj self height is 0, because the base
 * class implements no get_self_size. Once the theme's SPACE exceeds the box the
 * parenthesised height goes NEGATIVE and subtracting it yields a positive
 * extent on an EMPTY node.
 *
 * SPACE, not pad: `lv_obj_get_style_space_*` adds the border, so the threshold
 * is per widget class rather than one number. A card-styled lv_obj takes
 * PAD_DEF 24 + BORDER_WIDTH 2 = 26 a side and inverts under 52px; a button's
 * vertical axis takes pad_ver PAD_SMALL 14 and inverts under 28px. Measured:
 * the childless 20x20 chip in lv_obj/default/small reports 32px of scroll
 * extent under families 1 and 2 — 0 - (20 - 26 - 26) — and none under family 0.
 *
 * Scoped to nodes with no content on purpose, so it removes the artifact and
 * nothing else. The self-size term is REAL for classes that compute one —
 * lv_label from its text, lv_table from its cells, lv_roller from its drum —
 * so those keep reporting; and a node WITH a visible child keeps reporting
 * too, because a child outside an inverted content box really is drawn outside
 * its parent and really is clipped. What is suppressed is only the case where
 * there is provably nothing on the other side of the scroll. */
static bool obj_has_no_content(const lv_obj_t *obj) {
  if (lv_obj_get_self_width(obj) > 0 || lv_obj_get_self_height(obj) > 0)
    return false;
  uint32_t n = lv_obj_get_child_count(obj);
  for (uint32_t i = 0; i < n; i++) {
    const lv_obj_t *child = lv_obj_get_child(obj, i);
    if (!lv_obj_has_flag_any(child, LV_OBJ_FLAG_HIDDEN | LV_OBJ_FLAG_FLOATING))
      return false;
  }
  return true;
}
static unsigned obj_overflow_dirs(const lv_obj_t *obj) {
  unsigned dirs = 0u;
  if (obj_has_no_content(obj))
    return dirs;
  if (lv_obj_get_scroll_top(obj) > 0 || lv_obj_get_scroll_bottom(obj) > 0)
    dirs |= OVERFLOW_DIR_VER;
  if (lv_obj_get_scroll_left(obj) > 0 || lv_obj_get_scroll_right(obj) > 0)
    dirs |= OVERFLOW_DIR_HOR;
  /* ON AN INVERTED AXIS THIS FUNCTION DECLINES TO ANSWER, and that is a
   * DECLARED DECLINE rather than a silent suppression — read the whole of this
   * before removing it, because both the guard and its absence have been
   * measured and each is wrong in one direction.
   *
   * `lv_obj_get_scroll_bottom` returns LV_MAX of TWO terms and an inverted
   * content box corrupts BOTH, which is why neither can be read here:
   *   self_h    = self_height - (height - space_top - space_bottom). The
   *               parenthesis goes negative, so subtracting it ADDS.
   *   child_res = child.y2 - (obj.coords.y2 - space_bottom), where the
   *               subtrahend is pulled INSIDE the box by the same padding.
   * Measured on a 40x40 parent at pad 24 holding a 10x10 child at [24 24 33 33],
   * entirely within the parent's [0 0 39 39]: the self term yields 8, the child
   * term 18, and the function returns 18 — for content with no scroll position
   * that would reveal anything. That is an artifact, and quoting only the self
   * term would misattribute the number this fixture actually produces.
   *
   * BUT THE SAME ARITHMETIC ALSO COMES OUT POSITIVE WHEN CONTENT REALLY IS
   * CLIPPED AWAY — a 30x30 child in that same parent reaches [24 24 53 53],
   * 14px past the parent's own coords — and nothing in the extent number tells
   * the two apart. So there is no reading of it that is right in both cases,
   * and declining is the only answer that never asserts something false.
   *
   * WHAT MAKES THE DECLINE SAFE IS THAT THE DEFECT IS STILL REPORTED, from the
   * child's side: `obj_clipped` compares each child against the parent's coords
   * on exactly this inverted-box path and fires on the 30x30 child. The
   * canary asserts that pairing (dev/dump_contract_probe.clj,
   * "inverted-content-box") so the decline cannot quietly become a hole. What
   * is given up is the container's-eye duplicate of a fact the child already
   * carries; what is NOT given up is any defect.
   *
   * PER AXIS, not per box. `content_box_inverted` is a whole-box predicate and
   * using it here would kill the vertical answer for a box inverted only
   * horizontally — precisely the precision `scroll_dirs` exists to supply, and
   * reachable under stock on any tall narrow lv_obj. */
  {
    lv_area_t cc;
    lv_obj_get_content_coords(obj, &cc);
    if (cc.y1 > cc.y2)
      dirs &= ~(unsigned)OVERFLOW_DIR_VER;
    if (cc.x1 > cc.x2)
      dirs &= ~(unsigned)OVERFLOW_DIR_HOR;
  }
  return dirs;
}
static bool obj_content_overflows(const lv_obj_t *obj) {
  return obj_overflow_dirs(obj) != 0u;
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
/* text_wrapped: a WRAP-long-mode label reflowed onto MORE lines than its text
 * declares. WRAP keeps the object width and grows its HEIGHT, so no glyph is
 * ever clipped or ellipsized — which is exactly why neither neighbouring
 * clause can see it: label_text_clipped returns early on any non-CLIP mode,
 * and dot_begin stays at its sentinel because nothing ellipsized. A grown
 * label is still a layout defect (the author sized a column narrower than the
 * string, and the reader gets a mid-word break), and it is the one that
 * survives a theme change, since growing needs no padding to go wrong.
 *
 * Exact, and it computes no line height of its own: measure the same text
 * twice through the widget's OWN font and spacing — once unconstrained
 * (LV_COORD_MAX + LV_TEXT_FLAG_EXPAND, whose height is the line count the
 * TEXT asks for through its own newlines) and once at the content-box width
 * (what LVGL actually laid out). A taller laid-out extent means the box forced
 * a break the text did not write. Deriving it from the label's coords instead
 * would need a line-height constant on the Clojure side, and the compiled font
 * tables are the only source of truth for that (.claude/rules/renderer.md) —
 * so the measurement belongs HERE, next to the font.
 *
 * Scoped to WRAP deliberately, and each exclusion has its own reporter: DOTS
 * reflows too but announces itself through dot_begin, the SCROLL modes keep
 * one line and translate it, and CLIP is label_text_clipped's case.
 *
 * The residue, named rather than papered over: a content box narrower than
 * 1px has no defined wrap answer at all, so this clause declines it. :squished
 * does not reliably cover it — obj_squished needs a flex or grid parent, and
 * then fires only on an explicit min_width/min_height violation or on a
 * flex-grown child whose width or height is non-positive — nor does :zero-area
 * always, since padding can eat the content box while the label's own coords
 * stay non-zero. A label crushed that far reports nothing here. */
static bool label_text_wrapped(const lv_obj_t *obj) {
  if (!lv_obj_check_type(obj, &lv_label_class))
    return false;
  if (lv_label_get_long_mode(obj) != LV_LABEL_LONG_MODE_WRAP)
    return false;
  const char *text = lv_label_get_text(obj);
  if (text == NULL || text[0] == '\0')
    return false;
  const lv_font_t *font = lv_obj_get_style_text_font(obj, LV_PART_MAIN);
  if (font == NULL)
    return false;
  lv_area_t cc;
  lv_obj_get_content_coords(obj, &cc);
  int32_t cw = lv_area_get_width(&cc);
  if (cw <= 0)
    return false;
  int32_t letter_space = lv_obj_get_style_text_letter_space(obj, LV_PART_MAIN);
  int32_t line_space = lv_obj_get_style_text_line_space(obj, LV_PART_MAIN);
  lv_point_t natural = {0, 0};
  lv_text_get_size(&natural, text, font, letter_space, line_space, LV_COORD_MAX,
                   LV_TEXT_FLAG_EXPAND);
  lv_point_t laid_out = {0, 0};
  lv_text_get_size(&laid_out, text, font, letter_space, line_space, cw,
                   LV_TEXT_FLAG_NONE);
  return laid_out.y > natural.y;
}
/* A scroll container reveals offscreen children by scrolling, so an object
 * sitting outside the display because a scrollable ancestor has it scrolled
 * away (or scroll-snaps to it — tabview inactive pages) is DESIGNED, not a
 * defect. Walk ancestors: any SCROLLABLE one with live scroll extent, or any
 * scroll-snap container whose child is SNAPPABLE, designs the off-display
 * placement. This is the exclusion that keeps `offscreen` off the tabview
 * inactive pages (see test_inactive_tab_content_offscreen).
 *
 * IT READS obj_content_overflows, SO THE TWO EXTENT GUARDS REACH `offscreen`
 * TOO — a third clause, named here because the diff that added them otherwise
 * mentions only the two they were written for. Both make the extent answer
 * FALSE more often, so fewer ancestors qualify as scroll regions and FEWER
 * nodes are excused from `offscreen`. That is the safe direction: it over-fires
 * into a red gate rather than hiding a defect, the same asymmetry
 * `devcards.invariants` accepts knowingly for its hidden-node rule. Measured on
 * the full corpus across all three families at zero new `offscreen` findings —
 * an ancestor that genuinely scrolls has a valid content box and is untouched
 * by either guard. */
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
/* ── RESOLVED STYLE: what colour, on what, at what opacity ─────────────────
 * A readability gate needs three things geometry cannot give it: the colour of
 * the glyphs, the colour under them, and how much of either survives the
 * opacity chain. Every value below is read back through LVGL's OWN getters and
 * composed with LVGL's OWN arithmetic, so the dump TRACKS the draw path rather
 * than modelling it; and because every one of those getters lives in lvgl/src
 * (wasm.mk's LIB_OBJS, linked into controls.wasm AND reference.wasm), this
 * introduces no symbol the reference oracle would fail to resolve.
 *
 * WHICH WAY EACH KEY FAILS — absence is NOT neutral, and they do not all fail
 * the same way. A producer that guesses one of these is silently wrong:
 *   opa                  absent => LV_OPA_COVER.
 *   text                 absent => NOT "this node draws no glyphs." Only an
 *                        exact lv_label emits it; lv_roller_label is a label
 *                        subclass that draws glyphs but fails that exact-type
 *                        check, so both text and text_clipped stay absent.
 *   text_wrapped         absent => NOT "this label fits on one line." It means
 *                        this label did not GROW past the line count its own
 *                        text asks for — which is also what a CLIP, DOTS or
 *                        SCROLL label reports, since the clause is scoped to
 *                        WRAP. A truncated DOTS label and a one-line WRAP
 *                        label are therefore indistinguishable HERE; read
 *                        text_truncated and text_clipped for those. Absent
 *                        also on a content box under 1px, where the wrap has
 *                        no defined answer (label_text_wrapped names that
 *                        residue).
 *   text_color           absent => THE NEAREST ANCESTOR THAT EMITTED ONE.
 *                        LV_STYLE_TEXT_COLOR is inheritable, so emitting it
 *                        everywhere would repeat the screen's colour on almost
 *                        every node; only a CHANGE is emitted. The root has no
 *                        parent and so always emits, which is what makes the
 *                        walk up terminate.
 *   text_opa             absent => LV_OPA_COVER. Present => that alpha, and it
 *                        ALREADY INCLUDES `opa` — never multiply the two.
 *   bg_color             absent => THIS NODE PAINTS NO MAIN FILL. It does not
 *                        mean "the default background"; nothing was drawn.
 *   bg_opa               absent WITH bg_color => the fill covers fully.
 *                        Present => that alpha, already including `opa`.
 *   text_font            absent => THE NEAREST ANCESTOR THAT EMITTED ONE, the
 *                        same convention and the same reason as text_color:
 *                        LV_STYLE_TEXT_FONT is inheritable, so only a CHANGE is
 *                        emitted and the root always does. It is NOT a
 *                        statement about glyphs — a node that draws none still
 *                        emits it if its resolved face differs from its
 *                        parent's. Present => the name resolve_font answers to,
 *                        which is the key lvgl-codegen.font-metrics is keyed by.
 *   text_font_unnamed    present => the face at this node CHANGED and is one
 *                        this file cannot name: a runtime .bin or TinyTTF face,
 *                        which has no compiled table and therefore no metrics
 *                        row to join to. It is a positive declaration, the same
 *                        third answer backdrop_unresolved is, so an ancestor
 *                        walk terminates here rather than inheriting a face
 *                        that was overridden. Absent alongside text_font =>
 *                        nothing changed at this node.
 *   scroll_dirs          absent => NOT "this node does not scroll." It is
 *                        emitted ONLY beside scrollable_overflow, so absence
 *                        means that flag did not fire — which covers both a
 *                        node with nothing to scroll AND a node whose overflow
 *                        is clipped away rather than reachable (that one is
 *                        `overflow`). Present => "hor", "ver" or "both", the
 *                        axes LVGL reports live scroll extent on. A reader
 *                        that treats absence as "no axis" and a declaration
 *                        that treats it as "every axis" would disagree about
 *                        the same node.
 *   text_on              absent => this node's text, if any, rides on MAIN.
 *   text_on.font         absent => the part draws with the face the text_font
 *                        chain resolves for this node. Present => the part
 *                        carries its OWN face and the top-level text_font is
 *                        not the one these glyphs are cut from. `font_unnamed`
 *                        is its unnameable case, exactly as above.
 *   backdrop_unresolved  absent => either the node draws no text, or the fill
 *                        under its glyphs fully covers and IS bg_color (or
 *                        text_on.bg). Present => THE THIRD ANSWER: this node
 *                        does not determine what is behind its glyphs, and a
 *                        reader that computes a contrast ratio anyway is
 *                        guessing. It is a positive declaration precisely so a
 *                        producer can turn it into a finding rather than skip.
 *
 * WHY THERE IS NO ANCESTOR WALK. The obvious way to finish the backdrop — walk
 * ancestors to the first with a covering bg — is WRONG here, and not
 * marginally: PARTS ARE NOT NODES. dump_obj recurses lv_obj children, so no
 * part-level rect ever enters the tree, yet lv_theme_default gives
 * lv_buttonmatrix's LV_PART_ITEMS a covering bg (`btn`) and this theme repaints
 * it (`btnm_items`) — every button label rides on a rect no walk from the
 * buttonmatrix node can see. `text_on` answers exactly that class by naming the
 * part the glyphs are on and reporting ITS fill; `backdrop_unresolved` is the
 * honest remainder. Two further breakages are LATENT in this corpus rather than
 * fired, recorded so they are not rediscovered as bugs: lv_dropdown_open
 * REPARENTS the open list to the screen, so it covers nodes it has no ancestry
 * with (every dropdown card here is closed), and a PROXY_MODE_ALIGNABLE proxy
 * rect is placed by the host compositor after LVGL has finished (no card sets
 * it).
 *
 * WHAT THIS STILL CANNOT SEE, said out loud. LV_PART_ITEMS on lv_buttonmatrix
 * and lv_table is ONE style read for a row of independently-stated items:
 * lv_buttonmatrix.c re-resolves the part per button from its ctrl bits
 * (checked/inactive) and only the selected button inherits the widget's own
 * pressed/focused state. There is no node to hang those variants on, so what is
 * reported is the part as the object's CURRENT state resolves it — exact for a
 * default-state widget, and the default-button style for any other. */
/* Effective opacity — LVGL's accumulation INCLUDING ITS CLAMPS, which is not a
 * product. lv_obj_get_style_opa_recursive short-circuits to TRANSP the instant
 * any link is <= LV_OPA_MIN (2), SKIPS links >= LV_OPA_MAX (253) from the
 * multiply entirely (so 255 is exactly neutral), and snaps to TRANSP/COVER at
 * those same rails. Reimplemented rather than called because that function
 * knows only LV_STYLE_OPA: LV_STYLE_OPA_LAYERED is a SECOND, independent link
 * on the same chain (lv_refr.c refr_obj returns outright when it is <=
 * LV_OPA_MIN and blends the widget's whole layer at it otherwise) and the
 * ui_ast exposes it as a settable prop, so a screen can fade a subtree through
 * a link the library helper does not fold.
 *
 * ORDER, stated rather than glossed: this folds self -> root, the direction
 * lv_obj_get_style_opa_recursive folds; the draw path folds root -> self as it
 * descends. MIX2 truncates, so the two directions are not exactly associative
 * and a chain of unequal links can disagree in the low bit. That relationship
 * is LVGL's own — the library helper stands in the same place against the same
 * draw path — and it is why the rail probe
 * (tools/devcards/dev/opa_rail_probe.clj) adjudicates every value it covers
 * against the FRAMEBUFFER instead of against arithmetic. */
static lv_opa_t obj_effective_opa(const lv_obj_t *obj) {
  lv_opa_t acc = LV_OPA_COVER;
  for (const lv_obj_t *o = obj; o != NULL; o = lv_obj_get_parent(o)) {
    /* BOTH LINKS TAKE THE SAME RAILS, but they reach them at different
     * levels, and that difference is a trap worth naming because reading
     * refr_obj alone gets it wrong. For LV_STYLE_OPA the skip is right there:
     * `if(opa_main < LV_OPA_MAX)`. For LV_STYLE_OPA_LAYERED there is no such
     * guard — calculate_layer_type layers on `!= LV_OPA_COVER` and refr_obj
     * blends at exactly that value, which reads as "253 fades". It does not:
     * every software blender takes an `opa >= LV_OPA_MAX` fast path that
     * copies the source verbatim, so the rail simply lives one level lower.
     * MEASURED, not reasoned — an earlier version of this function split the
     * two links on that misreading, and opa_rail_probe's `layered-253` vs
     * `opa-255` comparison came back byte-identical and refuted it. */
    const lv_opa_t links[2] = {lv_obj_get_style_opa(o, LV_PART_MAIN),
                               lv_obj_get_style_opa_layered(o, LV_PART_MAIN)};
    for (uint32_t i = 0; i < 2u; i++) {
      if (links[i] <= LV_OPA_MIN)
        return LV_OPA_TRANSP;
      if (links[i] < LV_OPA_MAX)
        acc = LV_OPA_MIX2(acc, links[i]);
    }
  }
  if (acc <= LV_OPA_MIN)
    return LV_OPA_TRANSP;
  if (acc >= LV_OPA_MAX)
    return LV_OPA_COVER;
  return acc;
}
/* One draw's own alpha scaled by the node's effective opa, at the same rails
 * lv_obj_init_draw_rect_dsc / _label_dsc use: bail at <= LV_OPA_MIN, skip the
 * multiply at >= LV_OPA_MAX. */
static lv_opa_t opa_scaled(lv_opa_t own, lv_opa_t node_opa) {
  if (own <= LV_OPA_MIN)
    return LV_OPA_TRANSP;
  if (node_opa < LV_OPA_MAX)
    own = LV_OPA_MIX2(own, node_opa);
  if (own <= LV_OPA_MIN)
    return LV_OPA_TRANSP;
  return own;
}
/* Fold a non-MAIN part's own LV_STYLE_OPA into an already-accumulated node
 * opa, mirroring get_layer_opa's non-MAIN branch (lv_obj_draw.c) under
 * lv_obj_get_style_opa_recursive's rails. */
static lv_opa_t part_opa(const lv_obj_t *obj, lv_part_t part,
                         lv_opa_t node_opa) {
  const lv_opa_t own = lv_obj_get_style_opa(obj, part);
  if (own <= LV_OPA_MIN)
    return LV_OPA_TRANSP;
  if (own < LV_OPA_MAX)
    return LV_OPA_MIX2(node_opa, own);
  return node_opa;
}
/* The colour LVGL actually hands the draw: the styled colour with the
 * ACCUMULATED recolor applied. Byte-identical to lv_obj_draw.c's
 * normal_apply_layer_recolor — its layer branch and its
 * lv_obj_get_style_recolor_recursive fallback compose the chain in the same
 * order (descendant over ancestor), so the fallback is faithful outside a
 * refresh. Not cosmetic: this theme's DISABLED style sets recolor at opa 100,
 * so a gate reading the bare style colour would grade every disabled label
 * against a colour that is never drawn. */
static lv_color_t style_color_drawn(const lv_obj_t *obj, lv_part_t part,
                                    lv_color_t styled) {
  const lv_color32_t rc = lv_obj_get_style_recolor_recursive(obj, part);
  return lv_color_mix(lv_color_make(rc.red, rc.green, rc.blue), styled,
                      rc.alpha);
}
typedef struct {
  lv_part_t part;
  const char *name;
} text_part_t;
/* The part a class draws its PRIMARY text on, when that is not LV_PART_MAIN.
 * Enumerated from the lv_obj_init_draw_label_dsc call sites under
 * lvgl/src/widgets, never guessed. `lv_obj_has_class` so a subclass inherits
 * the row. Two exclusions are DECISIONS, listed so their absence does not read
 * as an oversight: (1) classes whose primary text is already on MAIN —
 * lv_label, lv_checkbox, lv_dropdown's own label, lv_textarea and lv_roller via
 * their internal label children; (2) DECORATIVE glyph parts, which carry no
 * content a readability clause judges — lv_dropdown's LV_PART_INDICATOR
 * chevron, lv_textarea's LV_PART_CURSOR and LV_PART_TEXTAREA_PLACEHOLDER. The
 * roller row IS listed, because LV_PART_SELECTED redraws the banded row over
 * its own fill, so the selected row's backdrop is the band and not the roller's
 * MAIN — the unbanded rows are the child label's, reported on that node. */
static bool obj_text_part(const lv_obj_t *obj, text_part_t *out) {
#if LV_USE_BUTTONMATRIX
  if (lv_obj_has_class(obj, &lv_buttonmatrix_class)) {
    out->part = LV_PART_ITEMS;
    out->name = "items";
    return true;
  }
#endif
#if LV_USE_TABLE
  if (lv_obj_has_class(obj, &lv_table_class)) {
    out->part = LV_PART_ITEMS;
    out->name = "items";
    return true;
  }
#endif
#if LV_USE_ROLLER
  if (lv_obj_has_class(obj, &lv_roller_class)) {
    out->part = LV_PART_SELECTED;
    out->name = "selected";
    return true;
  }
#endif
#if LV_USE_SCALE
  if (lv_obj_has_class(obj, &lv_scale_class)) {
    out->part = LV_PART_INDICATOR;
    out->name = "indicator";
    return true;
  }
#endif
  (void)obj;
  (void)out;
  return false;
}
/* Does this node put glyphs on screen at all? Only these owe a backdrop, so
 * only these can carry `backdrop_unresolved`. An EMPTY label draws nothing and
 * is excluded on purpose — reporting an unresolved backdrop for zero glyphs
 * would be a finding with no subject. */
static bool obj_draws_text(const lv_obj_t *obj) {
  if (lv_obj_check_type(obj, &lv_label_class)) {
    const char *t = lv_label_get_text(obj);
    return t != NULL && t[0] != '\0';
  }
#if LV_USE_CHECKBOX
  if (lv_obj_check_type(obj, &lv_checkbox_class))
    return true;
#endif
#if LV_USE_DROPDOWN
  if (lv_obj_check_type(obj, &lv_dropdown_class))
    return true;
#endif
  text_part_t tp;
  return obj_text_part(obj, &tp);
}
/* ── the resolved face, by the name that joins it to the compiled metrics ────
 * `resolve_font` (renderer/src/renderer.c) owns the name -> lv_font_t*
 * direction and is its ONE home; `lvgl-codegen.font-metrics` PARSES that
 * function's arms and keys every metric record by the same name string. A dump
 * consumer holds the resolved POINTER and has nothing to join on, which is what
 * this table is for: it is the REVERSE direction, and nothing but the dump
 * needs it.
 *
 * IT IS A SECOND LISTING OF THOSE NAMES AND CANNOT BE ANYTHING ELSE FROM HERE.
 * resolve_font is static to renderer.c, and the reference oracle
 * (src/reference_ui.c) is linked INSTEAD of renderer.c — so an accessor over
 * there would have to be stubbed here and would report nothing on the very
 * oracle the dump is compared against. Two things bound what the duplication
 * can cost, and they cover DIFFERENT drifts — say which, because the second one
 * does not cover the first. Behaviourally: a face this table OMITS emits the
 * `_unnamed` spelling below instead of going silent, so a missing row degrades
 * to the declared third answer and never to a wrong name. That is no help at
 * all against a MIS-PAIRED row, which reports a real name for the wrong face
 * and is indistinguishable from a correct one. Only the mechanical guard
 * catches that: `lvgl-codegen.pdl-t0-test` cross-checks every (symbol, name)
 * PAIR against the arms resolve_font actually answers, so both a dropped row
 * and a transposed one red there.
 *
 * ONLY THE COMPILED FACES ARE LISTED, and that is the whole set a join can
 * reach: a runtime `.bin` or TinyTTF face has no C table for
 * `lvgl-codegen.font-metrics` to read, so its record carries null metrics and a
 * `metrics-unavailable` reason. renderer.c's binfont registry does hold those
 * names — this file cannot see it, and surfacing them would hand a consumer a
 * join key with nothing on the other side of the join. */
typedef struct {
  const lv_font_t *font;
  const char *name;
} font_name_t;
static const font_name_t font_names[] = {
    {&font_b612mono_bold_12, "b612mono_bold_12"},
    {&font_b612mono_bold_14, "b612mono_bold_14"},
    {&font_b612mono_bold_16, "b612mono_bold_16"},
    {&font_b612mono_bold_18, "b612mono_bold_18"},
    {&font_b612mono_bold_20, "b612mono_bold_20"},
    {&font_orbitron_bold_22, "orbitron_bold_22"},
    {&font_orbitron_bold_28, "orbitron_bold_28"},
    {&font_orbitron_bold_32, "orbitron_bold_32"},
    {&lv_font_montserrat_14, "montserrat_14"},
    {&lv_font_montserrat_16, "montserrat_16"},
    {&lv_font_montserrat_18, "montserrat_18"},
    {&lv_font_montserrat_22, "montserrat_22"},
    {&lv_font_montserrat_24, "montserrat_24"},
};
/* The name resolve_font answers to for this face, or NULL for one this table
 * does not list. A NULL is REPORTED at every emit site, never dropped. */
static const char *font_name(const lv_font_t *font) {
  if (font == NULL)
    return NULL;
  for (size_t i = 0; i < sizeof(font_names) / sizeof(font_names[0]); i++) {
    if (font_names[i].font == font)
      return font_names[i].name;
  }
  return NULL;
}
/* ,"<key>":"#rrggbb" — the leading comma is the dump's separator convention,
 * which also makes this usable inside the text_on object after its first
 * member. */
static void tree_append_color(const char *key, lv_color_t c) {
  char buf[48];
  (void)snprintf(buf, sizeof(buf), ",\"%s\":\"#%02x%02x%02x\"", key,
                 (unsigned)c.red, (unsigned)c.green, (unsigned)c.blue);
  tree_append(buf);
}
/* ,"<key>":<0..255> */
static void tree_append_opa(const char *key, lv_opa_t opa) {
  char buf[32];
  (void)snprintf(buf, sizeof(buf), ",\"%s\":%u", key, (unsigned)opa);
  tree_append(buf);
}
/* The draw-stream palette, as deterministic unique (hex, theme-recolor?)
 * pairs.  Object pointers and PARTIAL-strip task order are runtime details and
 * are dropped here; the observer retains task keys internally so repeats are
 * merged before this set projection.
 *
 * THIS IS NOT PART OF THE SEMANTIC TREE, and that boundary is load-bearing
 * rather than tidiness.  The palette describes what has been PAINTED since the
 * last window boundary, so it is a property of the route taken to a screen and
 * not of the screen.  Two structurally identical trees reached by different
 * routes carry different palettes — measured: morph parity's
 * dual_oracle_patched_equals_fresh saw 29 records on the patched route against
 * 18 on the fresh one for the same tree.  The value of the draw stream is
 * precisely that it reports what the tree cannot, so it can never be an
 * equality-checked member of the tree.  It gets its own export. */
static void tree_append_draw_palette(void) {
  const palette_observation_t *records = palette_observer_records();
  const uint32_t count = palette_observer_count();
  char count_buf[64];
  (void)snprintf(count_buf, sizeof(count_buf), "{\"records\":%u,\"colors\":[",
                 (unsigned)count);
  tree_append(count_buf);
  bool first = true;
  uint32_t previous = 0;
  bool have_previous = false;
  while (true) {
    uint32_t next = UINT32_MAX;
    for (uint32_t i = 0; i < count; i++) {
      const uint32_t key =
          (records[i].rgb << 1) | (records[i].theme_recolor ? 1u : 0u);
      if ((!have_previous || key > previous) && key < next)
        next = key;
    }
    if (next == UINT32_MAX)
      break;
    if (!first)
      tree_append(",");
    char buf[80];
    (void)snprintf(buf, sizeof(buf), "{\"hex\":\"#%06X\",\"theme_recolor\":%s}",
                   (unsigned)(next >> 1), (next & 1u) != 0 ? "true" : "false");
    tree_append(buf);
    first = false;
    previous = next;
    have_previous = true;
  }
  tree_append("]");
  if (palette_observer_overflowed())
    tree_append(",\"overflow\":true");
  tree_append("}");
  /* The observer's capacity can hold more distinct colours than this buffer
   * can render, so a cut is reachable rather than theoretical — and cut JSON
   * here would be read as a SHORTER palette, i.e. a cleaner one. Nothing can
   * be appended to a full buffer, so rewrite the whole thing as the canonical
   * overflow form the producer already answers :cantTell to. */
  if (tree_out.truncated) {
    tree_out.pos = 0;
    tree_out.truncated = false;
    tree_buf[0] = '\0';
    char over[80];
    (void)snprintf(over, sizeof(over),
                   "{\"records\":%u,\"colors\":[],\"overflow\":true}",
                   (unsigned)count);
    tree_append(over);
  }
}
/* Diagnostic-only, and the same contract as controls_dump_tree: the returned
 * pointer is into the shared dump buffer, so the host copies it out before the
 * next call into this module. */
uint32_t controls_dump_draw_palette(void) {
  tree_out.pos = 0;
  tree_out.truncated = false;
  tree_buf[0] = '\0';
  tree_append_draw_palette();
  return (uint32_t)(uintptr_t)tree_buf;
}
/* THE INTERPRETER DECLARING ITS OWN COMPOSITION — the membership keys for the
 * objects the renderer builds ITSELF, so a consumer can name them.
 *
 * Two families, one reason. Host-proxy parts (glass / handle / cell) and gesture
 * affordances (anchor / band / aim) are both created with bare lv_obj_create
 * (or lv_line_create) and never pass through finalize_widget, so they carry no
 * uid and NOTHING downstream can address them. Without these keys a geometry
 * rule sees only rectangles and cannot tell a DESIGNED overlay stack
 * (UI-QUALITY-CONTRACTS §1.5b) from an accidental collision — and inferring it
 * from paint order is what §1.2 forbids. The proxy family carries its owner id
 * too, so two proxies are told apart rather than lumped together; the gesture
 * family needs none, because at most one drag is live.
 *
 * Every key is emitted only where it applies, so an ordinary node stays
 * compact — and a `gesture_part` appears only on a card that is MID-DRAG,
 * which no static render ever is.
 *
 * EXTRACTED FROM dump_obj rather than written inline: that function sits at the
 * .clang-tidy VariableThreshold measured off this tree's worst function, and
 * those thresholds only move DOWN. */
static void dump_composition_keys(const lv_obj_t *obj) {
  const char *root_id = renderer_proxy_root(obj);
  if (root_id) {
    char pbuf[128];
    (void)snprintf(pbuf, sizeof(pbuf), ",\"proxy_root\":\"%s\"", root_id);
    tree_append(pbuf);
  }
  const char *owner = NULL;
  const char *part = renderer_proxy_part(obj, &owner);
  if (part && owner) {
    char pbuf[192];
    (void)snprintf(pbuf, sizeof(pbuf),
                   ",\"proxy_part\":\"%s\",\"proxy_owner\":\"%s\"", part,
                   owner);
    tree_append(pbuf);
  }
  const char *gpart = gesture_affordance_part(obj);
  if (gpart) {
    char gbuf[64];
    (void)snprintf(gbuf, sizeof(gbuf), ",\"gesture_part\":\"%s\"", gpart);
    tree_append(gbuf);
  }
  /* The AUTHORED member of this family, and the one whose absence is
   * load-bearing: emitted only where WidgetNode.designed_overlay was set, so
   * an ordinary node stays silent and a consumer reading the key as falsey
   * when absent gets the right answer. The two families above name objects
   * that carry no uid; this one names an object that HAS one, and rides here
   * anyway because the dump is the only thing the overlap lane reads — a
   * declaration echoed off the object that actually rendered cannot name a
   * node that is not in the tree. */
  if (renderer_designed_overlay(obj))
    tree_append(",\"designed_overlay\":true");
}
/* ── PAINT EXTENT — what is DRAWN, against `coords`, which is DECLARED ──────
 *
 * A widget may paint outside its own box, and LVGL bounds that with ONE
 * scalar: before dispatching LV_EVENT_DRAW_MAIN, `refr_obj` truncates the
 * layer's clip area to `coords` grown by `lv_obj_get_ext_draw_size` on every
 * side (lv_refr.c). That box is therefore a HARD guarantee of the draw
 * pipeline rather than a convention, and its ZERO case is the one that carries
 * the most weight here:
 *
 *   NEITHER KEY EMITTED => the paint extent IS `coords`, exactly. The scalar
 *   is 0, so the clip forbids a single pixel outside it. That is the common
 *   case and it costs no dump bytes.
 *
 * When the scalar is non-zero the widget paints somewhere outside — and the
 * answer splits in two, because the scalar is a CONSERVATIVE SYMMETRIC BOUND
 * and not the extent. `lv_obj_calculate_ext_draw_size` folds an asymmetric
 * shadow offset into a max, and lv_slider's own handler adds half the knob
 * diameter plus 2 "for rounding error" (lv_slider.c), so on this repo's 16px
 * slider cards it reports 16 where the knob really overhangs 6. Publishing
 * that as the paint box over-reports by 10px a side; publishing `coords`
 * instead under-reports the overhang completely. Both are wrong in ways no
 * consumer can detect from the dump, so the two answers take DIFFERENT NAMES:
 *
 *   "paint_box"   — the EXACT extent, resolved from the rects the widget
 *                   actually drew. Decide with it.
 *   "paint_bound" — the extent is UNRESOLVED and lies somewhere inside this
 *                   box. Sound for proving a NEGATIVE (nothing painted outside
 *                   it), unsound for asserting one — so a consumer needing an
 *                   answer strictly inside it owes a FINDING, not a number.
 *
 * Exactly one is emitted, and only when it differs from `coords`. This is the
 * `text_font` / `text_font_unnamed` shape from the same function: the fact
 * that the answer is UNAVAILABLE is itself emitted, because a rule that cannot
 * tell "no overhang" from "overhang unknown" reports a clean run over an
 * unmeasured one. */
static void paint_join(lv_area_t *acc, const lv_area_t *add) {
  if (add->x1 < acc->x1)
    acc->x1 = add->x1;
  if (add->y1 < acc->y1)
    acc->y1 = add->y1;
  if (add->x2 > acc->x2)
    acc->x2 = add->x2;
  if (add->y2 > acc->y2)
    acc->y2 = add->y2;
}
/* Does this PART paint outside the rect it is positioned into? The three
 * non-transform contributors `lv_obj_calculate_ext_draw_size` sums, restated
 * per property rather than called through that function, because TRANSFORM
 * must be excluded here and it cannot be subtracted back out afterwards:
 * `position_knob` already folds transform_width/height into the knob rect it
 * stores, so a pressed knob (the stock theme's `grow` style) is fully
 * described by that rect while `lv_obj_calculate_ext_draw_size` still reports
 * the transform and would force a needless UNRESOLVED. */
static bool part_paints_outside(lv_obj_t *obj, lv_part_t part) {
  const bool shadow = lv_obj_get_style_shadow_width(obj, part) != 0 &&
                      lv_obj_get_style_shadow_opa(obj, part) > LV_OPA_MIN;
  const bool outline = lv_obj_get_style_outline_width(obj, part) != 0 &&
                       lv_obj_get_style_outline_opa(obj, part) > LV_OPA_MIN;
  const bool drop = lv_obj_get_style_drop_shadow_opa(obj, part) > LV_OPA_MIN;
  return shadow || outline || drop;
}
/* THE ONE CLASS THIS TREE CAN RESOLVE EXACTLY, and it is the class the whole
 * distinction exists for: lv_slider stores the knob rect it DREW
 * (`position_knob` writes it, `draw_knob` copies it into the widget), so the
 * exact extent is `coords` joined with that rect and nothing is inferred.
 *
 * It refuses in three cases rather than answering approximately:
 *   - MAIN or INDICATOR contributes extent (a focus outline, a shadow, a
 *     transform) — then the knob rects are not the whole story;
 *   - the KNOB carries a shadow/outline/drop-shadow of its own, which paints
 *     outside the stored rect;
 *   - LV_SLIDER_MODE_RANGE is off, in which case `left_knob_area` is never
 *     written by `draw_knob` and holds construction zeroes; joining it would
 *     drag the reported box to the screen origin. The mode gate is what keeps
 *     that unreachable, and `dump_paint_extent`'s containment check is the
 *     second line under it. */
static bool paint_box_of_slider(const lv_obj_t *obj, const lv_area_t *coords,
                                lv_area_t *out) {
  /* THE CLASS TEST IS LOAD-BEARING AND IS THE FIRST THING HERE. Without it
   * the cast below reinterprets whatever object it is handed as an
   * lv_slider_t and reads two lv_area_t out of unrelated memory. Measured
   * before it existed: 10 lv_scale nodes reported an "exact" paint_box built
   * from that garbage, and the containment guard did not catch them because
   * garbage landing INSIDE the bound is indistinguishable from an answer.
   * Worse than a wrong box, it can silently produce the RIGHT-LOOKING one —
   * a knob rect that happens to fall within coords joins to nothing, and the
   * node is then published as painting nowhere outside its box. */
  if (!lv_obj_check_type(obj, &lv_slider_class))
    return false;
  lv_obj_t *m = (lv_obj_t *)obj;
  if (lv_obj_calculate_ext_draw_size(m, LV_PART_MAIN) != 0 ||
      lv_obj_calculate_ext_draw_size(m, LV_PART_INDICATOR) != 0 ||
      part_paints_outside(m, LV_PART_KNOB))
    return false;
  const lv_slider_t *slider = (const lv_slider_t *)obj;
  *out = *coords;
  paint_join(out, &slider->right_knob_area);
  if (lv_slider_get_mode(m) == LV_SLIDER_MODE_RANGE)
    paint_join(out, &slider->left_knob_area);
  return true;
}
/* ── OVERFLOW PADDING — the BUDGET, as against the EXTENT ──────────────────
 *
 * `paint_box` / `paint_bound` answer "where did this node's pixels land in
 * THIS render". A budget answers a different question: "how far outside its
 * layout box is this node ENTITLED to paint, at any value it may hold". The
 * two are not interchangeable, and a snapshot cannot stand in for the second.
 * MEASURED on this repo's own corpus rather than assumed: of the sliders whose
 * extent resolves exactly, the great majority sit MID-RANGE and escape only
 * across the track, while a handful of deliberate value-axis cells sit at the
 * ends and escape roughly half a knob diameter ALONG it, on one side. So the
 * along-track escape is real and is invisible on most cards — a paint-only
 * rule allocates nothing for it there, and the widget needs only to be dragged
 * for the space to be taken.
 *
 * ONE NUMBER PER SIDE, DEFAULT ZERO, and the zero case is the common one: for
 * the great majority of nodes `lv_obj_get_ext_draw_size` is 0, the clip
 * forbids a pixel outside `coords`, and the budget is [0,0,0,0]. A consumer
 * inflating by an absent budget gets `coords` back, which is why the rules
 * reading it collapse to the node-box rules at zero rather than branching.
 *
 * PER SIDE RATHER THAN ONE REACH, and this is a measurement rather than a
 * preference: a horizontal slider's own escape is 6px across the track and up
 * to 15px along it, and along it only on the side the knob has travelled to.
 * A shadow offset splits the same way — LVGL folds the offset into a
 * symmetric max precisely because ONE scalar is all `refr_obj` can clip with.
 * Four sides subsume both the per-axis and the single-reach shapes, so this
 * publishes four.
 *
 * WHAT FEEDS IT HERE — resolved styles, and nothing else. The three
 * contributors `lv_obj_calculate_ext_draw_size` sums (shadow, outline, drop
 * shadow) plus the transform pad, restated PER SIDE rather than folded into
 * that function's symmetric maximum. Where those account for the whole of
 * LVGL's own reservation the budget is RESOLVED and emitted; where a widget
 * CLASS reserved more than its styles explain — lv_scale's literal 100,
 * lv_label's font_h/4, lv_arc's knob correction — nothing is emitted, the
 * node keeps its `paint_bound`, and the residual is what a DECLARED per-class
 * entry would have to carry. Absence is therefore "no budget resolved", never
 * "budget zero": the zero case emits nothing because it has no reservation to
 * explain, and a reader can tell the two apart because only the second
 * carries a paint key. */
typedef struct {
  int32_t l, t, r, b;
} overflow_pad_t;
static int32_t pad_pos(int32_t v) { return v > 0 ? v : 0; }
static void pad_join_max(overflow_pad_t *acc, const overflow_pad_t *add) {
  if (add->l > acc->l)
    acc->l = add->l;
  if (add->t > acc->t)
    acc->t = add->t;
  if (add->r > acc->r)
    acc->r = add->r;
  if (add->b > acc->b)
    acc->b = add->b;
}
static int32_t pad_widest(const overflow_pad_t *p) {
  int32_t m = p->l;
  if (p->t > m)
    m = p->t;
  if (p->r > m)
    m = p->r;
  if (p->b > m)
    m = p->b;
  return m;
}
/* `lv_obj_calculate_ext_draw_size` for ONE part, per side. Term for term and
 * in the same combining order as that function — max(shadow, outline), then
 * the drop shadow ADDED to it, then the transform pad — with each term's
 * offset applied to the side it actually moves toward instead of being folded
 * into `LV_MAX(LV_ABS(ofs_x), LV_ABS(ofs_y))`. Every side of the result is
 * therefore <= the scalar that function returns, which is what keeps the
 * budget inside LVGL's own clip guarantee. */
static overflow_pad_t style_overflow_pad(lv_obj_t *obj, lv_part_t part) {
  overflow_pad_t p = {0, 0, 0, 0};
  const int32_t sh_w = lv_obj_get_style_shadow_width(obj, part);
  if (sh_w != 0 && lv_obj_get_style_shadow_opa(obj, part) > LV_OPA_MIN) {
    const int32_t base =
        sh_w / 2 + 1 + lv_obj_get_style_shadow_spread(obj, part);
    const int32_t ox = lv_obj_get_style_shadow_offset_x(obj, part);
    const int32_t oy = lv_obj_get_style_shadow_offset_y(obj, part);
    const overflow_pad_t s = {pad_pos(base - ox), pad_pos(base - oy),
                              pad_pos(base + ox), pad_pos(base + oy)};
    pad_join_max(&p, &s);
  }
  const int32_t out_w = lv_obj_get_style_outline_width(obj, part);
  if (out_w != 0 && lv_obj_get_style_outline_opa(obj, part) > LV_OPA_MIN) {
    const int32_t s = lv_obj_get_style_outline_pad(obj, part) + out_w;
    const overflow_pad_t o = {s, s, s, s};
    pad_join_max(&p, &o);
  }
  if (lv_obj_get_style_drop_shadow_opa(obj, part) > 0) {
    const int32_t r = lv_obj_get_style_drop_shadow_radius(obj, part) + 1;
    const int32_t ox = lv_obj_get_style_drop_shadow_offset_x(obj, part);
    const int32_t oy = lv_obj_get_style_drop_shadow_offset_y(obj, part);
    p.l += pad_pos(r - ox);
    p.t += pad_pos(r - oy);
    p.r += pad_pos(r + ox);
    p.b += pad_pos(r + oy);
  }
  const int32_t tw = lv_obj_get_style_transform_width(obj, part);
  const int32_t th = lv_obj_get_style_transform_height(obj, part);
  if (tw > 0) {
    p.l += tw;
    p.r += tw;
  }
  if (th > 0) {
    p.t += th;
    p.b += th;
  }
  return p;
}
/* The parts a budget is unioned over. LVGL resolves a style for ANY part
 * whether or not the class draws it, so an unused part simply contributes
 * zero; enumerating them is what keeps a themed INDICATOR or SELECTED shadow
 * from being missed. LV_PART_KNOB is deliberately ABSENT — a knob's rect
 * escapes `coords` on its own, so its padding is not a padding OF the node
 * box and folding it in here would under-report the escape rather than
 * over-report it. `knob_overflow_pad` is where that case is answered. */
static const lv_part_t OVERFLOW_PAD_PARTS[] = {
    LV_PART_MAIN,     LV_PART_SCROLLBAR, LV_PART_INDICATOR,
    LV_PART_SELECTED, LV_PART_ITEMS,     LV_PART_CURSOR};
static overflow_pad_t style_overflow_pad_all_parts(lv_obj_t *obj) {
  overflow_pad_t p = {0, 0, 0, 0};
  for (size_t i = 0;
       i < sizeof(OVERFLOW_PAD_PARTS) / sizeof(OVERFLOW_PAD_PARTS[0]); i++) {
    const overflow_pad_t q = style_overflow_pad(obj, OVERFLOW_PAD_PARTS[i]);
    pad_join_max(&p, &q);
  }
  return p;
}
/* THE ONE CLASS OF WIDGET WHOSE RESERVATION IS GEOMETRY RATHER THAN STYLE,
 * and the reason the budget is worth having at all.
 *
 * `position_knob` centres the knob on the indicator end and then grows it by
 * the LV_PART_KNOB padding, so the escape splits by AXIS: across the track it
 * is exactly that padding, while along the track half a knob diameter is in
 * play before any padding is added — and which SIDE that lands on is the
 * current value, not a property of the widget. A budget must claim both ends,
 * so along the track it takes LVGL's own reservation, which is the tightest
 * value-independent number this tree can state without restating
 * `lv_slider`'s arithmetic; across it, the knob part's resolved padding,
 * which is exact.
 *
 * Refuses on anything but a slider, so the caller falls back to the styles-
 * only budget. The switch has the same knob shape but stores no knob rect, so
 * nothing here could be checked against a drawn box; it keeps its bound. */
static bool knob_overflow_pad(lv_obj_t *obj, int32_t ext, overflow_pad_t *out) {
  if (!lv_obj_check_type(obj, &lv_slider_class))
    return false;
  const int32_t across =
      LV_MAX(LV_MAX(lv_obj_get_style_pad_left(obj, LV_PART_KNOB),
                    lv_obj_get_style_pad_right(obj, LV_PART_KNOB)),
             LV_MAX(lv_obj_get_style_pad_top(obj, LV_PART_KNOB),
                    lv_obj_get_style_pad_bottom(obj, LV_PART_KNOB))) +
      LV_MAX(lv_obj_get_style_transform_width(obj, LV_PART_KNOB),
             lv_obj_get_style_transform_height(obj, LV_PART_KNOB));
  if (across > ext)
    return false; /* the styles claim more than LVGL reserved — do not guess */
  const bool hor = lv_obj_get_width(obj) >= lv_obj_get_height(obj);
  const overflow_pad_t p = hor ? (overflow_pad_t){ext, across, ext, across}
                               : (overflow_pad_t){across, ext, across, ext};
  *out = p;
  return true;
}
/* The node's budget, or false when the resolved styles do not account for the
 * whole of `ext` — which is exactly the widget-code blanket case, and the one
 * a DECLARED per-class entry exists to retire. */
static bool overflow_padding_of(lv_obj_t *obj, int32_t ext,
                                overflow_pad_t *out) {
  if (knob_overflow_pad(obj, ext, out))
    return true;
  const overflow_pad_t p = style_overflow_pad_all_parts(obj);
  /* EQUALITY, NOT ">=", AND THE STRICTNESS RUNS BOTH WAYS. Short of `ext` is
   * the widget-code blanket this refuses to guess at. OVER `ext` is the case
   * that looks harmless and is not: LVGL reserved less than these styles ask
   * for, so either a part was resolved that the class never draws or two
   * offsets attained their maxima on opposite sides, and a budget wider than
   * the clip claims space no pixel can reach. Both fall back to the bound. */
  if (pad_widest(&p) != ext)
    return false;
  *out = p;
  return true;
}
static void dump_paint_extent(const lv_obj_t *obj, const lv_area_t *coords) {
  const int32_t ext = lv_obj_get_ext_draw_size(obj);
  if (ext == 0)
    return; /* the clip forbids paint outside coords — nothing to say */
  lv_area_t bound = *coords;
  lv_area_increase(&bound, ext, ext);
  overflow_pad_t pad;
  const bool budgeted = overflow_padding_of((lv_obj_t *)obj, ext, &pad);
  lv_area_t box;
  bool exact = paint_box_of_slider(obj, coords, &box);
  /* A RESOLVED BUDGET RESOLVES THE EXTENT TOO, for every class whose escape
   * is a style: a shadow, an outline and a transform pad each paint out to
   * the side they extend and no further, so `coords` grown per side IS the
   * extent. The slider is the exception and keeps its own resolver, because
   * its budget is a value-independent CLAIM while its `left/right_knob_area`
   * is where the knob went in this render — two different numbers, and
   * publishing the claim as the extent would over-report the snapshot. */
  if (budgeted && !exact && !lv_obj_check_type(obj, &lv_slider_class)) {
    box = *coords;
    box.x1 -= pad.l;
    box.y1 -= pad.t;
    box.x2 += pad.r;
    box.y2 += pad.b;
    exact = true;
  }
  if (budgeted) {
    char obuf[80];
    (void)snprintf(obuf, sizeof(obuf), ",\"overflow_padding\":[%d,%d,%d,%d]",
                   (int)pad.l, (int)pad.t, (int)pad.r, (int)pad.b);
    tree_append(obuf);
  }
  /* THE RESOLVER IS CHECKED AGAINST LVGL'S OWN GUARANTEE, not trusted. Paint
   * is clipped to `bound`, so an "exact" box escaping it means this code and
   * the draw pipeline disagree — which is the one situation where publishing a
   * confident number is worse than publishing none. Demoting to the bound also
   * covers a slider whose stored knob rect is stale because it has never been
   * drawn (a hidden one), without needing a second way to ask that. */
  if (exact && !(box.x1 >= bound.x1 && box.y1 >= bound.y1 &&
                 box.x2 <= bound.x2 && box.y2 <= bound.y2))
    exact = false;
  if (!exact)
    box = bound;
  if (box.x1 == coords->x1 && box.y1 == coords->y1 && box.x2 == coords->x2 &&
      box.y2 == coords->y2)
    return; /* resolved, and it paints nowhere outside after all */
  char pbuf[80];
  (void)snprintf(pbuf, sizeof(pbuf), ",\"%s\":[%d,%d,%d,%d]",
                 exact ? "paint_box" : "paint_bound", (int)box.x1, (int)box.y1,
                 (int)box.x2, (int)box.y2);
  tree_append(pbuf);
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
    /* WRAP long-mode reflow: the label GREW rather than clipping, so neither
       * flag above can fire (see label_text_wrapped). */
    if (label_text_wrapped(obj))
      tree_append(",\"text_wrapped\":true");
  }
  /* Layout-defect flags, emitted only when set (the hidden/checked
   * convention). Derived from resolved geometry, so both oracles agree. */
  if (!is_root && obj_clipped(obj, &a))
    tree_append(",\"clipped\":true");
  /* One local for the AXES and none for "did it overflow at all": the boolean
     * was a second name for `overflow_dirs != 0`, and dump_obj sits AT
     * clang-tidy's readability-function-size variable threshold, so adding a
     * name here costs a real gate red rather than nothing. */
  unsigned overflow_dirs = obj_overflow_dirs(obj);
  bool scrollable = lv_obj_has_flag(obj, LV_OBJ_FLAG_SCROLLABLE);
  if (overflow_dirs != 0u && !scrollable)
    tree_append(",\"overflow\":true");
  if (overflow_dirs != 0u && scrollable) {
    tree_append(",\"scrollable_overflow\":true");
    /* The AXES, emitted only alongside the flag they qualify, so a deliberate
       * scroller can be declared per direction rather than per card. */
    tree_append(",\"scroll_dirs\":\"");
    if (overflow_dirs == (OVERFLOW_DIR_HOR | OVERFLOW_DIR_VER))
      tree_append("both");
    else if ((overflow_dirs & OVERFLOW_DIR_HOR) != 0u)
      tree_append("hor");
    else
      tree_append("ver");
    tree_append("\"");
  }
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
  /* Emitted only when set: the pending_when oracle. LV_STATE_USER_1 is the
   * bit the style vocabulary spells `pending:`, and it is the one reactive
   * state with NO other observable — CHECKED and DISABLED both change how
   * LVGL's own theme paints a widget, whereas USER_1 paints nothing until a
   * screen authors a `pending:` style group. Without this line the bit is
   * invisible to every DOM lane and to the harness, so the binding could
   * only be judged through a framebuffer probe that depends on an authored
   * style existing — i.e. not judged at all on a corpus that has none. */
  if (lv_obj_has_state(obj, LV_STATE_USER_1))
    tree_append(",\"pending\":true");
  /* POINTER REACHABILITY, emitted only when it differs from the common case.
   * lv_obj_hit_test gates on LV_OBJ_FLAG_CLICKABLE alone (lv_obj_pos.c), and
   * lv_obj_constructor sets that on EVERY object — so clickable is the norm
   * and its absence is the informative state, which is also why the negative
   * spelling is the cheap one (a handful of labels/images/lines/spinners,
   * versus every container). NOT redundant with the class name: this is a
   * per-INSTANCE fact a type-keyed classifier cannot recover. A STATIC
   * host_proxy has the flag cleared at runtime so the pointer falls through
   * it, and nothing in the dump said so, which left a consumer's overlap rule
   * counting a fall-through surface as pointer-taking. */
  if (!lv_obj_has_flag(obj, LV_OBJ_FLAG_CLICKABLE))
    tree_append(",\"clickable\":false");
  /* The pointer is tested against the CLICK AREA — coords grown by
   * ext_click_pad (lv_obj_get_click_area) — never against coords. A rule
   * measuring coords therefore UNDER-reports wherever a widget extends its
   * touch target. Emitted only when the two differ, which is rare, so the
   * dump stays compact and a geometry rule can judge the real hazard
   * boundary rather than the drawn box. */
  {
    lv_area_t click;
    lv_obj_get_click_area(obj, &click);
    if (click.x1 != a.x1 || click.y1 != a.y1 || click.x2 != a.x2 ||
        click.y2 != a.y2) {
      char cbuf[64];
      (void)snprintf(cbuf, sizeof(cbuf), ",\"click_area\":[%d,%d,%d,%d]",
                     (int)click.x1, (int)click.y1, (int)click.x2,
                     (int)click.y2);
      tree_append(cbuf);
    }
  }
  /* The box lv_indev_search_obj uses to decide whether it will DESCEND into
   * this object's children. Normally that is exactly coords. Under
   * OVERFLOW_VISIBLE, LVGL grows it by this object's resolved ext_draw_size
   * before testing the inverse-transformed point.
   *
   * Emit the already-resolved gate (option b), not `overflow_visible` plus a
   * value a producer would have to reconstruct: this is the exact box the
   * consumer needs, it uses only LVGL getters linked into both wasm oracles,
   * and absence has the same precise convention as click_area — the gate
   * equals coords. A set flag whose ext_draw_size is zero therefore costs no
   * dump bytes and needs no special case downstream. Transforms remain a
   * separately declared limitation: both coords and this gate live in the
   * pre-transform coordinate space lv_indev_search_obj compares after
   * inverse-transforming the pointer, while the dump carries no transform. */
  if (lv_obj_has_flag(obj, LV_OBJ_FLAG_OVERFLOW_VISIBLE)) {
    lv_area_t gate = a;
    int32_t ext_draw_size = lv_obj_get_ext_draw_size(obj);
    lv_area_increase(&gate, ext_draw_size, ext_draw_size);
    if (gate.x1 != a.x1 || gate.y1 != a.y1 || gate.x2 != a.x2 ||
        gate.y2 != a.y2) {
      char gbuf[64];
      (void)snprintf(gbuf, sizeof(gbuf), ",\"descend_gate\":[%d,%d,%d,%d]",
                     (int)gate.x1, (int)gate.y1, (int)gate.x2, (int)gate.y2);
      tree_append(gbuf);
    }
  }
  dump_paint_extent(obj, &a);
  dump_composition_keys(obj);
  /* RESOLVED STYLE — see the block comment above obj_effective_opa for what
   * each key's ABSENCE means; they do not all fail the same way. */
  {
    const lv_opa_t opa = obj_effective_opa(obj);
    if (opa != LV_OPA_COVER)
      tree_append_opa("opa", opa);
    /* text_color rides the inheritance chain, so emit only where it CHANGES
     * (the root has no parent and always emits). */
    const lv_color_t txt = style_color_drawn(
        obj, LV_PART_MAIN,
        lv_obj_get_style_text_color_filtered(obj, LV_PART_MAIN));
    const lv_obj_t *parent = lv_obj_get_parent(obj);
    bool txt_differs = true;
    if (parent != NULL) {
      const lv_color_t up = style_color_drawn(
          parent, LV_PART_MAIN,
          lv_obj_get_style_text_color_filtered(parent, LV_PART_MAIN));
      txt_differs =
          up.red != txt.red || up.green != txt.green || up.blue != txt.blue;
    }
    if (txt_differs)
      tree_append_color("text_color", txt);
    /* THE FACE THE GLYPHS ARE CUT FROM, named so a consumer can join it to the
     * compiled-table metrics (lvgl-codegen.font-metrics is keyed by exactly
     * this string). LV_STYLE_TEXT_FONT carries LV_STYLE_PROP_FLAG_INHERITABLE
     * (lvgl/src/misc/lv_style.c), so this rides the same chain text_color does
     * and takes the same convention: emitted only where it CHANGES, and the
     * root has no parent so it always emits, which is what terminates the walk
     * up. ABSENT => THE NEAREST ANCESTOR THAT EMITTED ONE — never a default,
     * and never "this node draws no glyphs" (glyph-bearing-ness is a different
     * question, answered by `text` / `text_on` / `backdrop_unresolved`).
     *
     * The face can also fail to be NAMEABLE, and that is emitted rather than
     * skipped: a runtime .bin or TinyTTF face has no compiled table and so no
     * metrics row to join to, and `text_font_unnamed` says exactly that. A walk
     * up terminates at whichever of the two keys it meets first; meeting
     * neither all the way to the root can only mean a detached subtree. */
    const lv_font_t *face = lv_obj_get_style_text_font(obj, LV_PART_MAIN);
    bool face_differs = true;
    if (parent != NULL)
      face_differs = lv_obj_get_style_text_font(parent, LV_PART_MAIN) != face;
    if (face_differs) {
      const char *face_name = font_name(face);
      if (face_name != NULL) {
        char fbuf[64];
        (void)snprintf(fbuf, sizeof(fbuf), ",\"text_font\":\"%s\"", face_name);
        tree_append(fbuf);
      } else {
        tree_append(",\"text_font_unnamed\":true");
      }
    }
    const lv_opa_t txt_opa =
        opa_scaled(lv_obj_get_style_text_opa(obj, LV_PART_MAIN), opa);
    if (txt_opa != LV_OPA_COVER)
      tree_append_opa("text_opa", txt_opa);
    /* The node's own MAIN fill. Silent when it paints nothing — a colour there
     * would describe a rect that was never drawn. */
    const lv_opa_t bg_opa =
        opa_scaled(lv_obj_get_style_bg_opa(obj, LV_PART_MAIN), opa);
    if (bg_opa != LV_OPA_TRANSP) {
      tree_append_color("bg_color",
                        style_color_drawn(obj, LV_PART_MAIN,
                                          lv_obj_get_style_bg_color_filtered(
                                              obj, LV_PART_MAIN)));
      if (bg_opa != LV_OPA_COVER)
        tree_append_opa("bg_opa", bg_opa);
    }
    /* The fill this node's glyphs actually ride on: its MAIN fill, unless the
     * class draws its text on another part (obj_text_part), in which case that
     * part's fill is what is under them. */
    lv_opa_t glyph_bg = bg_opa;
    text_part_t tp;
    if (obj_text_part(obj, &tp)) {
      const lv_opa_t p_opa = part_opa(obj, tp.part, opa);
      const lv_opa_t p_bg =
          opa_scaled(lv_obj_get_style_bg_opa(obj, tp.part), p_opa);
      glyph_bg = p_bg;
      tree_append(",\"text_on\":{\"part\":\"");
      tree_append(tp.name);
      tree_append("\"");
      tree_append_color(
          "color", style_color_drawn(
                       obj, tp.part,
                       lv_obj_get_style_text_color_filtered(obj, tp.part)));
      const lv_opa_t p_txt_opa =
          opa_scaled(lv_obj_get_style_text_opa(obj, tp.part), p_opa);
      if (p_txt_opa != LV_OPA_COVER)
        tree_append_opa("text_opa", p_txt_opa);
      if (p_bg != LV_OPA_TRANSP) {
        tree_append_color(
            "bg", style_color_drawn(
                      obj, tp.part,
                      lv_obj_get_style_bg_color_filtered(obj, tp.part)));
        if (p_bg != LV_OPA_COVER)
          tree_append_opa("bg_opa", p_bg);
      }
      /* The part's OWN face, when it is not the node's. `state_selector` on a
       * ui_ast StyleGroup is a full lv_style_selector_t, so a screen can set
       * LV_STYLE_TEXT_FONT on LV_PART_ITEMS / SELECTED / INDICATOR — and then
       * the top-level text_font, which reads MAIN, names a face these glyphs
       * are NOT cut from. Emitted only on that divergence, exactly like `bg`
       * beside it: ABSENT => the part draws with the face the top-level
       * text_font chain resolves for this node — which is `face`, compared
       * against here rather than re-looked-up, so the two keys cannot disagree
       * about what MAIN resolved to. Measured over this repo's corpus it fires
       * on 0 of 48 text_on nodes, so being right about a consumer's screens
       * costs this one nothing. */
      const lv_font_t *p_face = lv_obj_get_style_text_font(obj, tp.part);
      if (p_face != face) {
        const char *p_face_name = font_name(p_face);
        if (p_face_name != NULL) {
          char pfbuf[64];
          (void)snprintf(pfbuf, sizeof(pfbuf), ",\"font\":\"%s\"", p_face_name);
          tree_append(pfbuf);
        } else {
          tree_append(",\"font_unnamed\":true");
        }
      }
      tree_append("}");
    }
    /* THE THIRD ANSWER. Glyphs over a fill that does not fully cover sit on
     * something this node does not name, and the ancestor walk that would name
     * it is refuted above. Say so, so a producer emits a finding instead of a
     * confident wrong number. */
    if (glyph_bg != LV_OPA_COVER && obj_draws_text(obj))
      tree_append(",\"backdrop_unresolved\":true");
  }
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
    /* The clamped offset is arithmetic on buffer sizes and knows nothing about
     * the bytes it lands on, so it can fall inside a multi-byte character and
     * leave its first half behind — and then a host that decodes the dump as a
     * string refuses the whole thing, destroying THIS SENTINEL along with it.
     * Back the overwrite off to a codepoint boundary so the signal survives the
     * truncation it reports. The unclamped arm is unaffected: `at` is then
     * tree_out.pos, where the byte is the NUL and no character is open. */
    at = utf8_trim_to_boundary(tree_buf, at);
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
  /* Same window contract as update_composite: the family decides which
   * recolors asgard_theme_recolor_is_declared will vouch for, so records from
   * the outgoing family must not survive into the incoming one. */
  palette_observer_clear();
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
