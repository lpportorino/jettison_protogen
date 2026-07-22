/**
 * cmd_patch.c — R5b cmd-out byte-patcher implementation.
 *
 * See cmd_patch.h. The two slot writers (verbatim LE double, padded varint)
 * are the C mirror of uigen.cmd-spec's double->le-bytes / padded-varint; the
 * de-risk proof that this round-trips to a VALUE-identical cmd.Root lives in
 * test/uigen/cmd_spec_test.clj.
 */
#include "cmd_patch.h"
#include "host_imports.h"
#include "log.h"
#include <string.h>
/* Write the 8 little-endian wire bytes of `d` into out[0..8). WASM is
 * little-endian and the IEEE-754 layout matches the protobuf double wire
 * form, so the raw bit pattern copies straight through (verbatim, no recast —
 * ui_input NDC == cmd.* NDC). */
static void double_le_bytes(uint8_t *out, double d) {
  uint64_t bits;
  memcpy(&bits, &d, sizeof(bits));
  for (int i = 0; i < 8; i++)
    out[i] = (uint8_t)((bits >> (8 * i)) & 0xff);
}
void cmd_patch_padded_varint(uint8_t *out, uint32_t width, int64_t value) {
  /* Mirror uigen.cmd-spec/padded-varint: r starts as value, each low group
   * carries 7 value bits with bit-7 set (continuation); the final byte clears
   * bit-7. The shift is the unsigned >>> of a 64-bit pattern, so we carry the
   * value through a uint64_t. */
  uint64_t r = (uint64_t)value;
  for (uint32_t i = 0; i < width; i++) {
    uint8_t group = (uint8_t)(r & 0x7f);
    /* All but the final byte set the continuation bit (bit 7). */
    out[i] = (i == width - 1) ? group : (uint8_t)(group | 0x80);
    r >>= 7;
  }
}
int32_t cmd_patch_emit(const cmd_spec_t *spec, double x, double y,
                       int32_t value_or_delta) {
  /* A widget/gesture with no pre-encoded template never reaches the host —
   * there is nothing to send (no host_command, no legacy fallback). */
  if (!spec || !spec->present || spec->template_len == 0)
    return 0;
  if (spec->template_len > CMD_PATCH_TEMPLATE_CAP) {
    LOG_ERROR("cmd_patch: template_len %u exceeds cap %u",
              (unsigned)spec->template_len, CMD_PATCH_TEMPLATE_CAP);
    return -1;
  }
  uint8_t buf[CMD_PATCH_TEMPLATE_CAP];
  memcpy(buf, spec->template, spec->template_len);
  for (uint32_t i = 0; i < spec->patch_count; i++) {
    const cmd_patch_field_t *p = &spec->patches[i];
    /* Every slot must lie fully inside the template — a gen-time contract,
       * but the renderer validates the host-supplied bytes regardless
       * (overflow-safe: byte_offset + byte_width can WRAP as uint32). */
    if (!cmd_patch_slot_in_bounds(p->byte_offset, p->byte_width,
                                  spec->template_len)) {
      LOG_ERROR("cmd_patch: slot [%u+%u) overflows template_len %u",
                (unsigned)p->byte_offset, (unsigned)p->byte_width,
                (unsigned)spec->template_len);
      return -1;
    }
    switch (p->kind) {
    case CMD_PATCH_KIND_NDC_X:
      if (p->byte_width != 8) {
        LOG_ERROR("cmd_patch: NDC_X slot width %u != 8",
                  (unsigned)p->byte_width);
        return -1;
      }
      double_le_bytes(&buf[p->byte_offset], x);
      break;
    case CMD_PATCH_KIND_NDC_Y:
      if (p->byte_width != 8) {
        LOG_ERROR("cmd_patch: NDC_Y slot width %u != 8",
                  (unsigned)p->byte_width);
        return -1;
      }
      double_le_bytes(&buf[p->byte_offset], y);
      break;
    case CMD_PATCH_KIND_DELTA:
    case CMD_PATCH_KIND_WIDGET_VALUE: {
      /* value × wire-scale is the wire int (uigen.scales). The product
             * is computed in 64-bit so a large widget value × scale never
             * overflows before the padded-varint fan-out. */
      int64_t wire = (int64_t)value_or_delta * (int64_t)p->wire_scale;
      cmd_patch_padded_varint(&buf[p->byte_offset], p->byte_width, wire);
      break;
    }
    default:
      LOG_ERROR("cmd_patch: unknown patch kind %u", (unsigned)p->kind);
      return -1;
    }
  }
  return host_command((uint32_t)(uintptr_t)buf, spec->template_len);
}
