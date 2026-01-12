---
id: cmd.Power.SetAll
proto: jon_shared_cmd_power.proto
package: cmd.Power
type: message
---

# SetAll

**Source:** `jon_shared_cmd_power.proto`

## Description

Sets the power state for all 8 system power channels (0-7) simultaneously; when powering off, the ORIN NUC channel (channel 5) is safely skipped to prevent remote shutdown of the main compute unit.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataPower]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.Power.SetChannel]]



### Implementation Notes

Channel 5 (ORIN NUC) is protected and skipped when powering off



## Field Notes


### power_on (#1)


#### Metadata

- **Semantic Type:** :raw



