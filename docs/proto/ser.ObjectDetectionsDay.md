---
id: ser.ObjectDetectionsDay
proto: opaque/object_detections_day.proto
package: ser
type: message
---

# ObjectDetectionsDay

**Source:** `opaque/object_detections_day.proto`

## Description

Object detection results for the day (visible-light) camera channel. Produced by the YOLO/TensorRT detector process running inference on day camera frames at approximately 30 fps. The detector sends results via IPC to the bezoar native library, which caches them in a seqlock-protected store and encodes to nanopb on demand. The encoded payload is injected by cv-gateway into `JonGUIState.opaque_payloads` as a `JonOpaquePayload` identified by UUID `019c40f6-825c-7f4c-8284-ddad4375ed9b`. Each message carries the full set of detected objects for a single frame along with inference metadata for latency monitoring and frame correlation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | status | [[proto/ser.DetectionStatus]] | defined enum value only, not in: 0 |
| 2 | detections | repeated [[proto/ser.ObjectDetection]] | max-items: 256 |
| 3 | latency_ns | uint64 | >= 0 |
| 4 | frame | [[proto/ser.DetectionFrameMeta]] | - |
| 5 | config | [[proto/ser.DetectionConfig]] | - |
| 6 | capture_monotonic_us | uint64 | >= 0 |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget



### Related State

- [[proto/ser.JonGuiDataCameraDay]]






## Field Notes


### status (#1)

Current inference status for this detection cycle. Must be a valid `DetectionStatus` enum value (UNSPECIFIED/0 is excluded). Indicates whether inference succeeded (`OK`), the engine is still initializing (`NOT_READY`), the detector timed out waiting for a frame (`IPC_TIMEOUT`), the TensorRT engine failed (`INFER_FAILED`), or an unclassified error occurred (`ERROR`). Consumers should check this before reading the `detections` array.


#### Metadata

- **Semantic Type:** :enum-label


### detections (#2)

Array of detected objects in the current frame, each containing a bounding box in NDC coordinates (-1.0 to 1.0), a confidence score, and a class ID. Up to 256 detections per frame. The array is empty when no objects are detected or when status is not `OK`. Bounding box coordinates follow the same NDC convention as `JonGuiDataROI`, making them resolution-independent.


### latency_ns (#3)

End-to-end inference latency in nanoseconds, measuring the time from frame input to detection output in the TensorRT pipeline. Used for performance monitoring and pipeline health diagnostics.


#### Metadata

- **Semantic Type:** :duration
- **Unit:** ns
- **Precision:** 0


### frame (#4)

Metadata about the source frame that was analyzed, including presentation timestamp (`pts_ns`), capture timestamp (`capture_time_ns`), generation counter for CUDA IPC correlation, and source frame dimensions (width/height). Enables temporal correlation between detection results and the video pipeline.


### config (#5)

Inference configuration parameters that were active when this detection was produced, including the confidence threshold used for filtering and the NMS (Non-Maximum Suppression) IoU threshold used for duplicate suppression. Allows consumers to understand the sensitivity settings behind the results.


### capture_monotonic_us (#6)

Monotonic clock timestamp in microseconds (`CLOCK_MONOTONIC`) recording when the source frame was captured. Follows the same `CvMeta` correlation pattern used elsewhere in the system, enabling precise frame-to-detection matching across the day camera pipeline.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** μs
- **Precision:** 0



