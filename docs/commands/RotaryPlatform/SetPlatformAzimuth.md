---
id: cmd.RotaryPlatform.SetPlatformAzimuth
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetPlatformAzimuth

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Sets the platform azimuth/heading angle. Controls the horizontal rotation of the rotary platform.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | > -360, < 360 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :pending-timeout


### Purpose

Controls the horizontal rotation (azimuth/heading) of the rotary platform. The platform rotates to the specified angle in degrees.


### Related State

- [[ser.JonGuiDataRotary]]




### Implementation Notes

Expect state confirmation within ~1s. Implement 3s timeout for pending indicator. Consider implementing a circular angle picker UI with both drag control and direct numeric input. Handle angle wrapping for values outside -360 to +360 range.



## Field Notes


### value (#1)

Azimuth angle in degrees. Positive values typically represent clockwise rotation from north (0°).


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 1
- **Display Format:** `{value}°`



