---
id: ser.JonGuiDataRotaryDirection
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataRotaryDirection

**Source:** `jon_shared_data_types.proto`

## Description

Specifies the rotation direction of a rotary device, supporting clockwise and counter-clockwise movements. Used in gimbal and pan-tilt motor commands and telemetry to indicate the direction of rotation.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_ROTARY_DIRECTION_UNSPECIFIED | The proto3 zero default, not a direction. Every field carrying this enum is constrained `not_in: [0]`, so it is rejected at the boundary and appears only as the unset default of a message that was never populated. |
| 1 | JON_GUI_DATA_ROTARY_DIRECTION_CLOCKWISE | Travel in the sense in which the platform's own `azimuth` scale increases — that scale is degrees with 0 = North running clockwise, so a clockwise azimuth move drives `azimuth` upward and wraps 359° back to 0°. The direction is a SEPARATE field because the `speed` it accompanies is unsigned (0.0 to 1.0) and carries no sense of its own; on an absolute move such as `RotateAzimuthTo` it additionally selects WHICH of the two arcs to the same bearing the platform takes, which the target angle alone cannot express. |
| 2 | JON_GUI_DATA_ROTARY_DIRECTION_COUNTER_CLOCKWISE | The opposite sense: `azimuth` decreases and wraps 0° back to 359°, and an absolute move takes the other arc to the same bearing. Caveat for the elevation commands that also carry this field (`RotateElevation`, `RotateElevationRelative`, `RotateElevationRelativeSet`): nothing in this repository states how clockwise maps onto the signed -90° to +90° elevation axis, so do not assume it means "up". |

