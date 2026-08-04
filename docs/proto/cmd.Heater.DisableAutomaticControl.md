---
id: cmd.Heater.DisableAutomaticControl
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# DisableAutomaticControl

**Source:** `jon_shared_cmd_heater.proto`

## Description

Disables the PID-based automatic temperature regulation loop for all heater channels. When received, the heater module resets all PID controller states (clearing integral windup) and immediately sends zero power to the heating hardware, stopping all active heating. The `automatic_control_enabled` flag in the heater state is set to `false`. This is a parameterless command; the heater remains started and can still accept manual `SetHeating` commands after automatic control is disabled. The heater `Stop` command also implicitly disables automatic control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout



### Related State

- [[proto/ser.JonGuiDataHeater#automatic_control_enabled]]


### Related Commands

- [[proto/cmd.Heater.EnableAutomaticControl]]
- [[proto/cmd.Heater.SetAutomaticControlParams]]
- [[proto/cmd.Heater.SetHeating]]
- [[proto/cmd.Heater.Stop]]





