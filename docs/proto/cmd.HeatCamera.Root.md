---
id: cmd.HeatCamera.Root
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# Root

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Root message container for all thermal camera commands using a required oneof pattern with 38 command variants. Includes zoom, focus, AGC, filters, calibration, DDE, CLAHE, and region-of-interest operations. The frontend constructs individual commands and wraps them in this Root message for dispatch.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | zoom | [[proto/cmd.HeatCamera.Zoom]] | - |
| 2 | set_agc | [[proto/cmd.HeatCamera.SetAGC]] | - |
| 3 | set_filter | [[proto/cmd.HeatCamera.SetFilters]] | - |
| 4 | start | [[proto/cmd.HeatCamera.Start]] | - |
| 5 | stop | [[proto/cmd.HeatCamera.Stop]] | - |
| 6 | photo | [[proto/cmd.HeatCamera.Photo]] | - |
| 10 | zoom_in | [[proto/cmd.HeatCamera.ZoomIn]] | - |
| 11 | zoom_out | [[proto/cmd.HeatCamera.ZoomOut]] | - |
| 12 | zoom_stop | [[proto/cmd.HeatCamera.ZoomStop]] | - |
| 13 | focus_in | [[proto/cmd.HeatCamera.FocusIn]] | - |
| 14 | focus_out | [[proto/cmd.HeatCamera.FocusOut]] | - |
| 15 | focus_stop | [[proto/cmd.HeatCamera.FocusStop]] | - |
| 16 | calibrate | [[proto/cmd.HeatCamera.Calibrate]] | - |
| 17 | set_dde_level | [[proto/cmd.HeatCamera.SetDDELevel]] | - |
| 18 | enable_dde | [[proto/cmd.HeatCamera.EnableDDE]] | - |
| 19 | disable_dde | [[proto/cmd.HeatCamera.DisableDDE]] | - |
| 20 | set_auto_focus | [[proto/cmd.HeatCamera.SetAutoFocus]] | - |
| 21 | focus_step_plus | [[proto/cmd.HeatCamera.FocusStepPlus]] | - |
| 22 | focus_step_minus | [[proto/cmd.HeatCamera.FocusStepMinus]] | - |
| 23 | set_fx_mode | [[proto/cmd.HeatCamera.SetFxMode]] | - |
| 24 | next_fx_mode | [[proto/cmd.HeatCamera.NextFxMode]] | - |
| 25 | prev_fx_mode | [[proto/cmd.HeatCamera.PrevFxMode]] | - |
| 26 | get_meteo | [[proto/cmd.HeatCamera.GetMeteo]] | - |
| 27 | shift_dde | [[proto/cmd.HeatCamera.ShiftDDE]] | - |
| 28 | refresh_fx_mode | [[proto/cmd.HeatCamera.RefreshFxMode]] | - |
| 29 | reset_zoom | [[proto/cmd.HeatCamera.ResetZoom]] | - |
| 30 | save_to_table | [[proto/cmd.HeatCamera.SaveToTable]] | - |
| 31 | set_calib_mode | [[proto/cmd.HeatCamera.SetCalibMode]] | - |
| 32 | set_digital_zoom_level | [[proto/cmd.HeatCamera.SetDigitalZoomLevel]] | - |
| 33 | set_clahe_level | [[proto/cmd.HeatCamera.SetClaheLevel]] | - |
| 34 | shift_clahe_level | [[proto/cmd.HeatCamera.ShiftClaheLevel]] | - |
| 35 | focus_roi | [[proto/cmd.HeatCamera.FocusROI]] | - |
| 36 | track_roi | [[proto/cmd.HeatCamera.TrackROI]] | - |
| 37 | zoom_roi | [[proto/cmd.HeatCamera.ZoomROI]] | - |
| 38 | fx_roi | [[proto/cmd.HeatCamera.FxROI]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #10, #11, #12, #13, #14, #15, #16, #17, #18, #19, #20, #21, #22, #23, #24, #25, #26, #27, #28, #29, #30, #31, #32, #33, #34, #35, #36, #37, #38




## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :tabbed-config
- **Feedback:** :pending-timeout


### Purpose

Root message container for all heat camera commands


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]




### Implementation Notes

This is a oneof wrapper containing all heat camera command types



## Field Notes


### zoom (#1)

See [[proto/cmd.HeatCamera.Zoom]]


### set_agc (#2)

See [[proto/cmd.HeatCamera.SetAGC]]


### set_filter (#3)

Thermal image color filter


### start (#4)

See [[proto/cmd.HeatCamera.Start]]


### stop (#5)

See [[proto/cmd.HeatCamera.Stop]]


### photo (#6)

See [[proto/cmd.HeatCamera.Photo]]


### zoom_in (#10)

See [[proto/cmd.HeatCamera.ZoomIn]]


### zoom_out (#11)

See [[proto/cmd.HeatCamera.ZoomOut]]


### zoom_stop (#12)

See [[proto/cmd.HeatCamera.ZoomStop]]


### focus_in (#13)

See [[proto/cmd.HeatCamera.FocusIn]]


### focus_out (#14)

See [[proto/cmd.HeatCamera.FocusOut]]


### focus_stop (#15)

See [[proto/cmd.HeatCamera.FocusStop]]


### calibrate (#16)

See [[proto/cmd.HeatCamera.Calibrate]]


### set_dde_level (#17)

DDE (Dynamic Detail Enhancement) level


### enable_dde (#18)

See [[proto/cmd.HeatCamera.EnableDDE]]


### disable_dde (#19)

See [[proto/cmd.HeatCamera.DisableDDE]]


### set_auto_focus (#20)

See [[proto/cmd.HeatCamera.SetAutoFocus]]


### focus_step_plus (#21)

See [[proto/cmd.HeatCamera.FocusStepPlus]]


### focus_step_minus (#22)

See [[proto/cmd.HeatCamera.FocusStepMinus]]


### set_fx_mode (#23)

Image processing effects mode


### next_fx_mode (#24)

Image processing effects mode


### prev_fx_mode (#25)

Image processing effects mode


### get_meteo (#26)

See [[proto/cmd.HeatCamera.GetMeteo]]


### shift_dde (#27)

See [[proto/cmd.HeatCamera.ShiftDDE]]


### refresh_fx_mode (#28)

Image processing effects mode


### reset_zoom (#29)

See [[proto/cmd.HeatCamera.ResetZoom]]


### save_to_table (#30)

See [[proto/cmd.HeatCamera.SaveToTable]]


### set_calib_mode (#31)

See [[proto/cmd.HeatCamera.SetCalibMode]]


### set_digital_zoom_level (#32)

Digital zoom multiplier


### set_clahe_level (#33)

CLAHE contrast enhancement level (0.0 to 1.0)


### shift_clahe_level (#34)

CLAHE contrast enhancement level (0.0 to 1.0)


### focus_roi (#35)

See [[proto/cmd.HeatCamera.FocusROI]]


### track_roi (#36)

See [[proto/cmd.HeatCamera.TrackROI]]


### zoom_roi (#37)

See [[proto/cmd.HeatCamera.ZoomROI]]


### fx_roi (#38)

See [[proto/cmd.HeatCamera.FxROI]]



