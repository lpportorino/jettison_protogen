#ifndef HOST_IMPORTS_H
#define HOST_IMPORTS_H
#include <stdint.h>
/* Host-provided import: relay one OPAQUE cmd.* device command to the host
 * (R5b — docs/ui-nodes/README.md). The renderer pre-builds the full cmd.Root
 * protobuf by memcpy'ing a CmdSpec.root_template and overwriting the patch
 * slot(s) with the live widget/gesture value (src/cmd_patch.c), then hands the
 * finished bytes to the host VERBATIM — the host relays without decoding.
 * ptr/len: the cmd.* protobuf bytes in WASM linear memory.
 * Returns 0 on success, -1 on error. */
__attribute__((import_module("env"),
               import_name("host_command"))) extern int32_t
host_command(uint32_t ptr, uint32_t len);
/* Host-provided import: relay one SELF-DESCRIBING UI-event envelope to the
 * host (the named-event lane). When an EventBinding with a nonempty `name`
 * fires and the binding's host-relay gate is open (no subject mutation, or
 * notify_host set — the same gate as host_command), the renderer builds the
 * closed JSON map
 *   {"v":1,"tag":<EventBinding.name>,"origin":<widget uid>,
 *    "event":<trigger>,"seq":<per-instance monotonic>,"value":<int>}
 * via the bounded tree_append JSON machinery (main.c) and hands the UTF-8
 * bytes here. This lane is ADDITIVE to the cmd.* template path: a binding
 * carrying both a name and a cmd template emits on BOTH channels (cmd first),
 * and the cmd wire bytes are unchanged. The host validates the envelope at
 * its membrane; the module guarantees well-formed JSON and consecutive seq.
 * ptr/len: the envelope JSON bytes in WASM linear memory (NOT NUL-terminated).
 * Returns 0 on success, -1 on error. */
__attribute__((import_module("env"), import_name("host_event"))) extern int32_t
host_event(uint32_t ptr, uint32_t len);
/* Host-provided import: relay one ui.WasmToHost feedback report to the host
 * (R5b HOST_REPORT — docs/ui-nodes/README.md). On a pointer MOVE the WASM
 * hit-test resolves the hovered widget + the cursor it wants the host to paint;
 * when that CHANGES (debounced — never every move) the WASM pb_encodes a
 * ui.WasmToHost{ hover | cursor } and hands the bytes here. The host applies
 * the cursor / interactive affordance and NEVER produces a device command from
 * it (this is the bridge plane, not cmd.*).
 * ptr/len: the ui.WasmToHost protobuf bytes in WASM linear memory.
 * Returns 0 on success, -1 on error. */
__attribute__((import_module("env"), import_name("host_report"))) extern int32_t
host_report(uint32_t ptr, uint32_t len);
/* Host-provided import: stream a host-proxy rect/mode report (the
 * positioning-proxy geometry stream — docs/lvgl-factory/08).
 * id/id_len: proxy_id string (not null-terminated).
 * phase:  0 SYNC, 1 START, 2 MOVE, 3 END.
 * mode:   current ui_ProxyMode int.
 * x,y,w,h: the proxy's rect in FRAMEBUFFER px (lv_obj coords).
 * z:      the props' opaque stacking hint, echoed verbatim.
 * flags:  bit0 = hidden (lv_obj_is_visible == false); rest reserved 0.
 * Returns 0 on success, -1 on error. */
__attribute__((import_module("env"),
               import_name("host_proxy_report"))) extern int32_t
host_proxy_report(const char *id, uint32_t id_len, int32_t phase, int32_t mode,
                  int32_t x, int32_t y, int32_t w, int32_t h, int32_t z,
                  int32_t flags);
#endif
/* HOST_IMPORTS_H */
