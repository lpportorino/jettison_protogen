---
id: cmd.Root
proto: jon_shared_cmd.proto
package: cmd
type: message
---

# Root

**Source:** `jon_shared_cmd.proto`

## Description

Top-level command message that routes client commands to various subsystems (day camera, thermal camera, GPS, compass, LRF, rotary platform, OSD, system, CV, LIRA, power, PMU, heater) with protocol versioning, session tracking, timestamps, and validation support.

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
| 34 | lira | [[proto/cmd.Lira.Root]] | - |
| 35 | power | [[proto/cmd.Power.Root]] | - |
| 36 | pmu | [[proto/cmd.PMU.Root]] | - |
| 37 | heater | [[proto/cmd.Heater.Root]] | - |


## Oneofs


### payload (required)

Fields: #20, #21, #22, #23, #24, #25, #26, #27, #28, #29, #30, #31, #32, #34, #35, #36, #37




## Interaction

- **Category:** :actuator
- **UI Pattern:** :command-router
- **Feedback:** :fire-and-forget


### Purpose

Root container for all command messages with protocol metadata





### Implementation Notes

This is the top-level message wrapper. Contains protocol version, session ID, timestamps, client type, and oneOf payload for specific subsystem commands. Not directly invoked by UI.



## Field Notes


### protocol_version (#1)

Protocol version number


#### Metadata

- **Semantic Type:** :raw


### session_id (#2)

Session identifier


#### Metadata

- **Semantic Type:** :raw


### client_type (#5)

See related enum for valid values


### client_app (#10)

See related enum for valid values


### frame_time_day (#6)

Frame timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### frame_time_heat (#7)

Frame timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### state_time (#8)

State snapshot timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** nanoseconds


### client_time_ms (#9)

Client-side timestamp in milliseconds


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** milliseconds


### opaque_payloads (#11)

See [[proto/ser.JonOpaquePayload]]


### day_camera (#20)

See [[proto/cmd.DayCamera.Root]]


### heat_camera (#21)

See [[proto/cmd.HeatCamera.Root]]


### gps (#22)

See [[proto/cmd.Gps.Root]]


### compass (#23)

See [[proto/cmd.Compass.Root]]


### lrf (#24)

See [[proto/cmd.Lrf.Root]]


### lrf_calib (#25)

See [[proto/cmd.Lrf_calib.Root]]


### rotary (#26)

See [[proto/cmd.RotaryPlatform.Root]]


### osd (#27)

See [[proto/cmd.OSD.Root]]


### ping (#28)

See [[proto/cmd.Ping]]


### noop (#29)

See [[proto/cmd.Noop]]


### frozen (#30)

See [[proto/cmd.Frozen]]


### system (#31)

See [[proto/cmd.System.Root]]


### cv (#32)

See [[proto/cmd.CV.Root]]


### lira (#34)

See [[proto/cmd.Lira.Root]]


### power (#35)

See [[proto/cmd.Power.Root]]


### pmu (#36)

See [[proto/cmd.PMU.Root]]


### heater (#37)

See [[proto/cmd.Heater.Root]]



