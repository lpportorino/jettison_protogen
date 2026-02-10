---
id: cmd.Heater.EnableAutomaticControl
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# EnableAutomaticControl

**Source:** `jon_shared_cmd_heater.proto`

## Description

Enables PID-based automatic temperature regulation for all heater channels. When enabled, the heater module runs a periodic control loop (default 500ms interval) that computes power output per channel using PID control, comparing current temperatures against targets set via `SetAutomaticControlParams`. Target temperatures and PID gains (kp, ki, kd) are loaded from persistent configuration; this command only activates the control loop. The `automatic_control_enabled` flag in `ser.JonGuiDataHeater` reflects the current state. Use `DisableAutomaticControl` to stop automatic regulation, which also resets PID accumulators and sends zero power to hardware.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout



### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.DisableAutomaticControl]]
- [[proto/cmd.Heater.SetAutomaticControlParams]]





