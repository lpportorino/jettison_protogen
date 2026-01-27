---
id: cmd.Heater.SetHeating
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# SetHeating

**Source:** `jon_shared_cmd_heater.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target_0 | float | >= 0, <= 60 |
| 2 | target_1 | float | >= 0, <= 60 |
| 3 | target_2 | float | >= 0, <= 60 |
| 4 | temp_error_0 | float | >= 0, <= 40 |
| 5 | temp_error_1 | float | >= 0, <= 40 |
| 6 | temp_error_2 | float | >= 0, <= 40 |




