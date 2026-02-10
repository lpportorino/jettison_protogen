---
id: cmd.RotaryPlatform.SetElevationValue
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetElevationValue

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Instructs the rotary platform to move to an absolute elevation angle specified as a single value (-90 to 90 degrees). This command is triggered from the frontend's position input overlay when a user enters an elevation angle, immediately moving the platform to that exact elevation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -90, <= 90 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget


### Purpose

Set absolute elevation position of rotary platform


### Related State

- [[proto/ser.JonGuiDataRotary]]






## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2



