---
id: cmd.Power.SetAlertThreshold
proto: jon_shared_cmd_power.proto
package: cmd.Power
type: message
---

# SetAlertThreshold

**Source:** `jon_shared_cmd_power.proto`

## Description

Sets the overcurrent alert threshold for a power channel, specifying a maximum current limit (in milliamps) that triggers an alert when exceeded on channels 0-7.

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

- [[proto/ser.JonGuiDataPower]]


### Preconditions

- Power management must be active


### Implementation Notes

Configures per-channel overcurrent protection thresholds



## Field Notes


### channel (#1)

Power channel index


#### Metadata

- **Semantic Type:** :count
- **Display Format:** `Channel {value}`


### threshold_ma (#2)

Alert threshold in milliamps


#### Metadata

- **Semantic Type:** :count
- **Unit:** mA
- **Display Format:** `{value} mA`



