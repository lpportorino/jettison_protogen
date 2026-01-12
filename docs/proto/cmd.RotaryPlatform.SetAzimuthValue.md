---
id: cmd.RotaryPlatform.SetAzimuthValue
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetAzimuthValue

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, < 360 |
| 2 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :optimistic-visual


### Purpose

Sets the rotary platform to an absolute azimuth angle


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.RotateAzimuthTo]]


### Preconditions

- Rotary platform started
- Position mode


### Implementation Notes

Immediate positioning command with direction control



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 1
- **Display Format:** `{value}°`
- **Presets:** 0.0, 90.0, 180.0, 270.0


### direction (#2)


#### Metadata

- **Semantic Type:** :enum-label



