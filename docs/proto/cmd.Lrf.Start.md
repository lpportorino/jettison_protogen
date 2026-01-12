---
id: cmd.Lrf.Start
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# Start

**Source:** `jon_shared_cmd_lrf.proto`

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

Starts the laser range finder module


### Related State

- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.Lrf.Stop]]
- [[proto/proto/cmd.Lrf.Measure]]


### Preconditions

- System powered on


### Implementation Notes

Lifecycle command to initialize LRF hardware



