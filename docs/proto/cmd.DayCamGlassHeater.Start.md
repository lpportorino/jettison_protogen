---
id: cmd.DayCamGlassHeater.Start
proto: jon_shared_cmd_day_cam_glass_heater.proto
package: cmd.DayCamGlassHeater
type: message
---

# Start

**Source:** `jon_shared_cmd_day_cam_glass_heater.proto`

## Description

Initiates the day camera glass heater subsystem startup sequence. This parameterless lifecycle command initializes the heater control module, enabling subsequent TurnOn/TurnOff commands to activate the heating element for anti-fog and ice protection.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Starts day camera glass heater subsystem


### Related State

- [[proto/proto/proto/ser.JonGuiDataDayCamGlassHeater]]


### Related Commands

- [[proto/proto/proto/cmd.DayCamGlassHeater.Stop]]





