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


### Purpose

Starts the thermal camera subsystem


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.Stop]]


### Preconditions

- System must be powered




