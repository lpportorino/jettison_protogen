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
| 3 | state_source | [[ser.JonGuiDataStateSource]] | defined enum value only, not in: 0 |
| 4 | frame_pts_day_ns | uint64 | >= 0 |
| 5 | frame_pts_heat_ns | uint64 | >= 0 |
| 6 | frame_monotonic_day_us | uint64 | >= 0 |
| 7 | frame_monotonic_heat_us | uint64 | >= 0 |
| 13 | system | [[ser.JonGuiDataSystem]] | required |
| 14 | meteo_internal | [[ser.JonGuiDataMeteo]] | required |
| 15 | lrf | [[ser.JonGuiDataLrf]] | required |
| 16 | time | [[ser.JonGuiDataTime]] | required |
| 17 | gps | [[ser.JonGuiDataGps]] | required |
| 18 | compass | [[ser.JonGuiDataCompass]] | required |
| 19 | rotary | [[ser.JonGuiDataRotary]] | required |
| 20 | camera_day | [[ser.JonGuiDataCameraDay]] | required |
| 21 | camera_heat | [[ser.JonGuiDataCameraHeat]] | required |
| 22 | compass_calibration | [[ser.JonGuiDataCompassCalibration]] | required |
| 23 | rec_osd | [[ser.JonGuiDataRecOsd]] | required |
| 24 | day_cam_glass_heater | [[ser.JonGuiDataDayCamGlassHeater]] | required |
| 25 | actual_space_time | [[ser.JonGuiDataActualSpaceTime]] | required |
| 26 | power | [[ser.JonGuiDataPower]] | required |



