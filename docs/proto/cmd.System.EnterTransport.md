---
id: cmd.System.EnterTransport
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# EnterTransport

**Source:** `jon_shared_cmd_system.proto`

## Description

Initiates transport/shipping mode by commanding all system components (rotary platform, day camera zoom, thermal camera zoom) to move to safe parking positions (azimuth 0°, elevation 0°, zoom levels to zero) so the device can be safely transported or shipped.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Enter transport/storage mode - safely prepares system for transport


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataSystem]]




### Implementation Notes

Lifecycle transition - typically stops all subsystems and powers down safely



