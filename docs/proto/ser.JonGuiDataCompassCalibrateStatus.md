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
| 0 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_UNSPECIFIED | Protobuf default zero, meaning the field was never set. `ser.JonGuiDataCompassCalibration.status` carries `not_in: [0]`, so a calibration state leaving the status unset is rejected at the validation boundary; it marks an unset field, never a device condition. |
| 1 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_NOT_CALIBRATING | Idle: no calibration procedure is running. This is the resting status outside a `cmd.Compass.CalibrateStartShort` or `cmd.Compass.CalibrateStartLong` sequence. |
| 2 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_SHORT | A short (4-point) calibration started by `cmd.Compass.CalibrateStartShort` is in progress: the device is stepped through 4 cardinal points, which is faster and less precise than the long procedure. |
| 3 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_LONG | A long (12-point) calibration started by `cmd.Compass.CalibrateStartLong` is in progress: a multi-stage sequence of orientations that compensates the local magnetic field's hard-iron and soft-iron distortion. `stage` advances toward `final_stage` as the operator brings the device to each `target_azimuth` / `target_elevation` / `target_bank`. |
| 4 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_FINISHED | The procedure completed successfully. `figure_of_merit_raw` then carries the device's post-compensation quality score, in a raw CAN-UART encoding that field's own comment warns must be decoded empirically rather than read as the vendor's documented degrees value. |
| 5 | JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_ERROR | The procedure terminated in failure rather than reaching `FINISHED`. |

