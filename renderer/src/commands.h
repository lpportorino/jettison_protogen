#ifndef COMMANDS_H
#define COMMANDS_H
#include <stdint.h>
/*
 * Event system: an EventBinding either mutates a local subject (reactive
 * state, no host round-trip) and/or relays a device command to the host.
 *
 * R5b cmd-out: a value-widget click carries a pre-encoded cmd.* template
 * (EventBinding.cmd, a CmdSpec — uigen.cmd-spec) the renderer patches with the
 * widget's live value and relays as OPAQUE bytes via host_command
 * (src/cmd_patch.c). See EventBinding fields: set_subject, set_value, toggle
 * (subject mutation) and cmd (the device command template).
 *
 * Named-event lane (host_event): a binding with a nonempty `name` ALSO emits
 * a self-describing JSON envelope {"v":1,"tag":name,...} via the host_event
 * import when its host-relay gate is open — additive beside the cmd wire,
 * which stays byte-identical. See host_imports.h for the envelope contract
 * and renderer.h (controls_emit_host_event) for the emit seam.
 */
#endif
/* COMMANDS_H */
