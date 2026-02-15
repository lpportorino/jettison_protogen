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

- [[proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/cmd.Lrf.TargetDesignatorOnModeA]]
- [[proto/cmd.Lrf.TargetDesignatorOnModeB]]


### Preconditions

- LRF must be started


### Implementation Notes

Frontend function `lrfTargetDesignatorOff()` in `cmdLRF.ts` sends this command. Forms a toggle group with TargetDesignatorOnModeA and TargetDesignatorOnModeB. The `laser_pointer_mode` field in `ser.JonGuiDataLrf` tracks the current designator state. This is a safety-critical command that disables the laser beam.



