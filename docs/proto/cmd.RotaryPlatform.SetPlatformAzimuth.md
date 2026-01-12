---
id: cmd.RotaryPlatform.SetPlatformAzimuth
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetPlatformAzimuth

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | > -360, < 360 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Sets absolute platform azimuth angle


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.RotateAzimuthTo]]





## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2



