---
id: cmd.Power.SetAll
proto: jon_shared_cmd_power.proto
package: cmd.Power
type: message
---

# SetAll

**Source:** `jon_shared_cmd_power.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | power_on | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Sets power state for all channels simultaneously


### Related State

- [[proto/proto/ser.JonGuiDataPower]]


### Related Commands

- [[proto/proto/cmd.Power.SetChannel]]



### Implementation Notes

Channel 5 (ORIN NUC) is protected and skipped when powering off



## Field Notes


### power_on (#1)


#### Metadata

- **Semantic Type:** :raw



