---
id: cmd.RotaryPlatform.ScanPrev
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanPrev

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Commands the rotary platform to move to the previous node in the active scan pattern sequence.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Move to previous node in rotary scan pattern


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataRotaryScan]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanNext]]
- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanStart]]
- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanStop]]


### Preconditions

- Scan mode must be active




