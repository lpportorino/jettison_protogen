---
id: cmd.HeatCamera.FocusIn
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusIn

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :press-accelerating
- **Feedback:** :fire-and-forget


### Purpose

Continuously focus toward near (while held)


### Related State

- [[proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.FocusOut]]
- [[proto/proto/cmd.HeatCamera.FocusStop]]



### Implementation Notes

Empty message - trigger only. Used with button press/release or gamepad



