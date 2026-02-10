---
id: ser.CvMeta
proto: opaque/cv_meta.proto
package: ser
type: message
---

# CvMeta

**Source:** `opaque/cv_meta.proto`

## Description

Aggregated CV metadata payload combining all shared-memory sources at 60fps. Populated by the cv-gateway native library (`libbezoar_cv_meta.so`), which runs 5 background threads that block on futex to read from SHM segments (`/jon_shm_rotary`, `/jon_shm_cam_day`, `/jon_shm_cam_heat`, `/jon_cuda_ipc_day`, `/jon_cuda_ipc_heat`). The aggregated proto is encoded via nanopb on the critical read path (~10us latency) and published to the DataBus by CvMetaModule. The StateEnricherModule then injects it into `JonGUIState.opaque_payloads` (UUID `019c3e33-d52d-7552-b36b-6fdcaa5d59b8`) and patches top-level state fields with sharpness scores and sensor gain from the embedded channel metadata.

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



### Related State

- [[proto/ser.JonGuiDataCameraDay]]
- [[proto/ser.JonGuiDataCameraHeat]]
- [[proto/ser.JonGuiDataRotary]]
- [[proto/ser.CvChannelMeta]]






## Field Notes


### capture_monotonic_us (#1)

Correlation timestamp taken from `CLOCK_MONOTONIC` at the moment the native library assembles and encodes the aggregated proto. Used to correlate all embedded source data to a single point in time, and logged in the `cv_meta_audit` table for diagnostics.
- **Semantic Type:** timestamp
- **Unit:** us


### updated_sources (#2)

Freshness bitmask indicating which of the 5 SHM sources provided valid data in this read cycle. Bit 0 = rotary (`/jon_shm_rotary`), bit 1 = cam_day (`/jon_shm_cam_day`), bit 2 = cam_heat (`/jon_shm_cam_heat`), bit 3 = cuda_day (`/jon_cuda_ipc_day`), bit 4 = cuda_heat (`/jon_cuda_ipc_heat`). A value of 31 (0x1F) means all 5 sources have valid data. Consumers can check individual bits to determine which embedded sub-messages contain fresh data.
- **Semantic Type:** raw


### camera_day (#3)

Full copy of the day camera settings read from `/jon_shm_cam_day`. Embedded as-is from the seqlock-protected cache; validated by its own proto definition. The StateEnricherModule uses `channel_day.sensor_gain` (from the CUDA IPC channel, not this field) to patch `camera_day.sensor_gain` in the top-level state.


### camera_heat (#4)

Full copy of the thermal camera settings read from `/jon_shm_cam_heat`. Embedded as-is from the seqlock-protected cache; validated by its own proto definition.


### rotary (#5)

Full copy of the rotary turret state read from `/jon_shm_rotary`. Embedded as-is from the seqlock-protected cache; validated by its own proto definition.


### channel_day (#6)

CUDA IPC metadata for the day video channel, read from `/jon_cuda_ipc_day`. Contains frame timing (PTS, capture time), a multi-resolution sharpness pyramid (levels 0-3: global, 2x2, 4x4, 8x8), sharpness computation timing, and sensor gain from the IMX290 V4L2 driver. The StateEnricherModule extracts `sharpness_level0` to patch `cv.sharpness_day` and normalizes `sensor_gain` to [0.0, 1.0] (max 720) to patch `camera_day.sensor_gain`.


### channel_heat (#7)

CUDA IPC metadata for the thermal video channel, read from `/jon_cuda_ipc_heat`. Contains the same structure as `channel_day` but for the thermal sensor. Sensor gain is not valid for the heat channel (`gain_valid` is always false). The StateEnricherModule extracts `sharpness_level0` to patch `cv.sharpness_heat`.



