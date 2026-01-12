---
id: cmd.DayCamera.SetAutoIris
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetAutoIris

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Enables or disables automatic iris control for the day camera, allowing automatic aperture adjustment based on lighting conditions. When enabled, manual iris control via SetIris is disabled; when disabled, the operator can manually adjust the iris for precise exposure control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Enables or disables automatic iris control for day camera exposure


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.DayCamera.SetIris]]


### Preconditions

- Day camera started


### Implementation Notes

When enabled, disables manual iris control



## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :raw



