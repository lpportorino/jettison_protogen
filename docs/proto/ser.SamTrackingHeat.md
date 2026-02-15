---
id: ser.SamTrackingHeat
proto: opaque/sam_tracking_heat.proto
package: ser
type: message
---

# SamTrackingHeat

**Source:** `opaque/sam_tracking_heat.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | status | [[proto/ser.SamTrackingStatus]] | defined enum value only, not in: 0 |
| 2 | state | [[proto/ser.SamTrackingState]] | defined enum value only, not in: 0 |
| 3 | bbox_x1 | double | >= -1, <= 1 |
| 4 | bbox_y1 | double | >= -1, <= 1 |
| 5 | bbox_x2 | double | >= -1, <= 1 |
| 6 | bbox_y2 | double | >= -1, <= 1 |
| 7 | centroid_x | double | >= -1, <= 1 |
| 8 | centroid_y | double | >= -1, <= 1 |
| 9 | confidence | float | >= 0, <= 1 |
| 10 | iou | float | >= 0, <= 1 |
| 11 | mask_rle | bytes | max-len: 65536 |
| 12 | mask_width | uint32 | >= 1, <= 2048 |
| 13 | mask_height | uint32 | >= 1, <= 2048 |
| 14 | mask_pixels | uint32 | - |
| 15 | frame | [[proto/ser.SamTrackingFrameMeta]] | - |
| 16 | kalman | [[proto/ser.SamTrackingKalmanState]] | - |
| 17 | lost_frame_count | uint32 | <= 255 |
| 18 | latency_ns | uint64 | >= 0 |




