---
id: cmd.Power.Root
proto: jon_shared_cmd_power.proto
package: cmd.Power
type: message
---

# Root

**Source:** `jon_shared_cmd_power.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | set_channel | [[proto/cmd.Power.SetChannel]] | - |
| 2 | set_all | [[proto/cmd.Power.SetAll]] | - |
| 3 | set_alert_threshold | [[proto/cmd.Power.SetAlertThreshold]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3




## Interaction

- **Category:** :settings
- **UI Pattern:** :tabbed-config


### Purpose

Container for power management commands


### Related State

- [[proto/proto/ser.JonGuiDataPower]]




### Implementation Notes

Root message for controlling device power channels



