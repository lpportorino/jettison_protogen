---
id: cmd.Compass.SetOffsetAngleElevation
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# SetOffsetAngleElevation

**Source:** `jon_shared_cmd_compass.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/proto/cmd.Compass.SetOffsetAngleAzimuth]]
- [[proto/proto/cmd.Compass.Start]]





## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** mils
- **Precision:** 0
- **Display Format:** `{value} mils`



