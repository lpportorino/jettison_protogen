---
id: ser.JonGuiDatatLrfLaserPointerModes
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDatatLrfLaserPointerModes

**Source:** `jon_shared_data_types.proto`

## Description

Controls the laser rangefinder's target designator pointer, supporting three operational states: disabled (OFF), and two active modes (ON_1 and ON_2) for different targeting scenarios. The pointer_mode field in JonGuiDataLrf tracks the current state of the LRF laser designator output.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_LRF_LASER_POINTER_MODE_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_LRF_LASER_POINTER_MODE_OFF | Laser pointer off |
| 2 | JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_1 | Laser pointer mode 1 |
| 3 | JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_2 | Laser pointer mode 2 |

