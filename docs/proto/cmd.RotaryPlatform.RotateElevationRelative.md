---
id: cmd.RotaryPlatform.RotateElevationRelative
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateElevationRelative

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -90, <= 90 |
| 2 | speed | double | >= 0, <= 1 |
| 3 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :press-accelerating
- **Feedback:** :optimistic-visual


### Purpose

Rotate platform elevation relative to current position with specified speed and direction


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.RotateElevationTo]]
- [[proto/proto/cmd.RotaryPlatform.HaltElevation]]
- [[proto/proto/cmd.RotaryPlatform.SetElevationValue]]


### Preconditions

- Rotary platform must be started




## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`


### speed (#2)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 2
- **Display Format:** `{value * 100}%`


### direction (#3)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `{direction}`



