---
id: cmd.Gps.Stop
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# Stop

**Source:** `jon_shared_cmd_gps.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataGps]]


### Related Commands

- [[proto/proto/cmd.Gps.Start]]



### Implementation Notes

Lifecycle command to shutdown GPS hardware



