---
id: cmd.Heater.Start
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# Start

**Source:** `jon_shared_cmd_heater.proto`

## Description

Starts the heater subsystem, enabling temperature monitoring and heating control for all zones.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Initializes and starts the heater subsystem. Must be called before SetHeating commands will be processed.


### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.Stop]]
- [[proto/cmd.Heater.SetHeating]]





