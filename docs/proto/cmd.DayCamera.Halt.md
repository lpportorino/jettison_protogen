---
id: cmd.DayCamera.Halt
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Halt

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCameraDay]]



### Preconditions

- Day camera started


### Implementation Notes

Emergency stop for lens movements, part of zoom/focus control patterns



