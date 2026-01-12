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


### Purpose

Power module status and telemetry



### Related Commands

- [[proto/proto/proto/cmd.Power.SetChannel]]
- [[proto/proto/proto/cmd.Power.SetAll]]





## Field Notes


### voltage (#1)


#### Metadata

- **Semantic Type:** :voltage
- **Unit:** V
- **Precision:** 2
- **Display Format:** `{value.toFixed(2)}V`


### current (#2)


#### Metadata

- **Semantic Type:** :current
- **Unit:** A
- **Precision:** 3
- **Display Format:** `{value.toFixed(3)}A`


### power (#3)


#### Metadata

- **Semantic Type:** :power
- **Unit:** W
- **Precision:** 2
- **Display Format:** `{value.toFixed(2)}W`


### is_on (#4)


#### Metadata

- **Semantic Type:** :raw


### has_alarm (#5)


#### Metadata

- **Semantic Type:** :raw



