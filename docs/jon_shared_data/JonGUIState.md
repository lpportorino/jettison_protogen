# JonGUIState (ser.JonGUIState)

**Source:** `jon_shared_data.proto`

## Description

Complete system state snapshot.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| protocol_version | uint32 | 1 | - | > 0, <= 2147483647 |
| system_monotonic_time_us | uint64 | 2 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| state_source | JonGuiDataStateSource | 3 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| frame_pts_day_ns | uint64 | 4 | - | - |
| frame_pts_heat_ns | uint64 | 5 | - | - |
| frame_monotonic_day_us | uint64 | 6 | - | - |
| frame_monotonic_heat_us | uint64 | 7 | - | - |
| opaque_payloads | repeated JonOpaquePayload | 8 | Opaque payloads for subsystem-specific extensions | - |
| system | JonGuiDataSystem | 13 | - | - |
| meteo_internal | JonGuiDataMeteo | 14 | - | - |
| lrf | JonGuiDataLrf | 15 | - | - |
| time | JonGuiDataTime | 16 | - | - |
| gps | JonGuiDataGps | 17 | - | - |
| compass | JonGuiDataCompass | 18 | - | - |
| rotary | JonGuiDataRotary | 19 | - | - |
| camera_day | JonGuiDataCameraDay | 20 | - | - |
| camera_heat | JonGuiDataCameraHeat | 21 | - | - |
| compass_calibration | JonGuiDataCompassCalibration | 22 | - | - |
| rec_osd | JonGuiDataRecOsd | 23 | - | - |
| day_cam_glass_heater | JonGuiDataDayCamGlassHeater | 24 | - | - |
| actual_space_time | JonGuiDataActualSpaceTime | 25 | - | - |
| power | JonGuiDataPower | 26 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data.proto` for complete context
