---
id: ser.JonGuiDataCameraDay
proto: jon_shared_data_camera_day.proto
package: ser
type: message
---

# JonGuiDataCameraDay

**Source:** `jon_shared_data_camera_day.proto`

## Description

Captures the complete operational state of the day camera, including normalized control positions (focus, zoom, iris), automatic control modes (auto-focus, auto-iris, auto-gain), field of view angles, and image processing parameters like CLAHE level and FX mode presets.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | focus_pos | double | >= 0, <= 1 |
| 2 | zoom_pos | double | >= 0, <= 1 |
| 3 | iris_pos | double | >= 0, <= 1 |
| 4 | infrared_filter | bool | - |
| 5 | zoom_table_pos | int32 | >= 0 |
| 6 | zoom_table_pos_max | int32 | >= 0 |
| 7 | fx_mode | [[proto/ser.JonGuiDataFxModeDay]] | defined enum value only |
| 8 | auto_focus | bool | - |
| 9 | auto_iris | bool | - |
| 15 | auto_gain | bool | - |
| 10 | digital_zoom_level | double | >= 1 |
| 11 | clahe_level | double | >= 0, <= 1 |
| 12 | horizontal_fov_degrees | double | >= 0, < 360 |
| 13 | vertical_fov_degrees | double | >= 0, < 360 |
| 14 | is_started | bool | - |
| 16 | meteo | [[proto/ser.JonGuiDataMeteo]] | - |
| 17 | sensor_gain | double | >= 0, <= 1 |
| 18 | exposure | double | >= 0, <= 1 |
| 19 | capture_monotonic_us | uint64 | - |
| 20 | delivered_fps | double | >= 0 |
| 21 | content_fps | double | >= 0 |


## Oneofs


### _sensor_gain

Fields: #17


### _exposure

Fields: #18


### _delivered_fps

Fields: #20


### _content_fps

Fields: #21




## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Day camera state including zoom, focus, iris, and image processing settings



### Related Commands

- [[proto/cmd.DayCamera.SetIris]]
- [[proto/cmd.DayCamera.SetAutoIris]]
- [[proto/cmd.DayCamera.SetFxMode]]





## Field Notes


### focus_pos (#1)

Normalized value (0.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3


### zoom_pos (#2)

Normalized value (0.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 3


### iris_pos (#3)

Normalized value (0.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`


### infrared_filter (#4)

Thermal image color filter


#### Metadata

- **Semantic Type:** :raw


### zoom_table_pos (#5)

Current zoom table position


#### Metadata

- **Semantic Type:** :raw


### zoom_table_pos_max (#6)

Maximum zoom table position


#### Metadata

- **Semantic Type:** :raw


### fx_mode (#7)

See related enum for valid values


#### Metadata

- **Semantic Type:** :enum-label


### auto_focus (#8)

Auto-focus enabled state


#### Metadata

- **Semantic Type:** :raw


### auto_iris (#9)

Auto-iris enabled state


#### Metadata

- **Semantic Type:** :toggle-state


### auto_gain (#15)

Auto-gain enabled state


### digital_zoom_level (#10)

Digital zoom multiplier


#### Metadata

- **Semantic Type:** :normalized
- **Precision:** 2


### clahe_level (#11)

Normalized value (0.0 to 1.0)


#### Metadata

- **Semantic Type:** :normalized


### horizontal_fov_degrees (#12)

Horizontal field of view in degrees


### vertical_fov_degrees (#13)

Vertical field of view in degrees


### is_started (#14)

Whether the day camera is started.


### meteo (#16)

Local environmental sensor data from the day camera, providing temperature, humidity, and pressure readings for system diagnostics and thermal management.


### sensor_gain (#17)

Normalized value (0.0 to 1.0)


### exposure (#18)

Normalized value (0.0 to 1.0)


### capture_monotonic_us (#19)

CLOCK_MONOTONIC timestamp in microseconds, stamped when state is pushed to SHM in the sync timer. Approximates when the data was last captured.


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** us


### delivered_fps (#20)

Rate at which the day channel's CUDA IPC producer hands frames on, measured by eutropia by differencing successive `generation` counter values against their capture timestamps. Absent until two samples exist to difference, so a freshly attached reader publishes nothing rather than a fabricated zero. Zero when present is a measurement — nothing is arriving — and carrying presence is what keeps that distinct from "not yet known"; collapsing the two would make a starting reader indistinguishable from a dead pipeline.


#### Metadata

- **Semantic Type:** :raw
- **Unit:** fps


### content_fps (#21)

Rate at which frame CONTENT actually changes, measured by eutropia from the producer's content-novelty counter over the same interval as `delivered_fps`. This is the field to read when the question is whether the camera is really producing pictures.

Every other frame rate this system exposes — the health pools, jdiscover, jprobe, the encoder statistics — is sampled downstream of `videorate`, which runs `drop-only=false` and therefore FABRICATES duplicate frames to fill slots the source missed. Those surfaces report the encoder's rate, which is held at its nominal value by the duplicates themselves. Measured on the day channel: 858 fabricated frames against 300 genuine ones, so roughly two of every three frames leaving the pipeline carried no new image, while jprobe reported 30.4 fps and every health pool stayed green. `content_fps` is derived from a producer-side content comparison, so a duplicate cannot inflate it.

`delivered_fps` minus `content_fps` is therefore the duplication the pipeline is manufacturing. On this channel the two should track closely; a sustained gap means frames are being synthesized over a starving source.


#### Metadata

- **Semantic Type:** :raw
- **Unit:** fps



