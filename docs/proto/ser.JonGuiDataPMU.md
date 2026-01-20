---
id: ser.JonGuiDataPMU
proto: jon_shared_data_pmu.proto
package: ser
type: message
---

# JonGuiDataPMU

**Source:** `jon_shared_data_pmu.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | temperature | double | >= -273.15, <= 660.32 |
| 2 | status | bool | - |
| 3 | is_started | bool | - |
| 4 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |




