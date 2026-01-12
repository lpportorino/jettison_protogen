---
id: cmd.RotaryPlatform.SetPlatformElevation
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetPlatformElevation

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Sets the platform elevation/pitch angle. Controls the vertical tilt of the rotary platform.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -90, <= 90 |

## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :pending-timeout
- **Timeout:** 3000ms

### Purpose

Controls the vertical tilt (elevation/pitch) of the rotary platform. The platform tilts to the specified angle in degrees, where 0° is level, positive values are upward tilt, and negative values are downward tilt.

### Related State

- [[ser.JonGuiDataRotary]]

### Implementation Notes

Expect state confirmation within ~1s. Implement 3s timeout for pending indicator. The elevation angle is constrained to -90° (pointing straight down) to +90° (pointing straight up). UI should clearly indicate the valid range and prevent out-of-range inputs.

## Field Notes

### value (#1)

Elevation angle in degrees. 0° is level, positive values point upward, negative values point downward.

#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 1
- **Display Format:** `{value}°`
- **Range:** -90° to +90°



