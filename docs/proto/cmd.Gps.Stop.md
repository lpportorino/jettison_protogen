---
id: cmd.Gps.Stop
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# Stop

**Source:** `jon_shared_cmd_gps.proto`

## Description

Stops the GPS receiver hardware and ceases position data collection. This parameterless lifecycle command shuts down the GPS module, triggered via a power toggle button in the UI that enters a pending state until `isStarted` changes to false or a 2-second timeout elapses.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Stops the GPS module


### Related State

- [[proto/ser.JonGuiDataGps]]


### Related Commands

- [[proto/cmd.Gps.Start]]



### Implementation Notes

Lifecycle command to shutdown GPS hardware



