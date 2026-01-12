---
id: cmd.DayCamera.Start
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Start

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Starts/powers on the day camera.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Starts and powers on the day camera module.

### Related State

- [[ser.JonGuiDataCameraDay]]

### Preconditions

- System must be powered
- Camera must not already be running

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The toggle should show "on" state once the camera is started. Coordinate with Stop command for toggle behavior.

## Fields (Empty Message)

| # | Field | Type | Constraints |
|---|-------|------|-------------|



