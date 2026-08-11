---
id: ser.CvChannelMeta
proto: opaque/cv_meta.proto
package: ser
type: message
---

# CvChannelMeta

**Source:** `opaque/cv_meta.proto`

## Description

Per-channel CUDA IPC metadata carrying frame timing, a multi-level sharpness pyramid, and sensor gain. Populated from `/jon_cuda_ipc_day` and `/jon_cuda_ipc_heat` shared memory segments by the cv-gateway native reader (bezoar). Each video channel (day visible-light camera and heat thermal camera) gets its own `CvChannelMeta` instance embedded in [[ser.CvMeta]]. The sharpness pyramid is computed on-GPU by the Sharpy libraries (Variance of Laplacian for day, Morphological Gradient for heat) and is used for autofocus algorithms. The 85-float pyramid (1 + 4 + 16 + 64) enables coarse-to-fine focus search across the frame.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | pts_ns | uint64 | >= 0 |
| 2 | capture_time_ns | uint64 | >= 0 |
| 3 | generation | uint32 | - |
| 4 | sharpness_level0 | float | >= 0, <= 1 |
| 5 | sharpness_level1 | repeated float | min-items: 4, max-items: 4 |
| 6 | sharpness_level2 | repeated float | min-items: 16, max-items: 16 |
| 7 | sharpness_level3 | repeated float | min-items: 160, max-items: 160 |
| 8 | sharpness_compute_ns | uint64 | >= 0 |
| 9 | sharpness_total_ns | uint64 | >= 0 |
| 10 | sharpness_valid | bool | - |
| 11 | sensor_gain | int32 | - |
| 12 | gain_valid | bool | - |
| 13 | sensor_exposure | int32 | - |
| 14 | exposure_valid | bool | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget








## Field Notes


### pts_ns (#1)

GStreamer presentation timestamp of the video frame, in nanoseconds. Set by the CUDA IPC producer during the seqlock-protected frame push. Value is 0 if the PTS was not available from the GStreamer pipeline.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns


### capture_time_ns (#2)

CLOCK_MONOTONIC timestamp captured at the GStreamer probe callback when the frame entered the pipeline. Used for end-to-end latency measurement and CV correlation across channels.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns


### generation (#3)

Monotonically increasing counter from the CUDA IPC seqlock protocol. Incremented by the producer on each frame push; used by consumers to detect new data and by futex-based blocking reads. Currently set to 0 in the proto encoding (the SHM-level generation is used internally but not propagated to the protobuf message).


#### Metadata

- **Semantic Type:** :count


### sharpness_level0 (#4)

Global sharpness score for the entire frame, normalized to 0.0-1.0. Computed by the Sharpy CUDA library (Variance of Laplacian for day camera, Morphological Gradient for heat camera). Represents the overall focus quality and is used for quick accept/reject decisions in autofocus. The StateEnricherModule extracts this value to populate `cv.sharpness_day` and `cv.sharpness_heat` in the GUI state.


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3


### sharpness_level1 (#5)

2x2 quadrant sharpness map (4 floats, each 0.0-1.0). Layout is row-major: [Top-Left, Top-Right, Bottom-Left, Bottom-Right]. Enables coarse localization of the sharpest region in the frame for region-based autofocus. Each quadrant covers half the frame width and half the frame height (e.g., 960x540 pixels at 1080p).


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3


### sharpness_level2 (#6)

4x4 regional sharpness grid (16 floats, each 0.0-1.0, row-major). Each cell covers 1/16th of the frame (e.g., 480x270 pixels at 1080p for the day camera, 225x180 pixels for heat). Used for precise focus region selection and identifying specific areas of interest within a quadrant.


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3


### sharpness_level3 (#7)

8x8 sub-region sharpness grid (64 floats, each 0.0-1.0, row-major). Finest granularity of the sharpness pyramid. Each cell covers 1/64th of the frame (e.g., 240x135 pixels at 1080p for the day camera). Used for fine-grained focus analysis and precise object-level sharpness measurement.


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3


### sharpness_compute_ns (#8)

Time spent in the CUDA sharpness kernel plus reduction, in nanoseconds. Typical values on AGX Orin: ~0.25ms for day (1920x1080), ~0.10ms for heat (900x720). Used for performance profiling of the GPU computation.


#### Metadata

- **Semantic Type:** :duration
- **Unit:** ns


### sharpness_total_ns (#9)

Total wall-clock time for sharpness computation including the device-to-host memcpy of results, in nanoseconds. Typical values on AGX Orin: ~0.30ms for day, ~0.15ms for heat. The difference from `sharpness_compute_ns` represents the memcpy overhead.


#### Metadata

- **Semantic Type:** :duration
- **Unit:** ns


### sharpness_valid (#10)

Indicates whether the sharpness pyramid fields contain valid data for this frame. When false, all sharpness values should be ignored. Set to false when the Sharpy library is not loaded, has not yet produced results, or the staged sharpness data was consumed without replacement.


#### Metadata

- **Semantic Type:** :toggle-state


### sensor_gain (#11)

Raw V4L2 sensor gain value from the IMX290 day camera, polled by a background thread at 100ms intervals via `/dev/video0` (CID 0x009a2009). Range is 1-720 (step 3). The StateEnricherModule normalizes this to [0.0, 1.0] by dividing by 720. Only meaningful for the day channel; the heat channel always reports 0.


#### Metadata

- **Semantic Type:** :raw


### gain_valid (#12)

Indicates whether `sensor_gain` contains a valid reading. Always true for the day channel when the gain reader thread is running; always false for the heat channel (thermal cameras do not expose V4L2 gain). When false, `sensor_gain` should be ignored.


#### Metadata

- **Semantic Type:** :toggle-state



