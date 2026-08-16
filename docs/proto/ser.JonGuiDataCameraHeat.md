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

Whether the heat camera is started.


### meteo (#15)

Local environmental sensor data from the thermal camera, providing temperature, humidity, and pressure readings for system diagnostics and thermal management.


### capture_monotonic_us (#16)

CLOCK_MONOTONIC timestamp in microseconds, stamped when state is pushed to SHM in the sync timer. Approximates when the data was last captured.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** μs


### delivered_fps (#17)

Rate at which the heat channel's CUDA IPC producer hands frames on, measured by eutropia by differencing successive `generation` counter values against their capture timestamps. Absent until two samples exist to difference, so a freshly attached reader publishes nothing rather than a fabricated zero. Zero when present is a measurement — nothing is arriving — and carrying presence is what keeps that distinct from "not yet known".

This is NOT the thermal interface's own rate. A 2:1 ingress drop runs ahead of the GPU upload, so the CUDA IPC tap sees about half what the interface delivers, and this field reports the tap.

The interface delivers at a steady rate that is largely insensitive to whether the core produced a new image, so on this channel a healthy `delivered_fps` is weak evidence about the picture. Pair it with `content_fps`.


#### Metadata

- **Semantic Type:** :raw
- **Unit:** fps


### content_fps (#18)

Rate at which frame CONTENT actually changes, measured by eutropia from the producer's content-novelty counter over the same interval as `delivered_fps`. This is the field to read when the question is whether the thermal core is really producing pictures.

**DIVERGENCE IS THE SIGNAL, NOT THE BASELINE — and on this channel that is easy to get backwards.** A healthy IDLE heat channel reads the two rates EQUAL, because the 2:1 ingress drop ahead of the GPU upload cancels the thermal core's idle re-serve almost exactly. Measured on a bench target over a 20.34 s window: 508 deliveries against 508 content transitions, both near 25 Hz, dup-factor 1.000. **Do not read a 1:1 heat channel as a broken counter.**

Content falls below delivery under SCAN load rather than at rest. A rotary-scan clip decoded 61% of consecutive frames bit-identical, implying content refresh near 13 Hz against 25 fps of delivery. That figure is derived from clip analysis rather than measured on the current pipeline, so treat the healthy content band as roughly 13-25 Hz and state-dependent. This is also why no fixed content threshold works here: the two counters are meant to be compared against each other, never against a constant.

The consequence for any consumer: whenever content does lag delivery, a per-delivery loop reprocesses the same image, and a fixed-stride sampler that straddles a duplicate pair sees an exactly identical frame — indistinguishable from a static scene, and actively wrong during motion. Gate such work on content advancing, never on delivery.

The pair's real value is a failure nothing else reports. `content_fps` at zero while `delivered_fps` holds is a content freeze: frames keep arriving and every one is the same picture. BOTH at zero is a delivery stop — and there the shared control block stays CRC-clean, untorn and parseable, the producer process stays alive, and systemd stays green, so these two counters are the only surface that shows it.


#### Metadata

- **Semantic Type:** :raw
- **Unit:** fps



