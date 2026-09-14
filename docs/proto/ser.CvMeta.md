---
id: ser.CvMeta
proto: opaque/cv_meta.proto
package: ser
type: message
---

# CvMeta

**Source:** `opaque/cv_meta.proto`

## Description

Aggregated CV metadata payload at 60fps. It combines the rotary turret state and the day and thermal camera settings, handed over in-process by the state hub, with per-channel CUDA IPC metadata from `/jon_cuda_ipc_day` and `/jon_cuda_ipc_heat`. The payload reaches consumers inside `JonGUIState.opaque_payloads` (UUID `019c3e33-d52d-7552-b36b-6fdcaa5d59b8`), and the top-level state fields for sharpness scores and sensor gain are patched from the embedded channel metadata.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | capture_monotonic_us | uint64 | >= 0 |
| 2 | updated_sources | uint32 | >= 0, <= 31 |
| 3 | camera_day | [[proto/ser.JonGuiDataCameraDay]] | - |
| 4 | camera_heat | [[proto/ser.JonGuiDataCameraHeat]] | - |
| 5 | rotary | [[proto/ser.JonGuiDataRotary]] | - |
| 6 | channel_day | [[proto/ser.CvChannelMeta]] | - |
| 7 | channel_heat | [[proto/ser.CvChannelMeta]] | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget



### Related State

- [[proto/ser.JonGuiDataCameraDay]]
- [[proto/ser.JonGuiDataCameraHeat]]
- [[proto/ser.JonGuiDataRotary]]
- [[proto/ser.CvChannelMeta]]






## Field Notes


### capture_monotonic_us (#1)

Correlation timestamp taken from `CLOCK_MONOTONIC` at the moment the native library assembles and encodes the aggregated proto. Used to correlate all embedded source data to a single point in time, and logged in the `cv_meta_audit` table for diagnostics.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** μs


### updated_sources (#2)

Freshness bitmask indicating which of the 5 sources provided valid data in this cycle. Bit 0 = rotary, bit 1 = cam_day, bit 2 = cam_heat — the three the state hub hands over in-process — then bit 3 = cuda_day (`/jon_cuda_ipc_day`), bit 4 = cuda_heat (`/jon_cuda_ipc_heat`). A value of 31 (0x1F) means all 5 sources have valid data. Consumers can check individual bits to determine which embedded sub-messages contain fresh data.


#### Metadata

- **Semantic Type:** :raw


### camera_day (#3)

The day camera settings, handed over in-process by the state hub and embedded as-is; validated by its own proto definition. The top-level `camera_day.sensor_gain` is patched from `channel_day.sensor_gain` (the CUDA IPC channel), not from this field.


### camera_heat (#4)

The thermal camera settings, handed over in-process by the state hub and embedded as-is; validated by its own proto definition.


### rotary (#5)

The rotary turret state, handed over in-process by the state hub and embedded as-is; validated by its own proto definition.


### channel_day (#6)

CUDA IPC metadata for the day video channel, read from `/jon_cuda_ipc_day`. Contains frame timing (PTS, capture time), a multi-resolution sharpness pyramid (levels 0-3: global, 2x2, 4x4, 8x8), sharpness computation timing, and sensor gain from the IMX290 V4L2 driver. The StateEnricherModule extracts `sharpness_level0` to patch `cv.sharpness_day` and normalizes `sensor_gain` to [0.0, 1.0] (max 720) to patch `camera_day.sensor_gain`.


### channel_heat (#7)

CUDA IPC metadata for the thermal video channel, read from `/jon_cuda_ipc_heat`. Contains the same structure as `channel_day` but for the thermal sensor. Sensor gain is not valid for the heat channel (`gain_valid` is always false). The StateEnricherModule extracts `sharpness_level0` to patch `cv.sharpness_heat`.



