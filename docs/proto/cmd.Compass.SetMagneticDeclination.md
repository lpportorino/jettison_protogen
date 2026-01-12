---
id: cmd.Compass.SetMagneticDeclination
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# SetMagneticDeclination

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

Sets magnetic declination correction for compass




### Preconditions

- Compass must be started




## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** mils
- **Precision:** 0



