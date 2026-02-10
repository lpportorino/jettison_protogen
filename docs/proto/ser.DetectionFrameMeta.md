---
id: ser.DetectionFrameMeta
proto: opaque/detection_common.proto
package: ser
type: message
---

# DetectionFrameMeta

**Source:** `opaque/detection_common.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | pts_ns | uint64 | >= 0 |
| 2 | capture_time_ns | uint64 | >= 0 |
| 3 | generation | uint32 | - |
| 4 | width | uint32 | >= 1 |
| 5 | height | uint32 | >= 1 |




