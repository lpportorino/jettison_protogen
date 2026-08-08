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
- **UI Pattern:** :slider
- **Feedback:** :pending-timeout


### Purpose

Set compass elevation angle offset calibration value


### Related State

- [[proto/ser.JonGuiDataCompass#offsetElevation]]


### Related Commands

- [[proto/cmd.Compass.SetOffsetAngleAzimuth]]
- [[proto/cmd.Compass.Start]]


### Preconditions

- Compass must be started




## Field Notes


### value (#1)

Value (-90 to 90)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 0
- **Display Format:** `{value}°`



