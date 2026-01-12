---
id: cmd.Compass.SetMagneticDeclination
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# SetMagneticDeclination

**Source:** `jon_shared_cmd_compass.proto`

## Description

Sets the magnetic declination offset for true north calculation. Magnetic declination is the angle between magnetic north and true north at a given location.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -180, < 180 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :pending-timeout


### Purpose

Configures the magnetic declination offset used to convert magnetic north readings to true north. This value depends on the geographic location and should be set based on local magnetic declination data.


### Related State

- [[ser.JonGuiDataCompass]]




### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The UI should provide a stepper or slider control for adjusting the declination angle in degree increments.



## Field Notes


### value (#1)

Magnetic declination angle in degrees. Positive values indicate magnetic north is east of true north, negative values indicate magnetic north is west of true north.


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`



