---
id: ser.JonGuiDataPower
proto: jon_shared_data_power.proto
package: ser
type: message
---

# JonGuiDataPower

**Source:** `jon_shared_data_power.proto`

## Description

Represents real-time power distribution state across all 8 system channels (GPS, Compass, LRF, Day Camera, Thermal Camera, ORIN NUC, Thermal Core, and Heater), with each channel tracking voltage, current, power consumption, on/off state, and fault alarm status.

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
| 9 | accumulator_state | [[proto/ser.JonGuiDataAccumulatorStateIdx]] | - |
| 10 | ext_bat_capacity | int32 | - |
| 11 | ext_bat_status | [[proto/ser.JonGuiDataExtBatStatus]] | - |
| 12 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Real-time power monitoring for all channels with voltage, current, and alarm status



### Related Commands

- [[proto/cmd.Power.SetAll]]
- [[proto/cmd.Power.SetChannel]]
- [[proto/cmd.Power.SetAlertThreshold]]



### Implementation Notes

Contains 8 channel structures (s0-s7), each with voltage/current/power/state



## Field Notes


### s0 (#1)

See [[proto/ser.JonGuiDataPowerModule]]


### s1 (#2)

See [[proto/ser.JonGuiDataPowerModule]]


### s2 (#3)

See [[proto/ser.JonGuiDataPowerModule]]


### s3 (#4)

See [[proto/ser.JonGuiDataPowerModule]]


### s4 (#5)

See [[proto/ser.JonGuiDataPowerModule]]


### s5 (#6)

See [[proto/ser.JonGuiDataPowerModule]]


### s6 (#7)

See [[proto/ser.JonGuiDataPowerModule]]


### s7 (#8)

See [[proto/ser.JonGuiDataPowerModule]]


### accumulator_state (#9)

See [[proto/ser.JonGuiDataAccumulatorStateIdx]]


#### Metadata

- **Semantic Type:** :enum-label


### ext_bat_capacity (#10)

External battery capacity percentage


### meteo (#12)

Internal environmental sensor data from the power distribution board, providing temperature, humidity, and pressure readings for system monitoring and environmental diagnostics.



