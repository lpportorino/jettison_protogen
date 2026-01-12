---
id: cmd.RotaryPlatform.HaltElevation
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# HaltElevation

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Stops the rotary platform's elevation (vertical) movement by halting the elevation axis. This command immediately ceases elevation rotation while potentially allowing other axes like azimuth to continue moving.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Immediately halt elevation axis motion



### Related Commands

- [[proto/proto/cmd.RotaryPlatform.Halt]]
- [[proto/proto/cmd.RotaryPlatform.HaltAzimuth]]





