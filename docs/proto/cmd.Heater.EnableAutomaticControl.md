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
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms
- **Purpose:** Controls automatic PID-based temperature regulation for glass heater channels (Day Camera Lens, Rangefinder Lens, Thermal Camera Lens)

### Notes

In the frontend, this command is triggered via a toggle button in the Glass Heater Control panel (`jonHeaterPanel.ts`). The toggle pairs `EnableAutomaticControl` with `DisableAutomaticControl` - toggling ON sends this command, toggling OFF sends the disable command. The UI displays a pending state for 2000ms while waiting for the `automaticControlEnabled` flag in `ser.JonGuiDataHeater` to reflect the change.

### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.DisableAutomaticControl]]
- [[proto/cmd.Heater.SetAutomaticControlParams]]





