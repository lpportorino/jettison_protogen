---
id: ser.CvChannelMeta
proto: opaque/cv_meta.proto
package: ser
type: message
---

# CvChannelMeta

**Source:** `opaque/cv_meta.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | pts_ns | uint64 | >= 0 |
| 2 | capture_time_ns | uint64 | >= 0 |
| 3 | generation | uint32 | - |
| 4 | sharpness_level0 | float | >= 0, <= 1 |
| 5 | sharpness_level1 | repeated float | min-items: 4, max-items: 4 |
| 6 | sharpness_level2 | repeated float | min-items: 16, max-items: 16 |
| 7 | sharpness_level3 | repeated float | min-items: 64, max-items: 64 |
| 8 | sharpness_compute_ns | uint64 | >= 0 |
| 9 | sharpness_total_ns | uint64 | >= 0 |
| 10 | sharpness_valid | bool | - |
| 11 | sensor_gain | int32 | - |
| 12 | gain_valid | bool | - |




