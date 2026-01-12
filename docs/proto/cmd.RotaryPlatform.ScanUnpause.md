---
id: cmd.RotaryPlatform.ScanUnpause
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanUnpause

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Resumes a paused scan pattern on the rotary platform after it has been temporarily suspended with ScanPause. This command is a state-transition operation that complements ScanPause, allowing the scan to continue from where it was interrupted without needing to restart.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Resumes paused rotary platform scan pattern



### Related Commands

- [[proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanStart]]
- [[proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanPause]]
- [[proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanStop]]


### Preconditions

- Scan must be active and paused




