---
id: cmd.Compass.SetOffsetAngleElevation
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# SetOffsetAngleElevation

**Source:** `jon_shared_cmd_compass.proto`

## Description

Sets the compass elevation angle offset calibration value to correct for mounting or measurement errors in the vertical axis. This allows manual adjustment of the compass elevation reading by applying a fixed offset to compensate for non-level mounting or local geomagnetic anomalies.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -90, <= 90 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Sets compass elevation angle offset for calibration correction


### Related State

- [[proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/cmd.Compass.SetOffsetAngleAzimuth]]
- [[proto/cmd.Compass.Start]]


### Preconditions

- Compass must be started


### Implementation Notes

Used for manual calibration adjustments to correct for tilt or mounting errors.





## Field Notes


### value (#1)

Elevation angle offset value in the range -90 to +90. Negative values tilt the reference plane down, positive values tilt up.

<!-- NEEDS_REVIEW: Proto constraints (-90 to 90) suggest degrees, but UI displays "mils". Verify actual unit with hardware team. -->

#### Metadata

- **Semantic Type:** :angle
- **Unit:** mils
- **Precision:** 0
- **Display Format:** `{value} mils`



