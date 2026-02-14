---
id: cmd.HeatCamera.Start
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Start

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Initiates startup of the thermal camera sensor and begins capturing thermal imaging data. This parameterless lifecycle command enables the heat camera subsystem, typically triggered via a toggle button in the UI with pending-timeout feedback.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Starts the thermal camera subsystem. After sending, the UI enters a pending state until `cameraHeat.isStarted` becomes `true` or the timeout expires.


### Related State

- [[proto/ser.JonGuiDataCameraHeat]] - Contains `isStarted` field that indicates whether the thermal camera is running


### Related Commands

- [[proto/cmd.HeatCamera.Stop]] - Paired command to stop the thermal camera


### Preconditions

- System must be powered
- Camera must not already be started (UI prevents re-sending while pending)


### UI Implementation

The `jon-heat-camera-toggle` Lit component implements a toggle button in the command palette under "Power" heading. The button:
- Displays "Start" when camera is stopped, "Stop" when running
- Shows a pending spinner during state transition
- Uses thermal camera power icons (on/off states)
- Clears pending state when `cameraHeat.isStarted` matches expected value or timeout expires

**Component path:** `frontend/ts/components/lit/jonCommandPalette/elements/jonHeatCameraToggle.ts`

**Command sender:** `frontend/ts/cmd/cmdSender/cmdHeatCamera.ts` exports `heatCameraStart()`




