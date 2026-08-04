---
id: cmd.Gps.Stop
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# Stop

**Source:** `jon_shared_cmd_gps.proto`

## Description

Stops the GPS receiver hardware and ceases position data collection. This parameterless lifecycle command shuts down the GPS module, typically triggered via a power toggle button in the UI with fire-and-forget feedback.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops the GPS module


### Related State

- [[proto/ser.JonGuiDataGps#is_started]]


### Related Commands

- [[proto/cmd.Gps.Start]]



### Implementation Notes

Lifecycle command to shutdown GPS hardware



