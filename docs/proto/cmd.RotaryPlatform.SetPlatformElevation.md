---
id: cmd.RotaryPlatform.SetPlatformElevation
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetPlatformElevation

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Calibrates the rotary platform's elevation reference baseline by setting an absolute calibration value. Unlike SetElevationValue which moves the elevation axis during normal operation, this command establishes the platform's elevation offset that persists as the reference baseline.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -90, <= 90 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Sets the platform elevation angle directly


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataRotaryPlatform]]


### Related Commands

- [[proto/proto/proto/proto/proto/cmd.RotaryPlatform.SetPlatformAzimuth]]
- [[proto/proto/proto/proto/proto/cmd.RotaryPlatform.SetPlatformBank]]



### Implementation Notes

Direct position control for elevation axis



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2
- **Display Format:** `{value}°`



