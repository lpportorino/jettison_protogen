---
id: ser.JonGuiDataPowerModule
proto: jon_shared_data_power.proto
package: ser
type: message
---

# JonGuiDataPowerModule

**Source:** `jon_shared_data_power.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | voltage | double | >= 0, <= 100 |
| 2 | current | double | >= 0, <= 50 |
| 3 | power | double | >= 0, <= 500 |
| 4 | is_on | bool | - |
| 5 | has_alarm | bool | - |



