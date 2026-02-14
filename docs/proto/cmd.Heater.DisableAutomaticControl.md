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
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Usage Context

In the Glass Heater Control panel, the "Automatic Control" toggle button sends either `EnableAutomaticControl` or `DisableAutomaticControl` depending on the current state. When the toggle is switched from enabled to disabled, this command is sent. The UI shows a pending state for up to 2 seconds while awaiting confirmation via the `automaticControlEnabled` field in `JonGuiDataHeater` state.



### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.EnableAutomaticControl]]
- [[proto/cmd.Heater.SetAutomaticControlParams]]
- [[proto/cmd.Heater.SetHeating]]
- [[proto/cmd.Heater.Stop]]

