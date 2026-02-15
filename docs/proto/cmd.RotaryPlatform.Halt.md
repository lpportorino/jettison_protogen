---
id: cmd.RotaryPlatform.Halt
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# Halt

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Stops all rotary platform movement immediately by halting both azimuth and elevation axes simultaneously.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Immediately halt all rotary platform motion (both azimuth and elevation)


### Related State

- [[proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/cmd.RotaryPlatform.HaltAzimuth]]
- [[proto/cmd.RotaryPlatform.HaltElevation]]





