---
id: ser.ObjectDetection
proto: opaque/detection_common.proto
package: ser
type: message
---

# ObjectDetection

**Source:** `opaque/detection_common.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | x1 | float | >= -1, <= 1 |
| 2 | y1 | float | >= -1, <= 1 |
| 3 | x2 | float | >= -1, <= 1 |
| 4 | y2 | float | >= -1, <= 1 |
| 5 | confidence | float | >= 0, <= 1 |
| 6 | class_id | int32 | >= 0, <= 255 |




