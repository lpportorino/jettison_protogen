---
id: cmd.RotaryPlatform.ScanStop
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanStop

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Completely stops and terminates the scan pattern execution on the rotary platform, ending the scanning mode entirely. Unlike ScanPause which temporarily suspends the scan (allowing resume with ScanUnpause), ScanStop fully terminates the scan sequence.

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

- [[proto/ser.JonGuiDataRotary#is_scanning]]


### Related Commands

- [[proto/cmd.RotaryPlatform.ScanStart]]
- [[proto/cmd.RotaryPlatform.ScanPause]]
- [[proto/cmd.RotaryPlatform.ScanUnpause]]


### Preconditions

- Scan must be active


### Implementation Notes

Part of scan control system. No parameters.



