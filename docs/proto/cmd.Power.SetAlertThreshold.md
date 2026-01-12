---
id: cmd.Power.SetAlertThreshold
proto: jon_shared_cmd_power.proto
package: cmd.Power
type: message
---

# SetAlertThreshold

**Source:** `jon_shared_cmd_power.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | channel | uint32 | <= 7 |
| 2 | threshold_ma | uint32 | <= 10000 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :pending-timeout


### Purpose

Sets overcurrent alert threshold for power channel monitoring


### Related State

- [[proto/proto/ser.JonGuiDataPower]]




### Implementation Notes

Configures per-channel overcurrent protection thresholds



## Field Notes


### channel (#1)


#### Metadata

- **Semantic Type:** :count
- **Display Format:** `Channel {value}`


### threshold_ma (#2)


#### Metadata

- **Semantic Type:** :current
- **Unit:** mA
- **Display Format:** `{value} mA`



