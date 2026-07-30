/**
 * cmd_patch.h — R5b cmd-out byte-patcher (the RENDERER half of the cmd-out
 * rework; see docs/ui-nodes/README.md).
 *
 * R5a's uigen pre-encodes a full `cmd.Root` template at GENERATION time: the
 * deterministic envelope wrapping the subsystem command, with the command's
 * mutable leaf(s) sitting in a FIXED-WIDTH wire slot located by a sentinel.
 * The template + a patch descriptor ride the `ui_ast` `.pb` as a `CmdSpec`.
 *
 * At runtime R5b does the cheap half: memcpy the template into a scratch
 * buffer and overwrite JUST the patch slots with the live value, then relay
 * the result as OPAQUE `cmd.*` bytes via the `host_command` import —
 * controls.wasm builds the full device command itself; the host never decodes.
 *
 * Two slot classes (mirroring uigen.cmd-spec, the de-risk proof in
 * test/uigen/cmd_spec_test.clj):
 *   - NDC_X / NDC_Y → an 8-byte little-endian double slot, written VERBATIM
 *     (ui_input NDC == cmd.* NDC; no recast, wire-scale 1 → BYTE-identity).
 *   - DELTA / WIDGET_VALUE → a 5-byte NON-MINIMAL PADDED varint of
 *     (value * wire_scale): the low groups carry 7 value bits with bit-7 set
 *     (continuation), the final byte clears bit-7 → VALUE-identity.
 *
 * This is a renderer-internal lego: a stored CmdSpec (root_template bytes +
 * the patch descriptors, copied into PERSISTENT storage during finalize before
 * R5a's pb_release frees the nanopb decode copy) drives one emit per fire.
 */
#ifndef CMD_PATCH_H
#define CMD_PATCH_H
#include <stdbool.h>
#include <stdint.h>
/* Patch slot kinds — mirror ui_PatchKind (generated/ui_ast.pb.h). */
#define CMD_PATCH_KIND_UNSPECIFIED 0
#define CMD_PATCH_KIND_NDC_X 1
#define CMD_PATCH_KIND_NDC_Y 2
#define CMD_PATCH_KIND_DELTA 3
#define CMD_PATCH_KIND_WIDGET_VALUE 4
#define CMD_PATCH_KIND_NDC_X2 5
#define CMD_PATCH_KIND_NDC_Y2 6
/* Caps mirror the proto: root_template is PB_BYTES_ARRAY_T(128); a CmdSpec
 * carries up to 4 patches (an NDC x/y pair, plus an ROI rubber-band's
 * 2nd-corner x2/y2 pair). The persistent copy is a flat fixed-size record (no
 * malloc) so a stored CmdSpec outlives the nanopb decode buffer it was copied
 * from. */
#define CMD_PATCH_TEMPLATE_CAP 128
#define CMD_PATCH_MAX_PATCHES 4
/* Max FIXED templates an EventBinding.cmd_by_value carries — a widget's int
 * value index-selects one to emit. Mirrors the proto buf.validate max_items:16
 * (the largest real enum is 6 options). The stored array is malloc'd per-widget
 * (unlike the flat single cmd) since 16 × sizeof(cmd_spec_t) is too large to
 * inline in event_cb_data_t. */
#define CMD_PATCH_MAX_BY_VALUE 16
/* One fixed-width slot the patcher overwrites — a flat copy of ui_FieldPatch.
 */
typedef struct {
  uint32_t byte_offset; /* start of the slot in the template */
  uint32_t byte_width;  /* slot width (8 = double, 5 = padded varint) */
  uint32_t kind;        /* CMD_PATCH_KIND_* */
  int32_t wire_scale; /* runtime value × scale = the wire int (1 = verbatim) */
} cmd_patch_field_t;
/* A persistent (malloc-free) copy of a CmdSpec: the pre-encoded cmd.Root
 * template + the slot descriptors. `present` distinguishes a populated spec
 * from a zeroed slot. */
typedef struct {
  bool present;
  uint32_t template_len;
  uint8_t template[CMD_PATCH_TEMPLATE_CAP];
  uint32_t patch_count;
  cmd_patch_field_t patches[CMD_PATCH_MAX_PATCHES];
} cmd_spec_t;
/* The gesture-surface device gestures (PAN_END, TAP, TRACK, PINCH, …); a
 * spec set is at most one per GestureKind. */
