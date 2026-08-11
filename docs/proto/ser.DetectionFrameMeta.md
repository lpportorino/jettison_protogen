---
id: ser.DetectionFrameMeta
proto: opaque/detection_common.proto
package: ser
type: message
---

# DetectionFrameMeta

**Source:** `opaque/detection_common.proto`

## Description

Frame metadata for temporal correlation between detection results and the video pipeline. Carried as a sub-message within `ObjectDetectionsDay` and `ObjectDetectionsHeat`, providing the timestamps, generation counter, and dimensions of the source frame that was analyzed by the inference engine. These fields originate from the CUDA IPC shared memory control structure (`CudaIpcControl`), where the pipeline producer writes them during each frame push under a seqlock. The bezoar native library reads these values via its `CudaIpcReader`, caches them in the detection batch, and encodes them into the nanopb output. Consumers use this metadata to correlate detection bounding boxes with the correct video frame and to verify that detection results match the expected frame dimensions for coordinate mapping.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | pts_ns | uint64 | >= 0 |
| 2 | capture_time_ns | uint64 | >= 0 |
| 3 | generation | uint32 | - |
| 4 | width | uint32 | >= 1 |
| 5 | height | uint32 | >= 1 |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget








## Field Notes


### pts_ns (#1)

GStreamer presentation timestamp of the source frame in nanoseconds. Extracted from `GST_BUFFER_PTS` in the pipeline probe callback and passed through the CUDA IPC control structure. Set to 0 when the PTS is not valid (`GST_CLOCK_TIME_IS_VALID` returns false). Used to correlate detection results with a specific frame in the video stream timeline.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns
- **Precision:** 0


### capture_time_ns (#2)

Wall-clock capture timestamp in nanoseconds, recorded from `CLOCK_MONOTONIC` at the GStreamer pipeline probe callback when the frame is received. Written to the CUDA IPC shared memory alongside `pts_ns` and read by the cv-gateway consumer. Provides a monotonic reference point for measuring end-to-end pipeline latency from frame capture to detection output.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns
- **Precision:** 0


### generation (#3)

Monotonically increasing frame generation counter from the CUDA IPC shared memory control structure. Atomically incremented by the pipeline producer on each frame push and used by consumers to detect new frames via futex-based wakeup. Enables the detection reader to determine whether new frame data is available without polling, and allows correlation of detection results with the specific CUDA IPC buffer generation they were inferred from.


#### Metadata

- **Semantic Type:** :count


### width (#4)

Width of the source video frame in pixels, as reported by the CUDA IPC control structure. Must be at least 1. Used by consumers to verify that detection bounding box NDC coordinates correspond to the expected frame resolution and to perform any pixel-space coordinate transformations.


#### Metadata

- **Semantic Type:** :count
- **Unit:** px


### height (#5)

Height of the source video frame in pixels, as reported by the CUDA IPC control structure. Must be at least 1. Together with `width`, defines the source frame dimensions that the inference engine operated on, allowing consumers to map NDC bounding box coordinates back to pixel coordinates when needed.


#### Metadata

- **Semantic Type:** :count
- **Unit:** px



