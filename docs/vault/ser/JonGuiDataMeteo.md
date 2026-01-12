---
id: ser.JonGuiDataMeteo
proto: jon_shared_data_types.proto
package: ser
type: message
---

# JonGuiDataMeteo

**Source:** `jon_shared_data_types.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | temperature | double | >= -273.15, <= 150 |
| 2 | humidity | double | >= 0, <= 100 |
| 3 | pressure | double | >= 0, <= 120000 |



