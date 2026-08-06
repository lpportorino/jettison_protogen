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
 * A slot names its SOURCE (`kind`) and its ENCODING separately, because one
 * integer source reaches three different numeric wire shapes:
 *   - NDC_X / NDC_Y / NDC_X2 / NDC_Y2 → an 8-byte little-endian double slot
 *     at wire-scale 1, with no fixed-point recast. Their encoding IS the kind,
 *     so they carry UNSPECIFIED. The x pair is BYTE-identical to the pointer
 *     sample; the y pair is byte-identical only when the destination shares the
 *     pointer's plane, because the device's NDC commands do not all — see
 *     CMD_PATCH_NDC_Y_SENSE_* below.
 *   - DELTA / WIDGET_VALUE / SUBJECT_VALUE → whichever encoding the slot
 *     names:
 *       PADDED_VARINT — a NON-MINIMAL varint of (value * wire_scale) filling
 *         byte_width: the low groups carry 7 value bits with bit-7 set
 *         (continuation), the final byte clears bit-7 → VALUE-identity.
 *       DOUBLE_LE / FLOAT_LE — the 8 or 4 little-endian IEEE-754 bytes of
 *         (value / wire_scale), for a fixed64/fixed32 leaf. These exist
 *         because a varint written over a fixed64 slot decodes as garbage,
 *         which is what a double-typed leaf used to get.
 *   - SUBJECT_VALUE additionally takes its int from the NAMED subject in the
 *     slot rather than from an emit argument, so N slots carry N independent
 *     values — the shape a multi-field form's submit needs.
 *
 * This is a renderer-internal lego: a stored CmdSpec (root_template bytes +
 * the patch descriptors, copied into PERSISTENT storage during finalize before
 * R5a's pb_release frees the nanopb decode copy) drives one emit per fire.
 */
#ifndef CMD_PATCH_H
#define CMD_PATCH_H
#include <stdbool.h>
#include <stdint.h>
/* Patch slot kinds — mirror ui_PatchKind (generated/ui_ast.pb.h). The SOURCE
 * axis only: where the value comes from. */
#define CMD_PATCH_KIND_UNSPECIFIED 0
#define CMD_PATCH_KIND_NDC_X 1
#define CMD_PATCH_KIND_NDC_Y 2
#define CMD_PATCH_KIND_DELTA 3
#define CMD_PATCH_KIND_WIDGET_VALUE 4
#define CMD_PATCH_KIND_NDC_X2 5
#define CMD_PATCH_KIND_NDC_Y2 6
/* The current int of a NAMED local subject, resolved to a pointer at LOAD
 * (cmd_patch_field_t.subject). The one source that is neither a pointer
 * gesture nor the emitting widget's own value, which is what lets a control
 * with no value of its own — a form's Apply button — send N independent
 * field values: each slot carries its own subject, so the emit signature
 * below never grows an argument per field. */
#define CMD_PATCH_KIND_SUBJECT_VALUE 7
/* Slot encodings — mirror ui_PatchEncoding. The ENCODING axis: how the value
 * is written. Meaningful only for the value-sourced kinds (DELTA,
 * WIDGET_VALUE, SUBJECT_VALUE); the four NDC kinds are 8-byte verbatim
 * doubles BY DEFINITION and must carry UNSPECIFIED, so the fact lives in one
 * field rather than two that can disagree. */
#define CMD_PATCH_ENCODING_UNSPECIFIED 0
#define CMD_PATCH_ENCODING_PADDED_VARINT 1
#define CMD_PATCH_ENCODING_DOUBLE_LE 2
#define CMD_PATCH_ENCODING_FLOAT_LE 3
/* Which vertical sense the DESTINATION command's NDC y leaves are read in —
 * mirrors ui_NdcYSense (generated/ui_ast.pb.h). A THIRD axis beside kind and
 * encoding, and it is genuinely a third: kind names where the value comes from,
 * encoding names how it is written, and this names which coordinate frame the
 * receiver reads it in.
 *
 * The gesture recognizer works in ONE plane — the ui_input pointer plane, +y UP
 * — and the device's NDC commands do not all share it. A
 * cmd.{Day,Heat}Camera.{Focus,Track,Zoom,Fx}ROI rectangle is read in the
 * ser.JonGuiDataROI plane, which declares -1.0 at the TOP. Both planes are a
 * double in [-1,1] called "NDC", so a y written across the boundary unflipped
 * produces a VERTICALLY MIRRORED command: it decodes cleanly, it is inside every
 * declared range, and no consumer downstream can tell it from a correct one.
 * That undetectability is why this is stated on the wire per command rather than
 * inferred here from a command-id table — a second home for the fact, in the one
 * component that must never hold it.
 *
 * UNSPECIFIED IS REFUSED, NOT DEFAULTED, on any spec carrying an NDC y slot.
 * Either default would spell "the producer stated the plane" and "the producer
 * never considered the plane" identically, and the second is the state that
 * ships a mirror. A spec with no NDC y slot has no plane to state and correctly
 * carries UNSPECIFIED. */
#define CMD_PATCH_NDC_Y_SENSE_UNSPECIFIED 0
#define CMD_PATCH_NDC_Y_SENSE_UP 1
#define CMD_PATCH_NDC_Y_SENSE_DOWN 2
/* Caps mirror the proto: root_template is PB_BYTES_ARRAY_T(128); a CmdSpec
 * carries up to CMD_PATCH_MAX_PATCHES patches — the gesture shapes need 2 (an
 * NDC x/y pair) or 4 (an ROI rubber-band's two corners), and a multi-field form
 * needs one per field. The persistent copy is a flat fixed-size record (no
 * malloc) so a stored CmdSpec outlives the nanopb decode buffer it was copied
 * from. */
#define CMD_PATCH_TEMPLATE_CAP 128
/* Mirrors ui_ast.proto's CmdSpec.patches max_items:8, whose bound is set by the
 * WIDEST command this vocabulary must send in one shot — the rotary scan-node
 * commands carry 7 operator-facing fields. The gesture shapes that set the
 * previous bound of 4 are unaffected (an NDC x/y pair is 2, an ROI
 * rubber-band's two corners are 4). */
#define CMD_PATCH_MAX_PATCHES 8
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
  uint32_t byte_width;  /* slot width (8 = double, 4 = float, 5/10 = varint) */
  uint32_t kind;        /* CMD_PATCH_KIND_* — the SOURCE */
  /* uigen.scales' fixed-point factor, defined ONCE as `proto value × scale =
   * the ABI int` the widgets and subjects ride. DOUBLE_LE/FLOAT_LE therefore
   * DIVIDE by it to recover the proto value; PADDED_VARINT multiplies, because
   * an integer leaf's ABI int is already in the proto's own unit and its scale
   * is 1. A scale ≤ 0 is REFUSED rather than applied — it reaches here only
   * from a malformed producer, and either direction would corrupt the slot
   * silently (the multiply by 0 zeroed it, which is exactly the "sends a
   * plausible wrong value" failure this patcher must never have). */
  int32_t wire_scale;
  uint32_t encoding; /* CMD_PATCH_ENCODING_* — how the value is written */
  /* The subject a SUBJECT_VALUE slot reads, by NAME; empty for every other
   * kind. Bounded at 64 like every other subject-name mirror in the renderer
   * (subject_entry_t.name, event_cb_data_t.set_subject), so a name legal to
   * DECLARE is always legal to store here.
   *
   * THE NAME AND NOT A RESOLVED POINTER, and the reason is ownership rather
   * than taste. Screen.subjects is field 2 and streams AFTER the widget tree,
   * so nothing can resolve during the copy; and by the time it could, the two
   * places a spec is copied into are both unstable — the cmd_by_value array is
   * FREED if any later entry fails to copy, and a gesture surface's specs are
   * built in a CALLER'S LOCAL array that controls_gesture_specs_set copies out
   * of. A pointer captured at copy time would dangle in both. This is the same
   * shape, and the same answer, as EventBinding.set_subject: the emit owns its
   * copy of the name and resolves at fire time, while a deferred queue checks
   * RESOLVABILITY once at load so an unknown name fails the load loudly
   * instead of surfacing as a dead button. */
  char subject[64];
} cmd_patch_field_t;
/* Resolve `name` to a subject and read its current int into `*out`. Returns
 * false when the name names no subject, or names one that is not INT-typed —
 * the patcher then REFUSES the emit rather than sending a default.
 *
 * A REGISTERED POINTER rather than a direct extern call, and the reason is the
 * LINK GRAPH rather than taste: cmd_patch.c is in COMMON_APP_SRCS and is linked
 * into BOTH wasm modules, while the subject registry belongs to only one of
 * them. reference.wasm links cmd_patch.c beside reference_ui.c — which makes
 * literal lv_* calls, decodes no proto and owns no subjects — so a direct call
 * to renderer.c's accessor is an undefined symbol at THAT link, and was
 * (measured: `wasm-ld: undefined symbol: cmd_patch_subject_int`).
 *
 * Unregistered therefore means "this module has no subject registry", and a
 * SUBJECT_VALUE slot then refuses — the correct answer for a module that has
 * none, rather than a link error for a module that will never meet one. */
