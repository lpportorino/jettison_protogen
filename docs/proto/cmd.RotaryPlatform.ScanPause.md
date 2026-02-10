---
id: cmd.RotaryPlatform.ScanPause
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanPause

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Pauses an active rotary platform scan operation while keeping the scan state intact, allowing it to be resumed later with ScanUnpause.

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

- [[proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/cmd.RotaryPlatform.ScanUnpause]]
- [[proto/cmd.RotaryPlatform.ScanStart]]
- [[proto/cmd.RotaryPlatform.ScanStop]]



### Implementation Notes

Part of scan pattern control system with hotkey support



