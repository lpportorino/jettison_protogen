---
id: cmd.CV.Root
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# Root

**Source:** `jon_shared_cmd_cv.proto`

## Description

Container message for computer vision commands that provides object tracking, autofocus control, and various CV processing modes (vampire, stabilization, recognition) through a mutually-exclusive oneof command dispatch pattern.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | set_auto_focus | [[proto/cmd.CV.SetAutoFocus]] | - |
| 2 | start_track_ndc | [[proto/cmd.CV.StartTrackNDC]] | - |
| 3 | stop_track | [[proto/cmd.CV.StopTrack]] | - |
| 4 | vampire_mode_enable | [[proto/cmd.CV.VampireModeEnable]] | - |
| 5 | vampire_mode_disable | [[proto/cmd.CV.VampireModeDisable]] | - |
| 6 | stabilization_mode_enable | [[proto/cmd.CV.StabilizationModeEnable]] | - |
| 7 | stabilization_mode_disable | [[proto/cmd.CV.StabilizationModeDisable]] | - |
| 8 | dump_start | [[proto/cmd.CV.DumpStart]] | - |
| 9 | dump_stop | [[proto/cmd.CV.DumpStop]] | - |
| 10 | recognition_mode_enable | [[proto/cmd.CV.RecognitionModeEnable]] | - |
| 11 | recognition_mode_disable | [[proto/cmd.CV.RecognitionModeDisable]] | - |
| 20 | bridge_start | [[proto/cmd.CV.BridgeStart]] | - |
| 21 | bridge_stop | [[proto/cmd.CV.BridgeStop]] | - |
| 22 | bridge_restart | [[proto/cmd.CV.BridgeRestart]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8, #9, #10, #11, #20, #21, #22




## Interaction

- **Category:** :settings
- **UI Pattern:** :state-machine-menu
- **Feedback:** :pending-timeout

<!-- NEEDS_REVIEW: UI pattern could also be :tabbed-config if modes are accessed through tabs rather than state toggles -->

### Purpose

Root command container for computer vision subsystem control. Dispatches to sub-commands for object tracking (start/stop), autofocus toggle, mode switches (vampire, stabilization, recognition), CV dump sessions for debugging, and bridge lifecycle management.


### Related State

- [[proto/ser.JonGuiDataCV]] - CV state including autofocus metrics, sharpness, ROIs, bridge status, and tracked objects

### Related Commands

- [[proto/cmd.CV.SetAutoFocus]] - Toggle autofocus on/off for day or heat channel
- [[proto/cmd.CV.StartTrackNDC]] - Start object tracking at NDC coordinates
- [[proto/cmd.CV.StopTrack]] - Stop current object tracking
- [[proto/cmd.CV.VampireModeEnable]] - Enable sun avoidance mode
- [[proto/cmd.CV.VampireModeDisable]] - Disable sun avoidance mode
- [[proto/cmd.CV.StabilizationModeEnable]] - Enable image stabilization
- [[proto/cmd.CV.StabilizationModeDisable]] - Disable image stabilization
- [[proto/cmd.CV.RecognitionModeEnable]] - Enable AI object recognition
- [[proto/cmd.CV.RecognitionModeDisable]] - Disable AI object recognition
- [[proto/cmd.CV.DumpStart]] - Start CV dump session for debugging
- [[proto/cmd.CV.DumpStop]] - Stop CV dump session
- [[proto/cmd.CV.BridgeStart]] - Start CV bridge process
- [[proto/cmd.CV.BridgeStop]] - Stop CV bridge process
- [[proto/cmd.CV.BridgeRestart]] - Restart CV bridge process


### Preconditions

- CV bridge must be running for tracking and autofocus commands
- Pipelines must be started for video-dependent CV features


### Implementation Notes

Frontend uses toggle buttons with pending-timeout feedback (2s timeout). Each mode command pair (enable/disable) reflects in `ser.JonGuiDataSystem` state fields: `vampireMode`, `stabilizationMode`, `recognitionMode`, `tracking`, `cvDumping`. UI buttons show pending state until backend confirms state change or timeout expires. Tracking can be initiated via point click (NDC coordinates) or ROI selection (bounding box)



## Field Notes


### set_auto_focus (#1)

Toggle autofocus for day or heat camera channel. Used by `jon-focus-ui` component with toggle button UI. See [[proto/cmd.CV.SetAutoFocus]]


### start_track_ndc (#2)

Initiate object tracking at normalized device coordinates (-1 to 1). Requires channel, frame timestamp, and state monotonic time for temporal alignment. Triggered by point selection in tracking overlay. See [[proto/cmd.CV.StartTrackNDC]]


### stop_track (#3)

Terminate active object tracking. Shown as dedicated button when tracking is active. See [[proto/cmd.CV.StopTrack]]


### vampire_mode_enable (#4)

Enable vampire mode - cameras actively avoid looking at the sun/bright light sources. Toggle button shows pending state during transition. See [[proto/cmd.CV.VampireModeEnable]]


### vampire_mode_disable (#5)

Disable vampire mode - allows cameras to look at bright light sources. See [[proto/cmd.CV.VampireModeDisable]]


### stabilization_mode_enable (#6)

Enable image stabilization - reduces camera shake and vibration through digital compensation. See [[proto/cmd.CV.StabilizationModeEnable]]


### stabilization_mode_disable (#7)

Disable image stabilization - allows manual camera movement without compensation. See [[proto/cmd.CV.StabilizationModeDisable]]


### dump_start (#8)

Start CV dump session for debugging. Increases logging rate from 1% to 100% for autofocus, auto-diaphragm, and CV state tables. Developer mode only. See [[proto/cmd.CV.DumpStart]]


### dump_stop (#9)

Stop CV dump session - returns to 1% sampling rate. See [[proto/cmd.CV.DumpStop]]


### recognition_mode_enable (#10)

Enable AI object recognition and tracking. Activates neural network inference for object detection. See [[proto/cmd.CV.RecognitionModeEnable]]


### recognition_mode_disable (#11)

Disable AI object recognition and tracking. See [[proto/cmd.CV.RecognitionModeDisable]]


### bridge_start (#20)

Start CV bridge process. Bridge is required for autofocus, tracking, and state enrichment. See [[proto/cmd.CV.BridgeStart]]


### bridge_stop (#21)

Stop CV bridge process. WARNING: Stopping bridge will disable all CV functionality. See [[proto/cmd.CV.BridgeStop]]


### bridge_restart (#22)

Restart CV bridge process. Used for recovery from errors or applying configuration changes. See [[proto/cmd.CV.BridgeRestart]]



