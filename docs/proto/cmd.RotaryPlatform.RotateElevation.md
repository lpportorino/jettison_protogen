---
id: cmd.RotaryPlatform.RotateElevation
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateElevation

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Commands continuous rotation of the elevation axis at a specified speed and direction. Used for smooth, ongoing elevation changes without a target position.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | speed | double | >= 0, <= 1 |
| 2 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :poll-confirm


### Purpose

Continuously rotates platform elevation at specified speed


### Related State

- [[proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/cmd.RotaryPlatform.HaltElevation]]
- [[proto/cmd.RotaryPlatform.RotateElevationTo]]


### Preconditions

- Rotary platform must be started
- Mode must allow manual control




## Field Notes


### speed (#1)

Movement speed (0.0=stopped, 1.0=maximum)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`


### direction (#2)

Rotation direction


#### Metadata

- **Semantic Type:** :enum-label



