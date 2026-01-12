# Root (cmd.System.Root)

**Source:** `jon_shared_cmd_system.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| start_all | StartALl | 1 | - | - |
| stop_all | StopALl | 2 | - | - |
| reboot | Reboot | 3 | - | - |
| power_off | PowerOff | 4 | - | - |
| localization | SetLocalization | 5 | - | - |
| reset_configs | ResetConfigs | 6 | - | - |
| start_rec | StartRec | 7 | - | - |
| stop_rec | StopRec | 8 | - | - |
| mark_rec_important | MarkRecImportant | 9 | - | - |
| unmark_rec_important | UnmarkRecImportant | 10 | - | - |
| enter_transport | EnterTransport | 11 | - | - |
| geodesic_mode_enable | EnableGeodesicMode | 12 | - | - |
| geodesic_mode_disable | DisableGeodesicMode | 13 | - | - |
| save_factory_defaults | SaveFactoryDefaults | 14 | - | - |
| wipe_user_data | WipeUserData | 15 | - | - |
| step_year | StepYear | 16 | - | - |
| step_month | StepMonth | 17 | - | - |
| step_day | StepDay | 18 | - | - |
| step_hour | StepHour | 19 | - | - |
| step_minute | StepMinute | 20 | - | - |
| step_second | StepSecond | 21 | - | - |
| enable_manual_time | EnableManualTime | 22 | - | - |
| disable_manual_time | DisableManualTime | 23 | - | - |
| set_time_zone | SetTimeZone | 24 | - | - |
| step_time_zone | StepTimeZone | 25 | - | - |
| set_time_and_zone | SetTimeAndZone | 26 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_system.proto` for complete context
