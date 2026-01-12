---
id: cmd.Lrf.RefineOn
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# RefineOn

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Enables LRF refine mode to allow for precise targeting adjustments. When activated, the refine mode flag is set to true on the device, enabling fine-grained control for accurate target designation.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.Lrf.RefineOff]]
- [[proto/proto/proto/proto/proto/proto/cmd.Lrf.Measure]]


### Preconditions

- LRF must be started




