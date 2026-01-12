---
id: cmd.RotaryPlatform.ScanStop
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanStop

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops rotary platform scanning operation


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.ScanStart]]
- [[proto/proto/cmd.RotaryPlatform.ScanPause]]
- [[proto/proto/cmd.RotaryPlatform.ScanUnpause]]


### Preconditions

- Scan must be active


### Implementation Notes

Part of scan control system. No parameters.



