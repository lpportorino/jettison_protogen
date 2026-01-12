# Root (cmd.Root)

**Source:** `jon_shared_cmd.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| protocol_version | uint32 | 1 | - | > 0, <= 2147483647 |
| session_id | uint32 | 2 | - | - |
| important | bool | 3 | - | - |
| from_cv_subsystem | bool | 4 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| client_type | ser.JonGuiDataClientType | 5 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| client_app | ser.JonGuiDataClientApp | 10 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| frame_time_day | uint64 | 6 | Frame timestamps (PTS) from video streams when command was issued | - |
| frame_time_heat | uint64 | 7 | - | - |
| state_time | uint64 | 8 | System monotonic time when user performed action | - |
| client_time_ms | uint64 | 9 | Client wall-clock time when command was issued | - |
| opaque_payloads | repeated ser.JonOpaquePayload | 11 | Opaque payloads for subsystem-specific extensions | - |
| day_camera | DayCamera.Root | 20 | - | - |
| heat_camera | HeatCamera.Root | 21 | - | - |
| gps | Gps.Root | 22 | - | - |
| compass | Compass.Root | 23 | - | - |
| lrf | Lrf.Root | 24 | - | - |
| lrf_calib | Lrf_calib.Root | 25 | - | - |
| rotary | RotaryPlatform.Root | 26 | - | - |
| osd | OSD.Root | 27 | - | - |
| ping | Ping | 28 | - | - |
| noop | Noop | 29 | - | - |
| frozen | Frozen | 30 | - | - |
| system | System.Root | 31 | - | - |
| cv | CV.Root | 32 | - | - |
| day_cam_glass_heater | DayCamGlassHeater.Root | 33 | - | - |
| lira | Lira.Root | 34 | - | - |
| power | Power.Root | 35 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd.proto` for complete context