typedef bool (*cmd_patch_subject_reader_t)(const char *name, int32_t *out);
/* Install the reader. Idempotent; renderer.c registers its own beside the
 * registry's lifecycle, so the two can never be installed out of step. */
void cmd_patch_set_subject_reader(cmd_patch_subject_reader_t reader);
/* A persistent (malloc-free) copy of a CmdSpec: the pre-encoded cmd.Root
 * template + the slot descriptors. `present` distinguishes a populated spec
 * from a zeroed slot. */
typedef struct {
  bool present;
  uint32_t template_len;
  uint8_t template[CMD_PATCH_TEMPLATE_CAP];
  uint32_t patch_count;
  cmd_patch_field_t patches[CMD_PATCH_MAX_PATCHES];
  /* CMD_PATCH_NDC_Y_SENSE_* — the DESTINATION plane of this command's NDC y
   * leaves. Per SPEC and not per slot on purpose: an ROI carries y1 and y2 and
   * they are one rectangle in one frame, so two slots that could state
   * different senses is the same "two homes that can disagree" the encoding
   * check already refuses. */
  uint32_t ndc_y_sense;
} cmd_spec_t;
/* Orient a pointer-plane (+y UP) NDC y for a destination in `sense`, or refuse.
 * Returns false and leaves *out untouched when the sense is UNSPECIFIED or
 * unknown — the caller aborts the whole emit rather than picking a plane.
 *
 * `static inline` in the header because BOTH modules that link cmd_patch.c need
 * it and only one of them can see the generated enum: renderer.c validates the
 * decoded spec at the load boundary, cmd_patch.c writes the slot at emit, and
 * reference.wasm links cmd_patch.c with no proto header at all. */
