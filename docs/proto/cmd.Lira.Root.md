---
id: cmd.Lira.Root
proto: jon_shared_cmd_lira.proto
package: cmd.Lira
type: message
---

# Root

**Source:** `jon_shared_cmd_lira.proto`

## Description

Root command container for LIRA target designation subsystem. Routes target refinement commands containing geospatial coordinates, directional information, and distance measurements for target tracking operations.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | refine_target | [[proto/cmd.Lira.Refine_target]] | - |


## Oneofs


### cmd (required)

Fields: #1




## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Root message container for all LIRA (target designation) commands


### Related State

- [[proto/ser.JonGuiDataLrf]] - Contains `target` field with current LRF measurement and `is_refining` flag


### Related Commands

- [[proto/cmd.Lrf.RefineOn]] - Enables target refinement mode on LRF
- [[proto/cmd.Lrf.RefineOff]] - Disables target refinement mode


### Preconditions

- LRF subsystem must be started (`cmd.Lrf.Start`)
- Target refinement mode should be enabled (`cmd.Lrf.RefineOn`)



### Implementation Notes

This is a oneof wrapper containing all LIRA command types



## Field Notes


### refine_target (#1)

Command to update target tracking with refined geospatial coordinates from LRF measurements. See [[proto/cmd.Lira.Refine_target]] for the full message structure including GPS coordinates, azimuth/elevation angles, distance, and UUID.



