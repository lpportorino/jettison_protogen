---
id: cmd.Power.SetChannel
proto: jon_shared_cmd_power.proto
package: cmd.Power
type: message
---

# SetChannel

**Source:** `jon_shared_cmd_power.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | channel | uint32 | <= 7 |
| 2 | power_on | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Control power state for individual hardware channels




### Preconditions

- Valid channel number (0-7)


### Implementation Notes

Channel 5 (ORIN NUC) is protected and cannot be powered off remotely



## Field Notes


### channel (#1)


#### Metadata

- **Semantic Type:** :count


### power_on (#2)


#### Metadata

- **Semantic Type:** :enum-label



