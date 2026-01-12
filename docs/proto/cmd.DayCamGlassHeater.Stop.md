---
id: cmd.DayCamGlassHeater.Stop
proto: jon_shared_cmd_day_cam_glass_heater.proto
package: cmd.DayCamGlassHeater
type: message
---

# Stop

**Source:** `jon_shared_cmd_day_cam_glass_heater.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops glass heater control module


### Related State

- [[proto/proto/ser.JonGuiDataDayCamGlassHeater]]


### Related Commands

- [[proto/proto/cmd.DayCamGlassHeater.Start]]
- [[proto/proto/cmd.DayCamGlassHeater.TurnOff]]



### Implementation Notes

Stops heater module (different from turning heater off)



