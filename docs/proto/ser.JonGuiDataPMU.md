---
id: ser.JonGuiDataPMU
proto: jon_shared_data_pmu.proto
package: ser
type: message
---

# JonGuiDataPMU

**Source:** `jon_shared_data_pmu.proto`

## Description

Power Management Unit status. Reports battery/power system state including temperature, voltage, current sensor (INA) readings, heater state, charging status, and environmental data.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | temperature | double | >= -273.15, <= 660.32 |
| 3 | is_started | bool | - |
| 4 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |
| 5 | voltage | double | >= 2, <= 60 |
| 6 | heater_power_state | bool | - |
| 7 | ina_voltage | double | >= 0, <= 36 |
| 8 | ina_current | double | >= -10000, <= 10000 |
| 9 | ina_power | double | >= 0, <= 100000 |
| 10 | ina_power_fault | bool | - |
| 11 | charge_disabled | bool | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Reports comprehensive power management status including battery voltage, current draw, power consumption, temperature, heater state, and charging status.



### Related Commands

- [[proto/cmd.PMU.Start]]
- [[proto/cmd.PMU.TurnOn]]
- [[proto/cmd.PMU.ChargeEnable]]





## Field Notes


### temperature (#1)

PMU board temperature.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### is_started (#3)

Whether PMU monitoring is active.


### meteo (#4)

See [[proto/ser.JonGuiDataMeteo]]


### voltage (#5)

Main battery/power bus voltage.


#### Metadata

- **Semantic Type:** :voltage
- **Unit:** V
- **Precision:** 2


### heater_power_state (#6)

Whether the PMU heater is powered on.


### ina_voltage (#7)

INA current sensor voltage reading.


#### Metadata

- **Semantic Type:** :voltage
- **Unit:** V
- **Precision:** 2


### ina_current (#8)

INA current sensor current reading. Negative values indicate reverse current flow.


#### Metadata

- **Semantic Type:** :current
- **Unit:** mA
- **Precision:** 0


### ina_power (#9)

INA current sensor power reading.


#### Metadata

- **Semantic Type:** :power
- **Unit:** mW
- **Precision:** 0


### ina_power_fault (#10)

Indicates a power fault detected by the INA current sensor.


### charge_disabled (#11)

Whether battery charging is currently disabled.



