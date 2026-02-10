---
id: ser.JonGUIState
proto: jon_shared_data.proto
package: ser
type: message
---

# JonGUIState

**Source:** `jon_shared_data.proto`

## Description

Root protocol buffer message that aggregates telemetry and state from multiple subsystems including system status, meteorological data, laser rangefinder, time, GPS, compass with calibration, rotary encoder, dual thermal and optical cameras, recording metadata, spatiotemporal data, power management, PMU, and heater. Synchronized using monotonic timestamps for both day and thermal imaging pipelines, published periodically to the frontend.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | protocol_version | uint32 | > 0, <= 2147483647 |
| 2 | system_monotonic_time_us | uint64 | >= 0 |
| 3 | state_source | [[proto/ser.JonGuiDataStateSource]] | defined enum value only, not in: 0 |
| 4 | frame_pts_day_ns | uint64 | >= 0 |
| 5 | frame_pts_heat_ns | uint64 | >= 0 |
| 6 | frame_monotonic_day_us | uint64 | >= 0 |
| 7 | frame_monotonic_heat_us | uint64 | >= 0 |
| 8 | opaque_payloads | repeated [[proto/ser.JonOpaquePayload]] | - |
| 13 | system | [[proto/ser.JonGuiDataSystem]] | required |
| 14 | meteo_internal | [[proto/ser.JonGuiDataMeteo]] | required |
| 15 | lrf | [[proto/ser.JonGuiDataLrf]] | required |
| 16 | time | [[proto/ser.JonGuiDataTime]] | required |
| 17 | gps | [[proto/ser.JonGuiDataGps]] | required |
| 18 | compass | [[proto/ser.JonGuiDataCompass]] | required |
| 19 | rotary | [[proto/ser.JonGuiDataRotary]] | required |
| 20 | camera_day | [[proto/ser.JonGuiDataCameraDay]] | required |
| 21 | camera_heat | [[proto/ser.JonGuiDataCameraHeat]] | required |
| 22 | compass_calibration | [[proto/ser.JonGuiDataCompassCalibration]] | required |
| 23 | rec_osd | [[proto/ser.JonGuiDataRecOsd]] | required |
| 25 | actual_space_time | [[proto/ser.JonGuiDataActualSpaceTime]] | required |
| 26 | power | [[proto/ser.JonGuiDataPower]] | required |
| 27 | cv | [[proto/ser.JonGuiDataCV]] | - |
| 28 | pmu | [[proto/ser.JonGuiDataPMU]] | required |
| 29 | heater | [[proto/ser.JonGuiDataHeater]] | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Root state message containing all device state data





### Implementation Notes

Main state container - published periodically to frontend. Contains all subsystem state messages.



## Field Notes


### protocol_version (#1)

Protocol version number


### system_monotonic_time_us (#2)

System monotonic time in microseconds


### state_source (#3)

See related enum for valid values


### frame_pts_day_ns (#4)

Day camera frame PTS in nanoseconds


### frame_pts_heat_ns (#5)

Heat camera frame PTS in nanoseconds


### frame_monotonic_day_us (#6)

Day camera frame monotonic time in microseconds


### frame_monotonic_heat_us (#7)

Heat camera frame monotonic time in microseconds


### opaque_payloads (#8)

See [[proto/ser.JonOpaquePayload]]


### system (#13)

Required — see [[proto/ser.JonGuiDataSystem]]


### meteo_internal (#14)

Required — see [[proto/ser.JonGuiDataMeteo]]


### lrf (#15)

Required — see [[proto/ser.JonGuiDataLrf]]


### time (#16)

Required — see [[proto/ser.JonGuiDataTime]]


### gps (#17)

Required — see [[proto/ser.JonGuiDataGps]]


### compass (#18)

Required — see [[proto/ser.JonGuiDataCompass]]


### rotary (#19)

Required — see [[proto/ser.JonGuiDataRotary]]


### camera_day (#20)

Required — see [[proto/ser.JonGuiDataCameraDay]]


### camera_heat (#21)

Required — see [[proto/ser.JonGuiDataCameraHeat]]


### compass_calibration (#22)

Required — see [[proto/ser.JonGuiDataCompassCalibration]]


### rec_osd (#23)

Required — see [[proto/ser.JonGuiDataRecOsd]]


### actual_space_time (#25)

Required — see [[proto/ser.JonGuiDataActualSpaceTime]]


### power (#26)

Power consumption in watts


### cv (#27)

See [[proto/ser.JonGuiDataCV]]


### pmu (#28)

Required — see [[proto/ser.JonGuiDataPMU]]


### heater (#29)

See [[proto/ser.JonGuiDataHeater]]



