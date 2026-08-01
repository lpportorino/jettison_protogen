#ifndef RENDERER_H
#define RENDERER_H
#include "cmd_patch.h"
#include "lvgl.h"
#include <stdint.h>
/* The buffer size that carries EventBinding.name end-to-end through the
 * renderer: the persistent per-widget event cache (event_cb_data_t.name in
 * renderer.c) AND the host_event envelope serializer (json_append_str's tag
 * cap in main.c). It MUST be >= the nanopb decode buffer (ui_ast.options
 * ui.EventBinding.name max_size, 128); a _Static_assert in renderer.c locks it
 * to the generated ui_EventBinding struct so the whole chain moves together.
 * This constant exists because three independent literal-64 buffers on this
 * path (decode, cache, serializer) drifted apart and silently truncated a long
 * command-id — one home now governs all of them. */
#define UI_EVENT_NAME_BUF 128
/* The character cap the host_event envelope serializer applies to the trigger
 * field (json_append_str in main.c). Unlike UI_EVENT_NAME_BUF this bounds no
 * storage — the trigger is always one of a small closed set of enum names
 * returned by event_trigger_name in renderer.c — so its only job is to be
 * provably >= the longest of those names. A _Static_assert beside that function
 * enforces exactly that, which is what makes this a constant rather than the
 * bare literal it replaces: the same three-independent-literals drift that
 * silently truncated a command-id on the name chain could otherwise recur here
 * the first time a trigger name outgrows the cap. */
#define UI_EVENT_TRIGGER_CHARS 16
/* ── EXT CLICK AREA — a widget's hit box is its drawn box ──────────────
 * lv_obj_hit_test tests the pointer against lv_obj_get_click_area, which is
 * the drawn box GROWN by ext_click_pad — never the drawn box. A widget with
 * a non-zero pad is therefore reachable outside the rectangle it paints,
 * while a layout engine reserves space by the PAINTED box and cannot see the
 * growth. Any inherited pad thus bleeds into whatever the author placed
 * beside it, and in LVGL that bleed is not cosmetic: lv_indev_search_obj
 * walks children in REVERSE order and returns the FIRST hit, so in the
 * overlapped band exactly one of the two widgets takes the press and the
 * other is dead there. No pixel differs and no event fires, so neither a
 * framebuffer oracle nor an event log can see it.
 *
 * TWO vendored constructors set a pad of their own, and neither value is a
 * decision this interpreter ever made:
 *   lv_slider_constructor  LV_DPX(8)        — SCALES with display DPI
 *   lv_arc_constructor     LV_DPI_DEF / 10  — a FIXED 13px that does not
 * Both widgets also ship an LV_EVENT_HIT_TEST handler that would narrow the
 * reach back to the knob / the ring, and both handlers are DEAD CODE here:
 * lv_obj_hit_test consults them only under LV_OBJ_FLAG_ADV_HITTEST, which
 * neither widget sets. The full grown rectangle hit-tests, so the pad is
 * pure bleed. Setting that flag is NOT the repair — the narrowing lives in
 * an event handler, so lv_obj_get_click_area (and therefore dump_obj, and
 * therefore any geometry rule downstream) keeps reporting the UNnarrowed
 * box; the reported reach would not move while the real one silently would,
 * which is worse than the defect. It would also delete stationary track-tap
 * seek for every slider, since update_knob_pos runs from the RELEASED arm
 * with check_drag=false, and it would make a slider's reach depend on its
 * current VALUE, because the knob area moves.
 *
 * So both render paths set the pad EXPLICITLY on those two classes rather
 * than inheriting it. Zero means a widget's hit box IS its drawn box — the
 * only reach a layout can reason about, and the only one a wire-authored
 * screen can predict, because the wire carries no ext-click vocabulary and
 * an inherited pad is therefore something no author can see, set, or opt
 * out of.
 *
 * A DELIBERATE widening remains available, and the one that exists shows
 * the pattern the stock pads lack: SLIDER_SEEK_EXT_CLICK_PX (renderer.c)
 * widens a press-seek slider AND the composition that uses it reserves that
 * much clear space around the track, so the wider reach lands on space
 * nothing else claims. Widen a hit box only together with the space it will
 * occupy. If a control is hard to hit, grow the CONTROL — that growth is
 * the kind a layout can see.
 *
 * THESE LIVE HERE, IN THE SHARED HEADER, FOR THE REASON UI_EVENT_NAME_BUF
 * DOES. renderer.c and reference_ui.c are deliberately independent
 * implementations of this interface, and the coverage matrix tree-diffs
 * them — so a pad spelled separately on each side is two literals that
 * drift, and the drift surfaces as a matrix divergence blamed on the proto
 * representation. One home governs both. What the matrix therefore does NOT
 * check is the VALUE itself, which it never could; that is pinned by the
 * overlap rule's real-render canary (tools/devcards/dev/overlap_canary.clj).
 *
 * Design pixels: LV_DPX_CALC special-cases n == 0, so LV_DPX(0) is exactly 0
 * at every DPI, and a future non-zero value here would scale as the
 * press-seek one does. */