static inline bool cmd_patch_orient_y(uint32_t sense, double y, double *out) {
  switch (sense) {
  case CMD_PATCH_NDC_Y_SENSE_UP:
    *out = y;
    return true;
  case CMD_PATCH_NDC_Y_SENSE_DOWN:
    /* The planes differ by reflection about 0, so the transform is negation and
     * nothing else — both are [-1,1] with 0 at the centre of the image. */
    *out = -y;
    return true;
  default:
    return false;
  }
}
/* Which sign of a decision's step a gesture entry answers — mirrors
 * ui_GestureDeltaSign (generated/ui_ast.pb.h). ANY is the zero value and
 * answers every step, which is what a spec that says nothing about direction
 * means and the only sensible selector for a kind whose decisions carry no
 * step. The other two exist because ONE gesture kind can need TWO templates:
 * a pinch's two directions are frequently two different EMPTY commands
 * (…{Next,Prev}ZoomTablePos) rather than one command with a signed leaf, so
 * there is nowhere for the sign to ride as a patched value. */
#define CMD_PATCH_DELTA_SIGN_ANY 0
#define CMD_PATCH_DELTA_SIGN_POSITIVE 1
#define CMD_PATCH_DELTA_SIGN_NEGATIVE 2
/* The gesture-surface registry capacity, and it is the MAXIMUM LEGAL REGISTRY
 * rather than a guess: ONE entry per defined ui_GestureKind, PLUS one extra for
 * each kind whose decisions carry a STEP, because those are the kinds that can
 * legitimately hold a template per direction. Seven kinds, of which PINCH and
 * WHEEL carry a step (gesture.h states it at gesture_decision_t.delta), so the
 * sum is 7 + 2. The renderer refuses a signed selector on any other kind, so no
 * conforming producer can want a tenth entry. main.c holds this sum to
 * gesture_kind_t with a static_assert.
 *
 * A registry entry is 820 B, so the raise from the previous 5 costs ~3.3 KB in
 * main.c's static array and the same again in renderer.c's finalize_widget
 * frame — which is NOT in the decoder's recursion cycle (children_decode_cb
 * calls it after pb_decode returns), so it is paid once at the deepest level
 * rather than per level. wasm.mk's stack reservation records the measured peak
 * that budget comes out of.
 *
 * WHEEL is counted even though the live pointer pipeline never produces a WHEEL
 * decision today, because a bound that excepts an enumerator has to be
 * re-derived by every reader — and the previous bound was derived that way, from
 * the five device gestures of the day, then silently went short of the
 * vocabulary when GESTURE_KIND_ROI landed beside them. */
