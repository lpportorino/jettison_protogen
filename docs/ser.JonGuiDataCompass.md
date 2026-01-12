---
id: ser.JonGuiDataCompass
proto: jon_shared_data_compass.proto
package: ser
type: message
---

# JonGuiDataCompass

**Source:** `jon_shared_data_compass.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | azimuth | double | >= 0, < 360 |
| 2 | elevation | double | >= -90, <= 90 |
| 3 | bank | double | >= -180, < 180 |
| 4 | offsetAzimuth | double | >= -180, < 180 |
| 5 | offsetElevation | double | >= -90, <= 90 |
| 6 | magneticDeclination | double | >= -180, < 180 |
| 7 | calibrating | bool | - |
| 8 | is_started | bool | - |




