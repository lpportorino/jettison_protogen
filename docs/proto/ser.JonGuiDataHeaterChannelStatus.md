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