#define CMD_PATCH_MAX_GESTURES 9
/* One gesture → its pre-encoded cmd template, the persistent copy of a
 * ui_GestureSpec. `kind` mirrors gesture_kind_t / ui_GestureKind; `delta_sign`
 * mirrors ui_GestureDeltaSign and is what makes the (kind, sign) pair rather
 * than the kind alone the registry's key. */
typedef struct {
  uint32_t kind;       /* gesture_kind_t value */
  uint32_t delta_sign; /* CMD_PATCH_DELTA_SIGN_* */
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
 * mirroring uigen.wire-encode/padded-varint EXACTLY: each of the low groups
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
 * `uigen.wire-encode/padded-varint` is pinned by
 * tools/renderer-gen/test/uigen/wire_encode_test.clj, this side by nothing — and
 * closing that needs a deliberate export or probe entry point, which is an ABI
 * change and a coordinated event, not a linkage tweak. */
/* Emit the command described by `spec`, patched with the live values:
 *   - NDC_X slots ← `x` (8 LE double bytes, verbatim)
 *   - NDC_Y slots ← `y` ORIENTED into `spec->ndc_y_sense` (8 LE double bytes;
 *     verbatim for UP, negated for DOWN, REFUSED when unstated)
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
 * each written as 8 LE double bytes (wire-scale 1, no recast); the x pair
 * verbatim, the y pair oriented into `spec->ndc_y_sense`. An ROI destination is
 * the y-DOWN case in practice, so byte-identity with the pointer sample does NOT
 * hold for this emit — value-identity in the destination's plane does. The
 * corners are relayed in drag order (down→up); min/max ordering is deferred to
 * the consumer/device. Shares the slot-writer with cmd_patch_emit — a DELTA /
 * WIDGET_VALUE slot in an ROI spec patches to 0 (an ROI CmdSpec carries only
 * the 4 NDC slots). Returns the host_command result (0 ok, -1 error) or 0 for
 * the no-op (NULL/absent/empty) case. */
int32_t cmd_patch_emit_rect(const cmd_spec_t *spec, double x1, double y1,
                            double x2, double y2);
#endif
/* CMD_PATCH_H */
