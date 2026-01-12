---
id: ser.JonGuiDataCompassCalibration
proto: jon_shared_data_compass_calibration.proto
package: ser
type: message
---

# JonGuiDataCompassCalibration

**Source:** `jon_shared_data_compass_calibration.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | stage | uint32 | >= 0 |
| 2 | final_stage | uint32 | > 0 |
| 3 | target_azimuth | double | >= 0, < 360 |
| 4 | target_elevation | double | >= -90, <= 90 |
| 5 | target_bank | double | >= -180, < 180 |
| 6 | status | [[proto/ser.JonGuiDataCompassCalibrateStatus]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :status


### Purpose

Compass calibration state and progress



### Related Commands

- [[proto/proto/cmd.Compass.CalibrateStartLong]]
- [[proto/proto/cmd.Compass.CalibrateStartShort]]
- [[proto/proto/cmd.Compass.CalibrateNext]]
- [[proto/proto/cmd.Compass.CalibrateCencel]]



### Implementation Notes

Contains calibration step, progress, and result data



