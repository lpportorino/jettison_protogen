---
id: ser.JonGuiDataCameraHeat
proto: jon_shared_data_camera_heat.proto
package: ser
type: message
---

# JonGuiDataCameraHeat

**Source:** `jon_shared_data_camera_heat.proto`

## Description

Represents the complete operational and configuration state of the thermal/infrared camera system, including optical parameters (zoom position, field-of-view, focus mode), image processing settings (AGC mode, filter selection, CLAHE enhancement, DDE dynamics enhancement), and operational status.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | zoom_pos | double | >= 0, <= 1 |
| 2 | agc_mode | [[proto/ser.JonGuiDataVideoChannelHeatAGCModes]] | defined enum value only, not in: 0 |
| 3 | filter | [[proto/ser.JonGuiDataVideoChannelHeatFilters]] | defined enum value only, not in: 0 |
| 4 | auto_focus | bool | - |
| 5 | zoom_table_pos | int32 | >= 0 |
| 6 | zoom_table_pos_max | int32 | >= 0 |
| 7 | dde_level | int32 | >= 0, <= 512 |
| 8 | dde_enabled | bool | - |
| 9 | fx_mode | [[proto/ser.JonGuiDataFxModeHeat]] | defined enum value only |
| 10 | digital_zoom_level | double | >= 1 |
| 11 | clahe_level | double | >= 0, <= 1 |
| 12 | horizontal_fov_degrees | double | >= 0, < 360 |
| 13 | vertical_fov_degrees | double | >= 0, < 360 |
| 14 | is_started | bool | - |
| 15 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |
| 16 | capture_monotonic_us | uint64 | - |
| 17 | delivered_fps | double | >= 0 |
| 18 | content_fps | double | >= 0 |


## Oneofs


### _delivered_fps

Fields: #17


### _content_fps

Fields: #18




## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Feedback:** :poll-confirm


### Purpose

Thermal camera status, settings, and operational data



### Related Commands

- [[proto/cmd.HeatCamera.Start]]
- [[proto/cmd.HeatCamera.Stop]]
- [[proto/cmd.HeatCamera.SetAGC]]
- [[proto/cmd.HeatCamera.SetFilters]]
- [[proto/cmd.HeatCamera.SetDDELevel]]
- [[proto/cmd.HeatCamera.SetDigitalZoomLevel]]



### Implementation Notes

Provides real-time thermal camera state including AGC mode, filter, zoom levels, and DDE settings



## Field Notes


### zoom_pos (#1)

Normalized value (0.0 to 1.0)


### agc_mode (#2)

See related enum for valid values


### filter (#3)

See related enum for valid values


### auto_focus (#4)

Auto-focus enabled state


### zoom_table_pos (#5)

Current zoom table position


### zoom_table_pos_max (#6)

Maximum zoom table position


### dde_level (#7)

DDE (Dynamic Detail Enhancement) level


### fx_mode (#9)

See related enum for valid values


### digital_zoom_level (#10)

Digital zoom multiplier


### clahe_level (#11)

Normalized value (0.0 to 1.0)


### horizontal_fov_degrees (#12)

Horizontal field of view in degrees


### vertical_fov_degrees (#13)

Vertical field of view in degrees


### is_started (#14)

GPS receiver started state


### meteo (#15)

Local environmental sensor data from the thermal camera, providing temperature, humidity, and pressure readings for system diagnostics and thermal management.


### capture_monotonic_us (#16)

CLOCK_MONOTONIC timestamp in microseconds, stamped when state is pushed to SHM in the sync timer. Approximates when the data was last captured.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** us


### delivered_fps (#17)

Rate at which the heat channel's CUDA IPC producer hands frames on, measured by eutropia by differencing successive `generation` counter values against their capture timestamps. Absent until two samples exist to difference, so a freshly attached reader publishes nothing rather than a fabricated zero. Zero when present is a measurement — nothing is arriving — and carrying presence is what keeps that distinct from "not yet known".

The thermal interface delivers at a steady rate that is largely insensitive to whether the core produced a new image, so on this channel a healthy `delivered_fps` is weak evidence about the picture. Pair it with `content_fps`.


#### Metadata

- **Semantic Type:** :raw
- **Unit:** fps


### content_fps (#18)

Rate at which frame CONTENT actually changes, measured by eutropia from the producer's content-novelty counter over the same interval as `delivered_fps`. This is the field to read when the question is whether the thermal core is really producing pictures.

**On this channel the two rates differ under healthy operation, and that is expected rather than a fault.** The thermal core re-serves each image several times over its steady delivery rate — measured between two and four times, and which factor applies is state-dependent — so `delivered_fps` can sit at its nominal value while `content_fps` is a half or a quarter of it. A single rate could not carry both facts, which is why these are two fields and not one.

The consequence for any consumer: a per-delivery loop on heat processes the same image two to four times over, and a fixed-stride sampler that happens to straddle a duplicate pair sees an exactly identical frame — indistinguishable from a static scene, and actively wrong during motion. Gate such work on content advancing, never on delivery.

A `content_fps` that falls to zero while `delivered_fps` holds is the invisible-freeze case: the interface is still handing on frames, but every one of them is the same picture.


#### Metadata

- **Semantic Type:** :raw
- **Unit:** fps



