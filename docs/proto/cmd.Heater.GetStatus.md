---
id: cmd.Heater.GetStatus
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# GetStatus

**Source:** `jon_shared_cmd_heater.proto`

## Description

Requests the heater subsystem to report its current status including bus voltage, current, power consumption, and temperature status for all three heating zones.

## Fields

This message has no fields. It is a parameterless status request.


## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout

<!-- NEEDS_REVIEW: heaterGetStatus() is defined in frontend cmdHeater.ts but not called directly from UI components. The heater panel reads state reactively from DeviceStateDispatch rather than polling via GetStatus. This command may be used for debugging or initial state fetch. -->

### Purpose

Requests the heater subsystem to send its current status. The response is received via ser.JonGuiDataHeater, which includes:
- Bus voltage, current, and total power consumption
- Per-channel status for all three heating zones (day camera lens, rangefinder lens, thermal camera lens)
- Each channel reports: temperature, applied voltage, target voltage, and enabled state
- Automatic control enabled flag and target temperatures for PID control


### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.Start]]
- [[proto/cmd.Heater.Stop]]
- [[proto/cmd.Heater.SetHeating]]
- [[proto/cmd.Heater.EnableAutomaticControl]]
- [[proto/cmd.Heater.DisableAutomaticControl]]
- [[proto/cmd.Heater.SetAutomaticControlParams]]





