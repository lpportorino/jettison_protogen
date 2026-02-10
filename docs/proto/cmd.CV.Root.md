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
- **UI Pattern:** :tabbed-config


### Purpose

Root message for computer vision commands including tracking, autofocus, and mode controls



### Related Commands

- [[proto/cmd.CV.DumpStart]]
- [[proto/cmd.CV.DumpStop]]



### Implementation Notes

Container message for various CV operations



