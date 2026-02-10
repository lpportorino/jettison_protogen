---
id: cmd.System.StartRec
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StartRec

**Source:** `jon_shared_cmd_system.proto`

## Description

Initiates video recording on the device, triggering continuous video capture from the camera streams. The recording state is tracked by the `rec_enabled` flag in the system state and works in conjunction with StopRec to control the recording lifecycle.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Starts video recording of camera streams


### Related State

- [[proto/ser.JonGuiDataSystem]]




### Implementation Notes

Fire-and-forget command, state update confirms recording started



