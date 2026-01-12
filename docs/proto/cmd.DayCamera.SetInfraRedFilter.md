---
id: cmd.DayCamera.SetInfraRedFilter
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetInfraRedFilter

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Enables or disables the infrared filter on the day camera to block IR light for better color reproduction in visible light conditions. This fire-and-forget toggle command switches the physical IR-cut filter state with a boolean flag.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | bool | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Enable or disable infrared filter on day camera


### Related State

- [[proto/proto/proto/ser.JonGuiDataCameraDay]]






## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `Boolean flag`



