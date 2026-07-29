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
| 0 | DETECTION_STATUS_UNSPECIFIED | The proto3 zero default, not a status. Both carriers — `ObjectDetectionsDay` and `ObjectDetectionsHeat` — constrain their `status` field `not_in: [0]`, so a payload reporting it is rejected at the ingest boundary; a decoded zero means the field was never populated. |
| 1 | DETECTION_STATUS_OK | Inference succeeded for this cycle: the `detections` array is the complete set of objects found in the frame described by `frame`, and may be read. It is the only value under which `detections` is meaningful — under every other value the array is empty or stale. |
| 2 | DETECTION_STATUS_NOT_READY | The detector could not run because it is not initialised yet — either its inference engine or its IPC link to the video pipeline. Expected transiently during startup rather than indicating a fault. |
| 3 | DETECTION_STATUS_IPC_TIMEOUT | No frame arrived from the video pipeline within the detector's wait timeout, so no inference was attempted this cycle. Distinguishes a starved input from a broken detector: the engine itself is healthy and simply had nothing to run on. |
| 4 | DETECTION_STATUS_INFER_FAILED | The inference engine ran and failed on this frame. The complement of `IPC_TIMEOUT`: a frame WAS present, and processing it is what went wrong. |
| 5 | DETECTION_STATUS_ERROR | A failure none of the more specific codes covers. Being the catch-all it carries no diagnosis by itself — the detector writes error detail to stderr rather than embedding it in this message, so the log is the only place the cause exists. |