#define CMD_PATCH_MAX_GESTURES 5
/* One gesture → its pre-encoded cmd template, the persistent copy of a
 * ui_GestureSpec. `kind` mirrors gesture_kind_t / ui_GestureKind. */
typedef struct {
  uint32_t kind; /* gesture_kind_t value */
  cmd_spec_t cmd;
} cmd_gesture_spec_t;
/* Overflow-safe slot-bounds check: the slot [byte_offset,
 * byte_offset+byte_width) must lie fully inside a `template_len`-byte template,
 * computed WITHOUT the wrapping uint32 add — a crafted `.pb` could set
 * byte_offset near UINT32_MAX so a naive `off + width > len` WRAPS below len
 * and bypasses the guard (an OOB write). The host is untrusted (§8), so the
 * renderer validates every slot at the decode boundary
 * (cmd_spec_copy_from_proto) AND at emit (defense-in-depth). */
static inline bool cmd_patch_slot_in_bounds(uint32_t byte_offset,
                                            uint32_t byte_width,
                                            uint32_t template_len) {
  return byte_width <= template_len && byte_offset <= template_len - byte_width;
}
/* Write the NON-MINIMAL padded varint of `value` into `out[0..width)`,
 * mirroring uigen.cmd-spec/padded-varint EXACTLY: each of the low groups
 * carries (value & 0x7f) with bit-7 set; the final byte clears bit-7. The
 * value is treated as a 64-bit pattern shifted right 7 bits per group (the
 * Clojure unsigned-bit-shift-right), so a negative int32 fans out to its
 * full-width two's-complement varint — the renderer never produces a delta
 * outside int32 range, but the encoding is total.
 *
 * NOT DECLARED HERE ANY MORE — it is `static` in cmd_patch.c. It said "Exposed
 * for unit reach" and nothing reached it: the symbol is not a wasm export, so no
 * harness test could call it even in principle, and the external linkage bought a
 * seam that did not exist. The MIRROR is still only half asserted —
 * `uigen.cmd-spec/padded-varint` is pinned by
 * tools/renderer-gen/test/uigen/cmd_spec_test.clj, this side by nothing — and
 * closing that needs a deliberate export or probe entry point, which is an ABI
 * change and a coordinated event, not a linkage tweak. */
/* Emit the command described by `spec`, patched with the live values:
 *   - NDC_X slots ← `x` (8 LE double bytes, verbatim)
 *   - NDC_Y slots ← `y` (8 LE double bytes, verbatim)
 *   - DELTA / WIDGET_VALUE slots ← padded varint of (value_or_delta * scale)
 * memcpy's the template into a scratch buffer, overwrites each slot, then
 * relays the buffer via host_command(buf, template_len). A NULL/absent/empty
 * spec is a silent no-op (a widget/gesture with no pre-encoded template never
 * reaches the host — there is nothing to send). Returns the host_command
 * result (0 ok, -1 error) or 0 for the no-op case.
 */
int32_t cmd_patch_emit(const cmd_spec_t *spec, double x, double y,
                       int32_t value_or_delta);
/* Emit an ROI rubber-band command patched with BOTH drag corners:
 *   - NDC_X / NDC_Y slots   ← corner 1 (`x1`, `y1` — the drag DOWN point)
 *   - NDC_X2 / NDC_Y2 slots ← corner 2 (`x2`, `y2` — the drag UP point)
 * each written verbatim as 8 LE double bytes (wire-scale 1, no recast). The
 * corners are relayed in drag order (down→up); min/max ordering is deferred to
 * the consumer/device. Shares the slot-writer with cmd_patch_emit — a DELTA /
 * WIDGET_VALUE slot in an ROI spec patches to 0 (an ROI CmdSpec carries only
 * the 4 NDC slots). Returns the host_command result (0 ok, -1 error) or 0 for
 * the no-op (NULL/absent/empty) case. */
int32_t cmd_patch_emit_rect(const cmd_spec_t *spec, double x1, double y1,
                            double x2, double y2);
#endif
/* CMD_PATCH_H */
