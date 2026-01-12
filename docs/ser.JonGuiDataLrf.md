---
id: ser.JonGuiDataLrf
proto: jon_shared_data_lrf.proto
package: ser
type: message
---

# JonGuiDataLrf

**Source:** `jon_shared_data_lrf.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | is_scanning | bool | - |
| 2 | is_measuring | bool | - |
| 3 | measure_id | int32 | >= 0 |
| 4 | target | [[ser.JonGuiDataTarget]] | - |
| 5 | pointer_mode | [[ser.JonGuiDatatLrfLaserPointerModes]] | defined enum value only |
| 6 | fogModeEnabled | bool | - |
| 7 | is_refining | bool | - |
| 8 | is_continuous_measuring | bool | - |
| 9 | is_started | bool | - |




