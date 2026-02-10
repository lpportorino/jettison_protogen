---
id: ser.ObjectDetectionsDay
proto: opaque/object_detections_day.proto
package: ser
type: message
---

# ObjectDetectionsDay

**Source:** `opaque/object_detections_day.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | status | [[proto/ser.DetectionStatus]] | defined enum value only, not in: 0 |
| 2 | detections | repeated [[proto/ser.ObjectDetection]] | max-items: 256 |
| 3 | latency_ns | uint64 | >= 0 |
| 4 | frame | [[proto/ser.DetectionFrameMeta]] | - |
| 5 | config | [[proto/ser.DetectionConfig]] | - |
| 6 | capture_monotonic_us | uint64 | >= 0 |




