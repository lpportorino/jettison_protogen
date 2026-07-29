---
id: ser.JonGuiDataRotaryMode
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataRotaryMode

**Source:** `jon_shared_data_types.proto`

## Description

Represents the operational modes of a rotary gimbal platform: initialization (system setup), speed (direct velocity control), position (absolute pointing), stabilization (steady tracking), targeting (guided engagement), and video tracker (automated object tracking using computer vision).

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_ROTARY_MODE_UNSPECIFIED | The proto3 zero default, not a mode. Rejected on BOTH sides of the loop — `cmd.RotaryPlatform.SetMode.mode` and `JonGuiDataRotary.mode` are each constrained `not_in: [0]` — so it can neither be commanded nor validly reported by a running platform. |
| 1 | JON_GUI_DATA_ROTARY_MODE_INITIALIZATION | Bring-up, before the platform will accept operational commands. `cmd.RotaryPlatform.Start` PINGs the hardware to discover its address and, once the ACK returns, the system leaves initialization for operational readiness. Per-axis progress within this mode is the separate `JonGuiDataRotary.pan_init_status` / `tilt_init_status` counters (0 = not initialised through 14 = fully initialised), so this enum says WHETHER the platform is still initialising and those say how far along each axis is. |
| 2 | JON_GUI_DATA_ROTARY_MODE_SPEED | Rate control: motion is commanded as a speed plus a direction (`RotateAzimuth`, `RotateElevation`) and continues until halted, rather than being sent to an angle. The resulting rates are reported back as `azimuth_speed` / `elevation_speed`, normalised to -1.0 to 1.0 rather than in degrees per second, so the command and the telemetry are in the same unitless scale. |
| 3 | JON_GUI_DATA_ROTARY_MODE_POSITION | Absolute pointing: motion is commanded as an ANGLE (`SetAzimuthValue`, `RotateAzimuthTo`, `SetElevationValue`, `RotateElevationTo`) and the platform stops there. Speed becomes a parameter OF the move rather than the thing commanded — the inverse of SPEED mode. |
| 4 | JON_GUI_DATA_ROTARY_MODE_STABILIZATION | The platform holds its line of sight rather than following operator rate or angle commands. The schema supports such a hold by reporting the BASE attitude (`platform_azimuth`, `platform_elevation`, `platform_bank`) separately from the head angles (`azimuth`, `elevation`), which is the pair a stabilised hold is computed against. UNGROUNDED: no sensing mechanism is documented anywhere in this repository — one in-tree gloss calls it gyro-stabilised, but no IMU or gyro appears in any proto, doc or source here, so treat the mechanism as unknown. |
| 5 | JON_GUI_DATA_ROTARY_MODE_TARGETING | The platform is driven toward a designated point rather than by direct axis commands. The only targeting surface this schema exposes is GPS-based — `cmd.RotaryPlatform.RotateToGPS` against an origin established by `SetOriginGPS` — and the `is_parked` interlock names rotate-to-GPS as one of the motion classes it drops. UNGROUNDED: nothing here states how this mode's behaviour differs from issuing those same commands in POSITION mode. |
| 6 | JON_GUI_DATA_ROTARY_MODE_VIDEO_TRACKER | The platform is slaved to the CV video tracker, which drives the axes to keep a tracked object in view; tracking itself is initiated from the video plane by `cmd.CV.StartTrackNDC` at a normalised device coordinate, and `JonGuiDataRotary.is_parked` names the tracker as a distinct source of axis motion alongside the operator. UNGROUNDED: the slaving path between tracker output and axis command is not described in this repository. |

