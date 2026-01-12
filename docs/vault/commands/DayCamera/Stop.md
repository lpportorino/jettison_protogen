---
id: cmd.DayCamera.Stop
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Stop

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Stops/powers off the day camera.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Stops and powers off the day camera module.

### Related State

- [[ser.JonGuiDataCameraDay]]

### Preconditions

- Camera must be running

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The toggle should show "off" state once the camera is stopped. Coordinate with Start command for toggle behavior.

## Fields (Empty Message)

| # | Field | Type | Constraints |
|---|-------|------|-------------|



