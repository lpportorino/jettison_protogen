---
id: cmd.Lrf.TargetDesignatorOff
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# TargetDesignatorOff

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Disables the laser target designator pointer on the LRF device. Triggered when the gamepad pointer button is released or manually via UI commands.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.Lrf.TargetDesignatorOnModeA]]
- [[proto/proto/proto/proto/proto/proto/cmd.Lrf.TargetDesignatorOnModeB]]


### Preconditions

- LRF must be started


### Implementation Notes

Safety command to disable laser designation



