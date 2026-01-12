---
id: cmd.RotaryPlatform.SetPlatformAzimuth
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetPlatformAzimuth

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Calibrates the platform's absolute azimuth reference point during compass calibration, aligning the rotary system's mechanical coordinate frame with magnetic north. Unlike SetAzimuthValue which moves to a position, this sets the platform-level azimuth offset used to establish orientation reference.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.RotateAzimuthTo]]





## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2



