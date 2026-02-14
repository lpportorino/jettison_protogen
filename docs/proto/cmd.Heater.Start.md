---
id: cmd.Heater.Start
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# Start

**Source:** `jon_shared_cmd_heater.proto`

## Description

Starts the heater subsystem, enabling temperature monitoring and heating control for all three zones: Day Camera Lens, Rangefinder Lens, and Thermal Camera Lens.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Initializes and starts the heater subsystem. Must be called before SetHeating or automatic control commands will be processed.

<!-- NEEDS_REVIEW: Verify if Start is called automatically at system boot or if it requires explicit invocation -->
Note: This command is typically invoked at system startup rather than from the frontend UI. The frontend heater panel assumes the subsystem is already started and provides controls for automatic temperature control via EnableAutomaticControl/DisableAutomaticControl and SetAutomaticControlParams.


### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.Stop]]
- [[proto/cmd.Heater.EnableAutomaticControl]]
- [[proto/cmd.Heater.DisableAutomaticControl]]
- [[proto/cmd.Heater.SetAutomaticControlParams]]
- [[proto/cmd.Heater.SetHeating]]
- [[proto/cmd.Heater.GetStatus]]





