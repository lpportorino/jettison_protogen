---
id: cmd.HeatCamera.FocusStop
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusStop

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

*No description yet.*

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

- [[proto/proto/cmd.HeatCamera.FocusIn]]
- [[proto/proto/cmd.HeatCamera.FocusOut]]


### Preconditions

- Thermal camera must be started
- Focus movement in progress




