---
id: ser.JonGuiDataHeater
proto: jon_shared_data_heater.proto
package: ser
type: message
---

# JonGuiDataHeater

**Source:** `jon_shared_data_heater.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | bus_voltage_V | float | >= 0 |
| 2 | current_A | float | >= 0 |
| 3 | power_W | float | >= 0 |
| 4 | channel_0 | [[proto/ser.JonGuiDataHeaterChannelStatus]] | - |
| 5 | channel_1 | [[proto/ser.JonGuiDataHeaterChannelStatus]] | - |
| 6 | channel_2 | [[proto/ser.JonGuiDataHeaterChannelStatus]] | - |




