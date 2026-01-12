---
id: cmd.System.Root
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# Root

**Source:** `jon_shared_cmd_system.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start_all | [[proto/cmd.System.StartALl]] | - |
| 2 | stop_all | [[proto/cmd.System.StopALl]] | - |
| 3 | reboot | [[proto/cmd.System.Reboot]] | - |
| 4 | power_off | [[proto/cmd.System.PowerOff]] | - |
| 5 | localization | [[proto/cmd.System.SetLocalization]] | - |
| 6 | reset_configs | [[proto/cmd.System.ResetConfigs]] | - |
| 7 | start_rec | [[proto/cmd.System.StartRec]] | - |
| 8 | stop_rec | [[proto/cmd.System.StopRec]] | - |
| 9 | mark_rec_important | [[proto/cmd.System.MarkRecImportant]] | - |
| 10 | unmark_rec_important | [[proto/cmd.System.UnmarkRecImportant]] | - |
| 11 | enter_transport | [[proto/cmd.System.EnterTransport]] | - |
| 12 | geodesic_mode_enable | [[proto/cmd.System.EnableGeodesicMode]] | - |
| 13 | geodesic_mode_disable | [[proto/cmd.System.DisableGeodesicMode]] | - |
| 14 | save_factory_defaults | [[proto/cmd.System.SaveFactoryDefaults]] | - |
| 15 | wipe_user_data | [[proto/cmd.System.WipeUserData]] | - |
| 16 | step_year | [[proto/cmd.System.StepYear]] | - |
| 17 | step_month | [[proto/cmd.System.StepMonth]] | - |
| 18 | step_day | [[proto/cmd.System.StepDay]] | - |
| 19 | step_hour | [[proto/cmd.System.StepHour]] | - |
| 20 | step_minute | [[proto/cmd.System.StepMinute]] | - |
| 21 | step_second | [[proto/cmd.System.StepSecond]] | - |
| 22 | enable_manual_time | [[proto/cmd.System.EnableManualTime]] | - |
| 23 | disable_manual_time | [[proto/cmd.System.DisableManualTime]] | - |
| 24 | set_time_zone | [[proto/cmd.System.SetTimeZone]] | - |
| 25 | step_time_zone | [[proto/cmd.System.StepTimeZone]] | - |
| 26 | set_time_and_zone | [[proto/cmd.System.SetTimeAndZone]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8, #9, #10, #11, #12, #13, #14, #15, #16, #17, #18, #19, #20, #21, #22, #23, #24, #25, #26




## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Root system command container





### Implementation Notes

Container message for all system-level commands



