---
id: cmd.HeatCamera.Start
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Start

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Starts/powers on the thermal camera.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Starts and powers on the thermal camera module.

### Related State

- [[ser.JonGuiDataCameraHeat]]

### Preconditions

- System must be powered
- Camera must not already be running

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The toggle should show "on" state once the camera is started. Coordinate with Stop command for toggle behavior.

## Fields (Empty Message)

| # | Field | Type | Constraints |
|---|-------|------|-------------|



