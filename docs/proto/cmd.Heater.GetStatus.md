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

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :sensor
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Requests the heater subsystem to send its current status. The response is received via ser.JonGuiDataHeater.


### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.Start]]
- [[proto/cmd.Heater.Stop]]
- [[proto/cmd.Heater.SetHeating]]