#define SLIDER_EXT_CLICK_PX 0
#define ARC_EXT_CLICK_PX 0
/* ── Full loads (controls_load_ui) ─────────────────────────────────────
 * A failed load has two SEMANTICALLY DIFFERENT outcomes, and a bare -1
 * could not tell them apart. The distinction is not cosmetic: it decides
 * whether the caller may leave the screen up.
 *
 * The split follows the decoder's own control flow, so it is mechanical
 * rather than a per-site judgement:
 *
 *   ABORTED — a decode callback returned false, so nanopb stopped. The
 *     tree is TRUNCATED at the fault; what is on screen is debris that
 *     happens to precede it. Nothing usable was built.
 *   DEFECTIVE — a build step latched an error and RETURNED (finalize_widget
 *     is `void`; the decode ran to completion). The tree is COMPLETE and
 *     renderable; one or more nodes are degraded — canonically a duplicate
 *     codegen uid, where the collided node is deliberately left
 *     unidentified and the rest of the screen is correct.
 *
 * Tearing the screen down is right for ABORTED and WRONG for DEFECTIVE.
 * It was once done on any nonzero status and had to be reverted, because
 * it blanked screens that were fine — caught only because
 * wasm_harness/tests/reload_cycle.rs carries a non-vacuity guard (a uid
 * uniqueness assertion over an EMPTY tree proves nothing). */
#define LOAD_ERR_ABORTED -1
/* decode/limit refusal — tree truncated, nothing usable */
#define LOAD_ERR_DEFECTIVE -2
/* tree complete and renderable; >=1 node degraded (e.g. duplicate uid) */
/* Build LVGL widget tree from raw protobuf UI AST bytes.
 * Returns 0 on success, or a LOAD_ERR_* code. */
int build_ui_from_proto_raw(const uint8_t *data, uint32_t len,
                            lv_obj_t *parent);
/* Update reactive subjects from a protobuf StateUpdate message.
 * Returns 0 on success, -1 on error. */
int update_state_from_proto(const uint8_t *data, uint32_t len);
/* ── Tree patches (controls_apply_patch) ───────────────────────────────
 * Apply a ScreenPatch against the live tree. Distinct error codes — the
 * host's signal to recover by sending the full .pb (which it always
 * has). Any op failure aborts the batch: the tree is then INDETERMINATE.
 */
#define PATCH_ERR_DECODE -1
/* malformed patch / op payload */
#define PATCH_ERR_BASE_HASH -2
/* patch diffed from a different base */
#define PATCH_ERR_UNKNOWN_UID -3
/* op addresses a uid not in the tree */
#define PATCH_ERR_POOL -4
/* style/grid/string pool headroom low */
#define PATCH_ERR_OP -5
/* op validation / build failure */
/* The tree is INDETERMINATE after a prior aborted patch: ops apply
 * sequentially, so an abort leaves earlier ops applied (and the failing
 * op possibly half-applied). Every subsequent patch refuses with this
 * code until a full controls_load_ui resets the chain — partial
 * application is never silently compounded. */
#define PATCH_ERR_INDETERMINATE -6
/* Composite setters (breakpoint/theme) return this instead of silently
 * rebuilding from a STALE cached .pb after a successful patch — the
 * host must send the current full .pb (D6 in
 * docs/lvgl-factory/10-TREE-PATCH-DESIGN.md). */
#define CONTROLS_NEEDS_FULL_RELOAD 1
/* Apply a ScreenPatch (raw bytes). `expected_base_hash` is the FNV-1a-32
 * of the currently-loaded .pb; on success *out_target_hash carries the
 * patch's target hash (the new current-state hash). Returns 0 or a
 * PATCH_ERR_* code. */
int apply_patch_from_proto_raw(const uint8_t *data, uint32_t len,
                               uint32_t expected_base_hash,
                               uint32_t *out_target_hash);
/* Implemented in main.c (owns the input group), consumed by the
 * reconciler: when the group-focused widget lives inside a subtree a
 * patch op is deleting, defocus WITHOUT the auto-refocus
 * lv_group_remove_obj would perform (the patch contract matches full
 * builds: deletion leaves nothing focused). */
