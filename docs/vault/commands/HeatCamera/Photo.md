---
id: cmd.HeatCamera.Photo
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Photo

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Captures a still thermal image from the heat camera. This is a fire-and-forget command that triggers the thermal photo capture process.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :dual-feedback
- **Timeout:** 2000ms

### Purpose

Initiates thermal photo capture. Success is indicated by a change in the LRF target ID in state.

### Related State

- [[ser.JonGuiDataCameraHeat]]
- [[ser.JonGuiDataLrf]]

### Implementation Notes

Show pending state during capture. On success (detected via LRF target ID change), flash the button active state for 500ms. Thermal photos capture radiometric data along with the visible thermal image.



