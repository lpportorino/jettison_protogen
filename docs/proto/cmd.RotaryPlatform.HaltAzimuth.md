---
id: cmd.RotaryPlatform.HaltAzimuth
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# HaltAzimuth

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

Immediately stops azimuth axis movement


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.Halt]]



### Implementation Notes

Emergency stop for azimuth only



