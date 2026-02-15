---
id: cmd.DayCamera.Start
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Start

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Starts the day camera module, initiating video streaming and enabling camera controls. This parameterless lifecycle command powers on the camera hardware and begins video capture, typically triggered via a toggle button in the UI with pending-timeout feedback.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Starts day camera module initialization


### Related State

- [[proto/ser.JonGuiDataCameraDay]]


### Related Commands

- [[proto/cmd.DayCamera.Stop]]


### Preconditions

- Camera must be powered on




