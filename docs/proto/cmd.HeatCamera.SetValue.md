---
id: cmd.HeatCamera.SetValue
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetValue

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Generic command that sets a normalized thermal camera value (0-1 range) for zoom or other parameters. Defined in the HeatCamera command protocol as part of the Zoom submessage for absolute position control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Sets heat camera zoom value (specific usage unclear from codebase)


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataCameraHeat]]




### Implementation Notes

Message defined in proto but no direct usage found in frontend command senders



