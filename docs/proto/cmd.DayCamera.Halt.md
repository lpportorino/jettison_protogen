---
id: cmd.DayCamera.Halt
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Halt

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Immediately stops zoom or focus motor movement on the day camera. Used as a sub-command within Focus and Zoom composite commands to halt individual lens actuator movement. Part of the emergency stop control pattern for lens operations.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Immediately stops zoom/focus motor movement


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]



### Preconditions

- Day camera started


### Implementation Notes

Emergency stop for lens movements, part of zoom/focus control patterns



