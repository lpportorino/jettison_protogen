---
id: ser.JonGuiDataPowerModule
proto: jon_shared_data_power.proto
package: ser
type: message
---

# JonGuiDataPowerModule

**Source:** `jon_shared_data_power.proto`

## Description

Represents the real-time power state and telemetry for a single power distribution channel, tracking voltage, current, power consumption, on/off state, and alarm status. Used to monitor individual hardware subsystems for power management and diagnostics.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | voltage | double | >= 0, <= 100 |
| 2 | current | double | >= 0, <= 50 |
| 3 | power | double | >= 0, <= 500 |
| 4 | is_on | bool | - |
| 5 | has_alarm | bool | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Power module status and telemetry



### Related Commands

- [[proto/cmd.Power.SetChannel]]
- [[proto/cmd.Power.SetAll]]





## Field Notes


### voltage (#1)

Bus voltage in volts


#### Metadata

- **Semantic Type:** :voltage
- **Unit:** V
- **Precision:** 2
- **Display Format:** `{value.toFixed(2)}V`


### current (#2)

Current draw in amperes


#### Metadata

- **Semantic Type:** :current
- **Unit:** A
- **Precision:** 3
- **Display Format:** `{value.toFixed(3)}A`


### power (#3)

Power consumption in watts


#### Metadata

- **Semantic Type:** :power
- **Unit:** W
- **Precision:** 2
- **Display Format:** `{value.toFixed(2)}W`


### is_on (#4)

Channel powered on state


#### Metadata

- **Semantic Type:** :raw


### has_alarm (#5)

Alarm triggered state


#### Metadata

- **Semantic Type:** :raw



