---
id: ser.JonGuiDataPower
proto: jon_shared_data_power.proto
package: ser
type: message
---

# JonGuiDataPower

**Source:** `jon_shared_data_power.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | s0 | [[proto/ser.JonGuiDataPowerModule]] | - |
| 2 | s1 | [[proto/ser.JonGuiDataPowerModule]] | - |
| 3 | s2 | [[proto/ser.JonGuiDataPowerModule]] | - |
| 4 | s3 | [[proto/ser.JonGuiDataPowerModule]] | - |
| 5 | s4 | [[proto/ser.JonGuiDataPowerModule]] | - |
| 6 | s5 | [[proto/ser.JonGuiDataPowerModule]] | - |
| 7 | s6 | [[proto/ser.JonGuiDataPowerModule]] | - |
| 8 | s7 | [[proto/ser.JonGuiDataPowerModule]] | - |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator


### Purpose

Real-time power monitoring for all channels with voltage, current, and alarm status



### Related Commands

- [[proto/proto/cmd.Power.SetAll]]
- [[proto/proto/cmd.Power.SetChannel]]
- [[proto/proto/cmd.Power.SetAlertThreshold]]



### Implementation Notes

Contains 8 channel structures (s0-s7), each with voltage/current/power/state



## Field Notes


### s0 (#1)


#### Metadata

- **Semantic Type:** :voltage
- **Unit:** V
- **Precision:** 2


### s1 (#2)


#### Metadata

- **Semantic Type:** :current
- **Unit:** A
- **Precision:** 3


### s2 (#3)


#### Metadata

- **Semantic Type:** :power
- **Unit:** W
- **Precision:** 2


### s3 (#4)


#### Metadata

- **Semantic Type:** :raw


### s4 (#5)


#### Metadata

- **Semantic Type:** :raw



