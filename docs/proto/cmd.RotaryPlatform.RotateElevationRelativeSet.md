---
id: cmd.RotaryPlatform.RotateElevationRelativeSet
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateElevationRelativeSet

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Sets the rotary platform's elevation angle to a value relative to its current position, specified as an offset with a clockwise or counter-clockwise direction.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -90, <= 90 |
| 2 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Sets elevation relative to current position


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.RotateElevationRelative]]





## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2


### direction (#2)


#### Metadata

- **Semantic Type:** :enum-label



