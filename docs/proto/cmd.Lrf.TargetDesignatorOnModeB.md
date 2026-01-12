---
id: cmd.Lrf.TargetDesignatorOnModeB
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# TargetDesignatorOnModeB

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Enables the laser pointer on the LRF device in mode B, sending a hardware command to activate pointer mode 2 and updating the system state accordingly.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Enable laser target designator in Mode B



### Related Commands

- [[proto/proto/cmd.Lrf.TargetDesignatorOnModeA]]
- [[proto/proto/cmd.Lrf.TargetDesignatorOff]]


### Preconditions

- LRF must be started




