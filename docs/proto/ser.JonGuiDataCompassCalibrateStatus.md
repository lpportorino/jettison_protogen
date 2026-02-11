---
id: ser.JonGuiDataCompassCalibrateStatus
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataCompassCalibrateStatus

**Source:** `jon_shared_data_types.proto`

## Description

Represents the current state of the compass calibration process with five distinct statuses: not calibrating (idle), calibrating short, calibrating long (multi-stage extended calibration), finished (successful completion), and error (calibration failure).

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_UNSPECIFIED | - |
| 1 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_NOT_CALIBRATING | - |
| 2 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_SHORT | - |
| 3 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_LONG | - |
| 4 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_FINISHED | - |
| 5 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_ERROR | - |

