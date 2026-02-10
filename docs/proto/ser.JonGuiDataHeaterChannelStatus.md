---
id: ser.JonGuiDataHeaterChannelStatus
proto: jon_shared_data_heater.proto
package: ser
type: message
---

# JonGuiDataHeaterChannelStatus

**Source:** `jon_shared_data_heater.proto`

## Description

Status of an individual heater channel. Reports current temperature (°C), applied and target voltages for PWM control, and enabled state.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | temperature | float | - |
| 2 | applied_voltage_V | float | >= 0 |
| 3 | target_voltage_V | float | >= 0 |
| 4 | enabled | bool | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Reports the current state of a single heating channel including temperature, PWM voltage control, and enabled status.



### Related Commands

- [[proto/cmd.Heater.SetHeating]]





## Field Notes


### temperature (#1)

Current measured temperature of this heating zone.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### applied_voltage_V (#2)

Currently applied PWM voltage to the heater element.


#### Metadata

- **Semantic Type:** :voltage
- **Unit:** V
- **Precision:** 2


### target_voltage_V (#3)

Target voltage for PWM control to achieve desired temperature.


#### Metadata

- **Semantic Type:** :voltage
- **Unit:** V
- **Precision:** 2


### enabled (#4)

Whether this heating channel is currently active.



