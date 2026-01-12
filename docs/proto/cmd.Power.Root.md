---
id: cmd.Power.Root
proto: jon_shared_cmd_power.proto
package: cmd.Power
type: message
---

# Root

**Source:** `jon_shared_cmd_power.proto`

## Description

Routes power management commands to control individual power channels (0-7) or all channels simultaneously, supporting operations like setting power state per channel, powering all channels on/off, and configuring overcurrent alert thresholds in milliamps.

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

- [[proto/proto/proto/proto/proto/ser.JonGuiDataPower]]




### Implementation Notes

Root message for controlling device power channels



