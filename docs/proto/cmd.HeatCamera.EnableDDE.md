---
id: cmd.HeatCamera.EnableDDE
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# EnableDDE

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Enables Digital Detail Enhancement (DDE) on the thermal camera to enhance image detail visibility. This parameterless command activates additional image processing that sharpens edges and improves fine feature visibility in the thermal image.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings <!-- Image processing toggle for thermal camera -->
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout <!-- UI shows pending state for 2000ms until state confirmation -->


### Purpose

Enables Digital Detail Enhancement on thermal camera


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.DisableDDE]]
- [[proto/cmd.HeatCamera.SetDDELevel]]



### Implementation Notes

DDE (Digital Detail Enhancement) enhances image detail visibility in thermal imagery by sharpening edges and improving fine feature visibility. The UI presents this as part of a toggle control (`jon-dde-ui`) with a dedicated control palette for adjusting the DDE level (0-100). When toggled, the UI enters a pending state for 2000ms, waiting for confirmation via the `ddeEnabled` field in `ser.JonGuiDataCameraHeat`. The transient keyboard overlay (`t` key) also provides toggle functionality alongside level adjustment controls.



