---
id: cmd.Root
proto: jon_shared_cmd.proto
package: cmd
type: message
---

# Root

**Source:** `jon_shared_cmd.proto`

## Description

Top-level command message that routes client commands to various subsystems (day camera, thermal camera, GPS, compass, LRF, rotary platform, OSD, system, CV, glass heater, LIRA, power) with protocol versioning, session tracking, timestamps, and validation support.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | protocol_version | uint32 | > 0, <= 2147483647 |
| 2 | session_id | uint32 | - |
| 3 | important | bool | - |
| 4 | from_cv_subsystem | bool | - |
| 5 | client_type | [[proto/ser.JonGuiDataClientType]] | defined enum value only, not in: 0 |
| 10 | client_app | [[proto/ser.JonGuiDataClientApp]] | defined enum value only, not in: 0 |
| 6 | frame_time_day | uint64 | - |
| 7 | frame_time_heat | uint64 | - |
| 8 | state_time | uint64 | - |
| 9 | client_time_ms | uint64 | - |
| 11 | opaque_payloads | repeated [[proto/ser.JonOpaquePayload]] | - |
| 20 | day_camera | [[proto/cmd.DayCamera.Root]] | - |
| 21 | heat_camera | [[proto/cmd.HeatCamera.Root]] | - |
| 22 | gps | [[proto/cmd.Gps.Root]] | - |
| 23 | compass | [[proto/cmd.Compass.Root]] | - |
| 24 | lrf | [[proto/cmd.Lrf.Root]] | - |
| 25 | lrf_calib | [[proto/cmd.Lrf_calib.Root]] | - |
| 26 | rotary | [[proto/cmd.RotaryPlatform.Root]] | - |
| 27 | osd | [[proto/cmd.OSD.Root]] | - |
| 28 | ping | [[proto/cmd.Ping]] | - |
| 29 | noop | [[proto/cmd.Noop]] | - |
| 30 | frozen | [[proto/cmd.Frozen]] | - |
| 31 | system | [[proto/cmd.System.Root]] | - |
| 32 | cv | [[proto/cmd.CV.Root]] | - |
| 33 | day_cam_glass_heater | [[proto/cmd.DayCamGlassHeater.Root]] | - |
| 34 | lira | [[proto/cmd.Lira.Root]] | - |
| 35 | power | [[proto/cmd.Power.Root]] | - |
| 36 | pmu | [[proto/cmd.PMU.Root]] | - |


## Oneofs


### payload (required)

Fields: #20, #21, #22, #23, #24, #25, #26, #27, #28, #29, #30, #31, #32, #33, #34, #35, #36




## Interaction

- **Category:** :lifecycle


### Purpose

Root container for all command messages with protocol metadata





### Implementation Notes

This is the top-level message wrapper. Contains protocol version, session ID, timestamps, client type, and oneOf payload for specific subsystem commands. Not directly invoked by UI.



## Field Notes


### protocol_version (#1)


#### Metadata

- **Semantic Type:** :raw


### session_id (#2)


#### Metadata

- **Semantic Type:** :raw


### frame_time_day (#6)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### frame_time_heat (#7)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### state_time (#8)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### client_time_ms (#9)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** milliseconds



