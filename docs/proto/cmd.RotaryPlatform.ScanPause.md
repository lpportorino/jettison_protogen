---
id: cmd.RotaryPlatform.ScanPause
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanPause

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Pauses the active scan pattern execution


### Related State

- [[proto/proto/ser.JonGuiDataRotaryPlatform]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.ScanUnpause]]
- [[proto/proto/cmd.RotaryPlatform.ScanStart]]
- [[proto/proto/cmd.RotaryPlatform.ScanStop]]



### Implementation Notes

Part of scan pattern control system with hotkey support



