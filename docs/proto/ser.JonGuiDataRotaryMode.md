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
| 0 | JON_GUI_DATA_ROTARY_MODE_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_ROTARY_MODE_INITIALIZATION | Platform initializing |
| 2 | JON_GUI_DATA_ROTARY_MODE_SPEED | Speed control mode |
| 3 | JON_GUI_DATA_ROTARY_MODE_POSITION | Position control mode |
| 4 | JON_GUI_DATA_ROTARY_MODE_STABILIZATION | Gyro-stabilized mode |
| 5 | JON_GUI_DATA_ROTARY_MODE_TARGETING | Target tracking mode |
| 6 | JON_GUI_DATA_ROTARY_MODE_VIDEO_TRACKER | Video tracker following mode |

