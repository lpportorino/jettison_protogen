---
id: ser.SamTrackingFrameMeta
proto: opaque/sam_tracking_common.proto
package: ser
type: message
---

# SamTrackingFrameMeta

**Source:** `opaque/sam_tracking_common.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | pts_ns | uint64 | >= 0 |
| 2 | capture_time_ns | uint64 | >= 0 |
| 3 | generation | uint32 | - |
| 4 | capture_monotonic_us | uint64 | >= 0 |




