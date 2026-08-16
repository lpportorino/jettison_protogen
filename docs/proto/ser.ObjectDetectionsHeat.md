---
id: ser.ObjectDetectionsHeat
proto: opaque/object_detections_heat.proto
package: ser
type: message
---

# ObjectDetectionsHeat

**Source:** `opaque/object_detections_heat.proto`

## Description

Object detection results for the thermal (heat/IR) camera channel. Produced by the CUDA/TensorRT detector process running in a separate GCC+nvcc-compiled binary, written into a seqlock cache via `bezoar_object_detect_write_heat()`, and read on the JVM hot path via the `bezoar_object_detect_read_heat()` critical downcall (target latency <100us). The native library encodes detection batches to nanopb into a static 12KB buffer. The StateEnricherModule injects the serialized payload into `JonGUIState.opaque_payloads` at inference rate (~30fps). Each detection uses NDC coordinates (-1.0 to 1.0), matching the JonGuiDataROI coordinate system. The day and heat channels use independent seqlock caches and separate DataBus topics for failure isolation.

UUID: `019c40f6-825d-7e0e-9893-87c7b167a751`

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

- [[proto/ser.JonGuiDataCameraHeat]]






## Field Notes


### status (#1)

Current inference status for the heat channel. Must be a valid `DetectionStatus` enum value (UNSPECIFIED/0 is excluded). Reports whether inference succeeded (`OK`), the engine is not yet initialized (`NOT_READY`), no frame arrived within the timeout window (`IPC_TIMEOUT`), the TensorRT engine returned an error (`INFER_FAILED`), or an unclassified error occurred (`ERROR`).


#### Metadata

- **Semantic Type:** :enum-label


### detections (#2)

Repeated list of detected objects in the current thermal frame, up to 256 entries. Each `ObjectDetection` contains bounding box coordinates in NDC space (-1.0 to 1.0), a confidence score (0.0 to 1.0), and a detector-specific class ID (0-255). The list is populated only when `status` is `OK`; otherwise it is empty.


### latency_ns (#3)

End-to-end inference latency in nanoseconds, measured from frame capture to detection result availability. Useful for monitoring detector performance and diagnosing pipeline bottlenecks.


#### Metadata

- **Semantic Type:** :duration
- **Unit:** ns
- **Precision:** 0


### frame (#4)

Frame metadata for correlating detections with the source thermal video frame. Contains PTS (presentation timestamp), capture time, generation counter, and frame dimensions (width/height). Used to match detection results back to the correct frame in the video pipeline.


### config (#5)

Inference configuration that was active when this detection batch was produced. Contains the confidence threshold and NMS IoU threshold used by the detector, allowing consumers to understand the filtering parameters applied to the raw detections.


### capture_monotonic_us (#6)

Correlation timestamp from `CLOCK_MONOTONIC` in microseconds. Follows the same pattern as `CvMeta.capture_monotonic_us`, enabling temporal correlation between detection results and other sensor data (camera metadata, rotary encoder readings) across the pipeline.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** μs
- **Precision:** 0



