---
id: cmd.Gps.Start
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# Start

**Source:** `jon_shared_cmd_gps.proto`

## Description

Starts the GPS module and begins receiving position data. This parameterless lifecycle command initializes the GPS hardware and triggers data collection, typically activated via a power toggle button in the UI.

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

- [[proto/proto/proto/ser.JonGuiDataGps]]


### Related Commands

- [[proto/proto/proto/cmd.Gps.Stop]]


### Preconditions

- System powered on


### Implementation Notes

Lifecycle command to initialize GPS hardware



