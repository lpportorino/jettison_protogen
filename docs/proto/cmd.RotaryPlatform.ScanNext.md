---
id: cmd.RotaryPlatform.ScanNext
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanNext

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Instructs the rotary platform to advance to the next scan node in a predefined scan path sequence. Complements the ScanPrev command for forward navigation through scan waypoints.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Move to next scan node in automated scan sequence


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.ScanPrev]]
- [[proto/proto/cmd.RotaryPlatform.ScanStart]]
- [[proto/proto/cmd.RotaryPlatform.ScanStop]]
- [[proto/proto/cmd.RotaryPlatform.ScanPause]]


### Preconditions

- Scan mode must be active




