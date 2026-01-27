---
id: cmd.Heater.Stop
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# Stop

**Source:** `jon_shared_cmd_heater.proto`

## Description

Stops the heater subsystem, disabling all heating zones and temperature control.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout

### Purpose

Shuts down the heater subsystem. All heating zones will be disabled.

### Related State

- [[proto/ser.JonGuiDataHeater]]

### Related Commands

- [[proto/cmd.Heater.Start]]




