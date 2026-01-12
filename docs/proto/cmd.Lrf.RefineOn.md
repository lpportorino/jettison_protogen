---
id: cmd.Lrf.RefineOn
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# RefineOn

**Source:** `jon_shared_cmd_lrf.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Enables LRF refine mode for precision measurements


### Related State

- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.Lrf.RefineOff]]
- [[proto/proto/cmd.Lrf.Measure]]


### Preconditions

- LRF must be started




