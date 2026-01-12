---
id: cmd.RotaryPlatform.RotateAzimuthRelativeSet
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuthRelativeSet

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.RotateAzimuthRelative]]
- [[proto/proto/cmd.RotaryPlatform.RotateAzimuthTo]]





## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** mils
- **Precision:** 0


### direction (#2)


#### Metadata

- **Semantic Type:** :enum-label



