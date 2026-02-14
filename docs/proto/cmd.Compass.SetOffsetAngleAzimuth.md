---
id: cmd.Compass.SetOffsetAngleAzimuth
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# SetOffsetAngleAzimuth

**Source:** `jon_shared_cmd_compass.proto`

## Description

Sets the compass azimuth angle offset calibration value to correct for mounting or measurement errors in the horizontal axis. This allows manual adjustment of the compass azimuth reading by applying a fixed offset to compensate for mounting misalignment or sensor drift.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -180, < 180 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Sets compass azimuth angle offset for calibration correction


### Related State

- [[proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/cmd.Compass.SetOffsetAngleElevation]]


### Preconditions

- Compass must be started


### Implementation Notes

Used for manual calibration adjustments.



## Field Notes


### value (#1)

Azimuth offset angle in degrees. Positive values rotate the reported azimuth clockwise, negative values rotate counter-clockwise. Used to compensate for physical mounting misalignment between the compass sensor and the platform's reference frame.

<!-- NEEDS_REVIEW: Frontend code comment says "mils" but proto constraints (-180 to 180) suggest degrees. Verify actual unit with hardware team. -->

#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1
- **Display Format:** `{value}°`



