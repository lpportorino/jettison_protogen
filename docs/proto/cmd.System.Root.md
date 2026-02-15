---
id: cmd.System.Root
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# Root

**Source:** `jon_shared_cmd_system.proto`

## Description

Union message that dispatches system-level commands through a required oneof field, allowing clients to send exactly one of 26 different system operation types (reboot, power-off, time adjustment, recording control, configuration management) in a type-safe, mutually-exclusive manner.

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

- **Category:** :actuator
- **UI Pattern:** :command-router
- **Feedback:** :fire-and-forget


### Purpose

Root system command container





### Implementation Notes

Container message for all system-level commands



## Field Notes


### start_all (#1)

See [[proto/cmd.System.StartALl]]


### stop_all (#2)

See [[proto/cmd.System.StopALl]]


### reboot (#3)

See [[proto/cmd.System.Reboot]]


### power_off (#4)

See [[proto/cmd.System.PowerOff]]


### localization (#5)

See [[proto/cmd.System.SetLocalization]]


### reset_configs (#6)

See [[proto/cmd.System.ResetConfigs]]


### start_rec (#7)

See [[proto/cmd.System.StartRec]]


### stop_rec (#8)

See [[proto/cmd.System.StopRec]]


### mark_rec_important (#9)

See [[proto/cmd.System.MarkRecImportant]]


### unmark_rec_important (#10)

See [[proto/cmd.System.UnmarkRecImportant]]


### enter_transport (#11)

See [[proto/cmd.System.EnterTransport]]


### geodesic_mode_enable (#12)

See [[proto/cmd.System.EnableGeodesicMode]]


### geodesic_mode_disable (#13)

See [[proto/cmd.System.DisableGeodesicMode]]


### save_factory_defaults (#14)

See [[proto/cmd.System.SaveFactoryDefaults]]


### wipe_user_data (#15)

See [[proto/cmd.System.WipeUserData]]


### step_year (#16)

See [[proto/cmd.System.StepYear]]


### step_month (#17)

See [[proto/cmd.System.StepMonth]]


### step_day (#18)

See [[proto/cmd.System.StepDay]]


### step_hour (#19)

See [[proto/cmd.System.StepHour]]


### step_minute (#20)

See [[proto/cmd.System.StepMinute]]


### step_second (#21)

See [[proto/cmd.System.StepSecond]]


### enable_manual_time (#22)

See [[proto/cmd.System.EnableManualTime]]


### disable_manual_time (#23)

See [[proto/cmd.System.DisableManualTime]]


### set_time_zone (#24)

See [[proto/cmd.System.SetTimeZone]]


### step_time_zone (#25)

See [[proto/cmd.System.StepTimeZone]]


### set_time_and_zone (#26)

See [[proto/cmd.System.SetTimeAndZone]]