void input_group_defocus_within(lv_obj_t *subtree_root);
/* Clean up renderer state (subjects + style pool).
 * Call after lv_obj_clean() has destroyed all widgets. */
void renderer_cleanup(void);
/* Host-proxy report sweep — called once per controls_tick AFTER
 * lv_timer_handler so coords are post-layout. Compares each registered
 * proxy's rect/mode/visibility against its last-reported values and
 * emits host_proxy_report on divergence (phase MOVE during an active
 * gesture, SYNC otherwise). The reference module has no proxies and
 * stubs this as a no-op. */
void proxy_report_sweep(void);
/* R5b cmd-out gesture drain seam. The renderer (finalize_widget) COPIES a
 * gesture-surface's GestureSpec set into PERSISTENT storage (before R5a's
 * pb_release frees the nanopb decode copy) and hands it to main.c, which owns
 * the controls_tick drain that matches a buffered gesture_decision_t to its
 * GestureSpec.kind and emits the patched cmd via cmd_patch_emit. Implemented
 * in main.c (it owns the decision buffer). `specs`/`count` reference a
 * renderer-owned static array valid until the next full build.
 *
 * reset clears the registered set (a full build starts from no gestures).
 * set replaces it with `count` specs (≤ CMD_PATCH_MAX_GESTURES), OWNED by
 * `owner_uid` (the building node's uid). clear_owner drops the set iff `uid`
 * owns it — the incremental-REMOVE seam that keeps a torn-down gesture surface
 * from leaving phantom-emitting templates in the singleton registry (ITEM 7).
 */
void controls_gesture_specs_reset(void);
void controls_gesture_specs_set(const cmd_gesture_spec_t *specs, uint32_t count,
                                uint32_t owner_uid);
void controls_gesture_specs_clear_owner(uint32_t uid);
/* Named-event envelope emit seam (host_event lane). Implemented in main.c —
 * it owns the bounded JSON machinery (the tree_append escaping the dump-tree
 * oracle uses; ONE escaping implementation, never a second writer) and the
 * per-instance monotonic `seq`. The renderer (button_event_cb) calls this
 * when an EventBinding with a nonempty `name` fires and its host-relay gate
 * is open — ADDITIVE to the cmd.* template path, which is unchanged.
 * `tag` = EventBinding.name; `trigger` = "clicked" / "value-changed" /
 * "long-pressed" (the envelope-v1 kebab-case trigger enum); `origin_uid` =
 * the firing widget's uid (0 when the node
 * carried none); `value` = the SAME int the cmd template path patches
 * (post widget-value injection / toggle). Returns host_event's rc (0 host
 * accepted, -1 host error), -1 on an envelope-overflow refusal, or 0 as a
 * no-op for an empty tag. */
int32_t controls_emit_host_event(const char *tag, const char *trigger,
                                 uint32_t origin_uid, int32_t value);
/* Harness-only crafted-`.pb` probe: run a raw CmdSpec `.pb` (untrusted host
 * bytes, §8) through the FULL decode boundary — nanopb pb_decode AND
 * cmd_spec_copy_from_proto — and report which layer accepted or rejected it.
 * This is the crafted-wire complement to controls_cmd_patch_probe, which
 * hand-builds a cmd_spec_t and so bypasses nanopb decode + the copy guard.
 * Returns:
 *    0  accepted (decoded AND the copy stored it);
 *   -1  decoded, but cmd_spec_copy_from_proto REJECTED the slot bounds — an
 *       overflowing or uint32-WRAPPING byte_offset+byte_width;
 *   -2  nanopb REJECTED the bytes at decode — a root_template past the
 *       PB_BYTES_ARRAY_T(128) cap, or more than the 2-patch static array.
 * The reference oracle decodes no proto and stubs this (returns -2). */
int32_t cmd_spec_decode_probe(const uint8_t *data, uint32_t len);
/* Proxy membership, for dump_tree. `renderer_proxy_root` returns the proxy's
 * stable id when `obj` IS a proxy box, else NULL. `renderer_proxy_part`
 * returns "glass" / "handle" / "cell" when `obj` is one of a proxy's
 * renderer-built affordances (and sets *owner_id to that proxy's id), else
 * NULL. Together they let a dump consumer scope a pair to ONE proxy — the
 * affordances carry no uid, so nothing else can name them. */
const char *renderer_proxy_root(const lv_obj_t *obj);
const char *renderer_proxy_part(const lv_obj_t *obj, const char **owner_id);
#endif
/* RENDERER_H */
