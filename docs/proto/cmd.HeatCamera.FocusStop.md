---
id: cmd.HeatCamera.FocusStop
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusStop

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Stops continuous thermal camera focus movement initiated by FocusIn or FocusOut commands. This parameterless fire-and-forget command halts ongoing focus motor operation when the user releases the focus control button.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops continuous thermal camera focus movement



### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.FocusIn]]
- [[proto/proto/proto/proto/proto/proto/cmd.HeatCamera.FocusOut]]


### Preconditions

- Thermal camera must be started
- Focus movement in progress




