---
id: cmd.Lrf.TargetDesignatorOff
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# TargetDesignatorOff

**Source:** `jon_shared_cmd_lrf.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Turns off laser target designator


### Related State

- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.Lrf.TargetDesignatorOnModeA]]
- [[proto/proto/cmd.Lrf.TargetDesignatorOnModeB]]


### Preconditions

- LRF must be started


### Implementation Notes

Safety command to disable laser designation



