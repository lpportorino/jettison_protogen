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
| 0 | JON_GUI_DATA_LRF_LASER_POINTER_MODE_UNSPECIFIED | The proto3 zero default. `JonGuiDataLrf.pointer_mode` constrains this enum `defined_only` with NO `not_in: [0]`, so zero is accepted on the wire — and it means the designator state has not been reported, which is NOT the same as the designator being off. Do not render it as OFF. |
| 1 | JON_GUI_DATA_LRF_LASER_POINTER_MODE_OFF | The target designator is not emitting. This is the reported state after `cmd.Lrf.TargetDesignatorOff`, which includes the automatic off sent when the gamepad pointer button is released, not only a deliberate UI action. |
| 2 | JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_1 | The designator is emitting in Mode A — the state commanded by `cmd.Lrf.TargetDesignatorOnModeA`. What physically differs between Mode A and Mode B (beam pattern, pulse coding, power) is not stated anywhere in this repository. |
| 3 | JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_2 | The designator is emitting in Mode B — the state commanded by `cmd.Lrf.TargetDesignatorOnModeB`, whose own page describes that command as activating "pointer mode 2", which is what fixes this value to Mode B. As with ON_1, the physical difference between the two on-modes is undocumented here. |

