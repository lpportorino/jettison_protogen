---
id: cmd.RotaryPlatform.ScanStart
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanStart

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

Starts automated scan pattern execution



### Related Commands

- [[proto/proto/cmd.RotaryPlatform.ScanStop]]
- [[proto/proto/cmd.RotaryPlatform.ScanPause]]
- [[proto/proto/cmd.RotaryPlatform.ScanAddNode]]


### Preconditions

- Rotary platform must be started
- At least one scan node must be defined




