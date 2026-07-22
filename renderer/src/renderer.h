#ifndef RENDERER_H
#define RENDERER_H
#include "cmd_patch.h"
#include "lvgl.h"
#include <stdint.h>
/* Build LVGL widget tree from raw protobuf UI AST bytes.
 * Returns 0 on success, -1 on error. */
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
#endif
/* RENDERER_H */
