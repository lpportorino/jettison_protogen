---
id: cmd.Lrf.TargetDesignatorOnModeA
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# TargetDesignatorOnModeA

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Enables the laser pointer (target designator) in Mode A, allowing the LRF system to project a laser beam on a target for ranging and designation purposes.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Enable laser target designator in Mode A



### Related Commands

- [[proto/proto/cmd.Lrf.TargetDesignatorOnModeB]]
- [[proto/proto/cmd.Lrf.TargetDesignatorOff]]


### Preconditions

- LRF must be started




