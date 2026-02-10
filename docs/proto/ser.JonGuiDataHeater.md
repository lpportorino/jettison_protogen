---
id: ser.JonGuiDataHeater
proto: jon_shared_data_heater.proto
package: ser
type: message
---

# JonGuiDataHeater

**Source:** `jon_shared_data_heater.proto`

## Description

Heater subsystem status. Reports overall bus power consumption (voltage, current, power) and per-channel status for up to 3 heating channels (e.g., camera housing, lens, enclosure).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | bus_voltage_V | float | >= 0 |
| 2 | current_A | float | >= 0 |
| 3 | power_W | float | >= 0 |
| 4 | channel_0 | [[proto/ser.JonGuiDataHeaterChannelStatus]] | - |
| 5 | channel_1 | [[proto/ser.JonGuiDataHeaterChannelStatus]] | - |
| 6 | channel_2 | [[proto/ser.JonGuiDataHeaterChannelStatus]] | - |
| 7 | automatic_control_enabled | bool | - |
| 8 | target_temp_channel_0 | float | >= 0, <= 60 |
| 9 | target_temp_channel_1 | float | >= 0, <= 60 |
| 10 | target_temp_channel_2 | float | >= 0, <= 60 |




## Field Notes


### bus_voltage_V (#1)

Bus voltage in volts


### current_A (#2)

Current draw in amperes


### power_W (#3)

Power consumption in watts


### channel_0 (#4)

See [[proto/ser.JonGuiDataHeaterChannelStatus]]


### channel_1 (#5)

See [[proto/ser.JonGuiDataHeaterChannelStatus]]


### channel_2 (#6)

See [[proto/ser.JonGuiDataHeaterChannelStatus]]


### target_temp_channel_0 (#8)

Target temperature setpoint in degrees Celsius


### target_temp_channel_1 (#9)

Target temperature setpoint in degrees Celsius


### target_temp_channel_2 (#10)

Target temperature setpoint in degrees Celsius



