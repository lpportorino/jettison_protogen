# Root (cmd.CV.Root)

**Source:** `jon_shared_cmd_cv.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| set_auto_focus | SetAutoFocus | 1 | - | - |
| start_track_ndc | StartTrackNDC | 2 | - | - |
| stop_track | StopTrack | 3 | - | - |
| vampire_mode_enable | VampireModeEnable | 4 | - | - |
| vampire_mode_disable | VampireModeDisable | 5 | - | - |
| stabilization_mode_enable | StabilizationModeEnable | 6 | - | - |
| stabilization_mode_disable | StabilizationModeDisable | 7 | - | - |
| dump_start | DumpStart | 8 | - | - |
| dump_stop | DumpStop | 9 | - | - |
| recognition_mode_enable | RecognitionModeEnable | 10 | - | - |
| recognition_mode_disable | RecognitionModeDisable | 11 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_cv.proto` for complete context
