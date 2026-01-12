---
id: cmd.RotaryPlatform.RotateAzimuthTo
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuthTo

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target_value | double | >= 0, < 360 |
| 2 | speed | double | >= 0, <= 1 |
| 3 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :pending-timeout


### Purpose

Rotates azimuth to target angle at specified speed


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.SetPlatformAzimuth]]





## Field Notes


### target_value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2


### speed (#2)


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** %
- **Precision:** 0


### direction (#3)


#### Metadata

- **Semantic Type:** :enum-label



