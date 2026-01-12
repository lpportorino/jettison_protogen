---
id: ser.JonGUIState
proto: jon_shared_data.proto
package: ser
type: message
---

# JonGUIState

**Source:** `jon_shared_data.proto`

## Description

*No description yet.*

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
| 24 | day_cam_glass_heater | [[proto/ser.JonGuiDataDayCamGlassHeater]] | required |
| 25 | actual_space_time | [[proto/ser.JonGuiDataActualSpaceTime]] | required |
| 26 | power | [[proto/ser.JonGuiDataPower]] | required |



## Interaction

- **Category:** :status


### Purpose

Root state message containing all device state data





### Implementation Notes

Main state container - published periodically to frontend. Contains all subsystem state messages.



