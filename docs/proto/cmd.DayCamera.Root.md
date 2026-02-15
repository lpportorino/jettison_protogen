---
id: cmd.DayCamera.Root
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# Root

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

Root container message for all day camera control commands using a required oneof pattern. Routes different camera operations (focus, zoom, iris, FX modes, ROI tracking, etc.) to their respective handlers. The frontend constructs and sends these command messages to the backend, where cmd_hooks_day_camera.c dispatches them to appropriate device handlers.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | focus | [[proto/cmd.DayCamera.Focus]] | - |
| 2 | zoom | [[proto/cmd.DayCamera.Zoom]] | - |
| 3 | set_iris | [[proto/cmd.DayCamera.SetIris]] | - |
| 4 | set_infra_red_filter | [[proto/cmd.DayCamera.SetInfraRedFilter]] | - |
| 5 | start | [[proto/cmd.DayCamera.Start]] | - |
| 6 | stop | [[proto/cmd.DayCamera.Stop]] | - |
| 7 | photo | [[proto/cmd.DayCamera.Photo]] | - |
| 8 | set_auto_iris | [[proto/cmd.DayCamera.SetAutoIris]] | - |
| 9 | halt_all | [[proto/cmd.DayCamera.HaltAll]] | - |
| 10 | set_fx_mode | [[proto/cmd.DayCamera.SetFxMode]] | - |
| 11 | next_fx_mode | [[proto/cmd.DayCamera.NextFxMode]] | - |
| 12 | prev_fx_mode | [[proto/cmd.DayCamera.PrevFxMode]] | - |
| 13 | get_meteo | [[proto/cmd.DayCamera.GetMeteo]] | - |
| 14 | refresh_fx_mode | [[proto/cmd.DayCamera.RefreshFxMode]] | - |
| 15 | set_digital_zoom_level | [[proto/cmd.DayCamera.SetDigitalZoomLevel]] | - |
| 16 | set_clahe_level | [[proto/cmd.DayCamera.SetClaheLevel]] | - |
| 17 | shift_clahe_level | [[proto/cmd.DayCamera.ShiftClaheLevel]] | - |
| 18 | focus_roi | [[proto/cmd.DayCamera.FocusROI]] | - |
| 19 | track_roi | [[proto/cmd.DayCamera.TrackROI]] | - |
| 20 | zoom_roi | [[proto/cmd.DayCamera.ZoomROI]] | - |
| 21 | fx_roi | [[proto/cmd.DayCamera.FxROI]] | - |
| 22 | set_auto_gain | [[proto/cmd.DayCamera.SetAutoGain]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8, #9, #10, #11, #12, #13, #14, #15, #16, #17, #18, #19, #20, #21, #22




## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :state-machine-menu
- **Feedback:** :fire-and-forget


### Purpose

Root container for all day camera commands





### Implementation Notes

This is a oneOf container message, not directly invoked. Contains all day camera sub-commands like SetIris, Focus, Zoom, etc.



## Field Notes


### focus (#1)

See [[proto/cmd.DayCamera.Focus]]


### zoom (#2)

See [[proto/cmd.DayCamera.Zoom]]


### set_iris (#3)

See [[proto/cmd.DayCamera.SetIris]]


### set_infra_red_filter (#4)

Thermal image color filter


### start (#5)

See [[proto/cmd.DayCamera.Start]]


### stop (#6)

See [[proto/cmd.DayCamera.Stop]]


### photo (#7)

See [[proto/cmd.DayCamera.Photo]]


### set_auto_iris (#8)

See [[proto/cmd.DayCamera.SetAutoIris]]


### halt_all (#9)

See [[proto/cmd.DayCamera.HaltAll]]


### set_fx_mode (#10)

Image processing effects mode


### next_fx_mode (#11)

Image processing effects mode


### prev_fx_mode (#12)

Image processing effects mode


### get_meteo (#13)

See [[proto/cmd.DayCamera.GetMeteo]]


### refresh_fx_mode (#14)

Image processing effects mode


### set_digital_zoom_level (#15)

Digital zoom multiplier


### set_clahe_level (#16)

CLAHE contrast enhancement level (0.0 to 1.0)


### shift_clahe_level (#17)

CLAHE contrast enhancement level (0.0 to 1.0)


### focus_roi (#18)

See [[proto/cmd.DayCamera.FocusROI]]


### track_roi (#19)

See [[proto/cmd.DayCamera.TrackROI]]


### zoom_roi (#20)

See [[proto/cmd.DayCamera.ZoomROI]]


### fx_roi (#21)

See [[proto/cmd.DayCamera.FxROI]]


### set_auto_gain (#22)

See [[proto/cmd.DayCamera.SetAutoGain]]



