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

- [[proto/ser.JonGuiDataCompass#magneticDeclination]]



### Preconditions

- Compass must be started


### Implementation Notes

The `value` field is a bounded absolute angle (-180 to 180 degrees), so this command writes an absolute declination over its range rather than an incremental step. Although tagged `:stepper` for the fine-adjustment affordance, a bounded absolute `Set*` value of this shape is presented as a bounded absolute control (a `:slider` over its range), not a discrete stepper.



## Field Notes


### value (#1)

Angle value in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** °
- **Precision:** 0



