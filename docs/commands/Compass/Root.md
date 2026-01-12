---
id: cmd.Compass.Root
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# Root

**Source:** `jon_shared_cmd_compass.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start | [[cmd.Compass.Start]] | - |
| 2 | stop | [[cmd.Compass.Stop]] | - |
| 3 | set_magnetic_declination | [[cmd.Compass.SetMagneticDeclination]] | - |
| 4 | set_offset_angle_azimuth | [[cmd.Compass.SetOffsetAngleAzimuth]] | - |
| 5 | set_offset_angle_elevation | [[cmd.Compass.SetOffsetAngleElevation]] | - |
| 6 | set_use_rotary_position | [[cmd.Compass.SetUseRotaryPosition]] | - |
| 7 | start_calibrate_long | [[cmd.Compass.CalibrateStartLong]] | - |
| 8 | start_calibrate_short | [[cmd.Compass.CalibrateStartShort]] | - |
| 9 | calibrate_next | [[cmd.Compass.CalibrateNext]] | - |
| 10 | calibrate_cencel | [[cmd.Compass.CalibrateCencel]] | - |
| 11 | get_meteo | [[cmd.Compass.GetMeteo]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8, #9, #10, #11





