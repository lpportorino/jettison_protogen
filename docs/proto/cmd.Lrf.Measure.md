---
id: cmd.Lrf.Measure
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# Measure

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Initiates a single laser rangefinder measurement operation, optionally applying fog mode correction if enabled. Sends the appropriate UART bridge command to start a measured distance acquisition.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Trigger laser rangefinder measurement


### Related State

- [[proto/proto/proto/ser.JonGuiDataLrf]]



### Preconditions

- LRF must be started




