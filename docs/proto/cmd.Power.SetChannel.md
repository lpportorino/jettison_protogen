---
id: cmd.Power.SetChannel
proto: jon_shared_cmd_power.proto
package: cmd.Power
type: message
---

# SetChannel

**Source:** `jon_shared_cmd_power.proto`

## Description

Sends a command to control the power state of a single device channel (0-7) such as GPS, compass, cameras, thermal core, or heater. The message specifies the channel number and whether to power it on or off.

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

Power channel index


#### Metadata

- **Semantic Type:** :count


### power_on (#2)

Power consumption in watts


#### Metadata

- **Semantic Type:** :toggle-state



