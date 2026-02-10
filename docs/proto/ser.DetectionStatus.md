---
id: ser.DetectionStatus
proto: opaque/detection_common.proto
package: ser
type: enum
---

# DetectionStatus

**Source:** `opaque/detection_common.proto`

## Description

Detector-agnostic inference status codes reported by the object detection pipeline. Indicates whether the most recent inference cycle completed successfully or identifies the specific failure mode. Used as the `status` field in [[proto/ser.ObjectDetectionsDay]] and [[proto/ser.ObjectDetectionsHeat]] to communicate pipeline health. A status other than OK means the `detections` array should be considered empty or stale.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | DETECTION_STATUS_UNSPECIFIED | Unspecified/default value |
| 1 | DETECTION_STATUS_OK | Detection running normally |
| 2 | DETECTION_STATUS_NOT_READY | Detection system not ready |
| 3 | DETECTION_STATUS_IPC_TIMEOUT | IPC communication timeout |
| 4 | DETECTION_STATUS_INFER_FAILED | Neural network inference failed |
| 5 | DETECTION_STATUS_ERROR | Detection system error |

