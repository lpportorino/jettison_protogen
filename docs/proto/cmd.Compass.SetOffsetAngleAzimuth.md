---
id: cmd.Compass.SetOffsetAngleAzimuth
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# SetOffsetAngleAzimuth

**Source:** `jon_shared_cmd_compass.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/proto/cmd.Compass.SetOffsetAngleElevation]]


### Preconditions

- Compass must be started


### Implementation Notes

Used for manual calibration adjustments.



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** milliradians



