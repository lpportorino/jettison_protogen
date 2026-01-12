---
id: cmd.Root
proto: jon_shared_cmd.proto
package: cmd
type: message
---

# Root

**Source:** `jon_shared_cmd.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | protocol_version | uint32 | > 0, <= 2147483647 |
| 2 | session_id | uint32 | - |
| 3 | important | bool | - |
| 4 | from_cv_subsystem | bool | - |
| 5 | client_type | [[ser.JonGuiDataClientType]] | defined enum value only, not in: 0 |
| 10 | client_app | [[ser.JonGuiDataClientApp]] | defined enum value only, not in: 0 |
| 6 | frame_time_day | uint64 | - |
| 7 | frame_time_heat | uint64 | - |
| 8 | state_time | uint64 | - |
| 9 | client_time_ms | uint64 | - |
| 20 | day_camera | [[cmd.DayCamera.Root]] | - |
| 21 | heat_camera | [[cmd.HeatCamera.Root]] | - |
| 22 | gps | [[cmd.Gps.Root]] | - |
| 23 | compass | [[cmd.Compass.Root]] | - |
| 24 | lrf | [[cmd.Lrf.Root]] | - |
| 25 | lrf_calib | [[cmd.Lrf_calib.Root]] | - |
| 26 | rotary | [[cmd.RotaryPlatform.Root]] | - |
| 27 | osd | [[cmd.OSD.Root]] | - |
| 28 | ping | [[cmd.Ping]] | - |
| 29 | noop | [[cmd.Noop]] | - |
| 30 | frozen | [[cmd.Frozen]] | - |
| 31 | system | [[cmd.System.Root]] | - |
| 32 | cv | [[cmd.CV.Root]] | - |
| 33 | day_cam_glass_heater | [[cmd.DayCamGlassHeater.Root]] | - |
| 34 | lira | [[cmd.Lira.Root]] | - |
| 35 | power | [[cmd.Power.Root]] | - |


## Oneofs


### payload (required)

Fields: #20, #21, #22, #23, #24, #25, #26, #27, #28, #29, #30, #31, #32, #33, #34, #35




