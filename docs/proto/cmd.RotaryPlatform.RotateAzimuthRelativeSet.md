---
id: cmd.RotaryPlatform.RotateAzimuthRelativeSet
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuthRelativeSet

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Sets the rotary platform's azimuth angle to a value relative to its current position, specified as an offset with a clockwise or counter-clockwise direction.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -180, < 180 |
| 2 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :pending-timeout


### Purpose

Set azimuth position relative to current position (immediate, no motion)


### Related State

- [[proto/ser.JonGuiDataRotary#azimuth]]


### Related Commands

- [[proto/cmd.RotaryPlatform.RotateAzimuthRelative]]
- [[proto/cmd.RotaryPlatform.RotateAzimuthTo]]





## Field Notes


### value (#1)

Angle value in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 0


### direction (#2)

Rotation direction


#### Metadata

- **Semantic Type:** :enum-label



