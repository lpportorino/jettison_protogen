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
- **UI Pattern:** :command-router
- **Feedback:** :fire-and-forget
- **Related State:** ser.JonGuiDataCameraDay
- **Related Commands:** cmd.HeatCamera.Root (thermal equivalent)


### Purpose

Root container for all day camera commands. Frontend creates Root instances with exactly one sub-command field set, then assigns to `rootMsg.dayCamera` before sending via WebSocket/WebTransport to cmd_server.





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

Controls the infrared cut filter on the day camera. When enabled, blocks near-IR light for accurate color reproduction; when disabled, allows IR sensitivity for low-light operation.


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

Sets the image processing effects mode explicitly by value. Uses JonGuiDataFxModeDay enum.

### next_fx_mode (#11)

Cycles to the next FX mode in sequence. Fire-and-forget action button.

### prev_fx_mode (#12)

Cycles to the previous FX mode in sequence. Fire-and-forget action button.


### get_meteo (#13)

See [[proto/cmd.DayCamera.GetMeteo]]


### refresh_fx_mode (#14)

Reloads the current FX mode parameters from Redis without changing the mode. Used after config changes.


### set_digital_zoom_level (#15)

Sets the digital zoom multiplier applied in the GPU FX pipeline (PRE stage). Separate from optical zoom.

### set_clahe_level (#16)

Sets the CLAHE (Contrast Limited Adaptive Histogram Equalization) level absolutely. Value range 0.0 to 1.0.

### shift_clahe_level (#17)

Adjusts the CLAHE level by a relative delta. Useful for increment/decrement controls.


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



