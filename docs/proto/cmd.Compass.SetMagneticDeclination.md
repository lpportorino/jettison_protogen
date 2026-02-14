---
id: cmd.Compass.SetMagneticDeclination
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# SetMagneticDeclination

**Source:** `jon_shared_cmd_compass.proto`

## Description

Sets the magnetic declination correction value for the compass to convert magnetic north readings to true north. Magnetic declination is the angle between magnetic north (as read by the compass) and true north, which varies by geographic location and changes over time.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -180, < 180 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Sets magnetic declination correction for compass


### Related State

- [[proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/cmd.Compass.SetOffsetAngleAzimuth]]
- [[proto/cmd.Compass.SetOffsetAngleElevation]]


### Preconditions

- Compass must be started




## Field Notes


### value (#1)

Angle value in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 1



