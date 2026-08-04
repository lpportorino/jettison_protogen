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

- [[proto/ser.JonGuiDataCompass#offsetAzimuth]]


### Related Commands

- [[proto/cmd.Compass.SetOffsetAngleElevation]]


### Preconditions

- Compass must be started


### Implementation Notes

Used for manual calibration adjustments. The `value` field is a bounded absolute angle (-180 to 180 degrees), so this command writes an absolute azimuth offset over its range rather than an incremental step. Although tagged `:stepper` for the fine-adjustment affordance, a bounded absolute `Set*` value of this shape is presented as a bounded absolute control (a `:slider` over its range), not a discrete stepper.



## Field Notes


### value (#1)

Angle value in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** milliradians



