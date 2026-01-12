---
id: cmd.Gps.Start
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# Start

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

Starts the GPS module


### Related State

- [[proto/proto/ser.JonGuiDataGps]]


### Related Commands

- [[proto/proto/cmd.Gps.Stop]]


### Preconditions

- System powered on


### Implementation Notes

Lifecycle command to initialize GPS hardware



