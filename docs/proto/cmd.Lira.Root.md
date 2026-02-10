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

- **Category:** :lifecycle
- **UI Pattern:** :tabbed-config
- **Feedback:** :pending-timeout


### Purpose

Root message container for all LIRA (target designation) commands





### Implementation Notes

This is a oneof wrapper containing all LIRA command types



## Field Notes


### refine_target (#1)

See [[proto/cmd.Lira.Refine_target]]



