---
id: cmd.Heater.AutomaticControlChannelParams
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# AutomaticControlChannelParams

**Source:** `jon_shared_cmd_heater.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target_temperature | float | >= 0, <= 100 |
| 2 | kp | float | >= 0 |
| 3 | ki | float | >= 0 |
| 4 | kd | float | >= 0 |




