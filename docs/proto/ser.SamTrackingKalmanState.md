---
id: ser.SamTrackingKalmanState
proto: opaque/sam_tracking_common.proto
package: ser
type: message
---

# SamTrackingKalmanState

**Source:** `opaque/sam_tracking_common.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | predicted_x | double | >= -1, <= 1 |
| 2 | predicted_y | double | >= -1, <= 1 |
| 3 | velocity_x | double | - |
| 4 | velocity_y | double | - |




