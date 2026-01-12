# Root (cmd.OSD.Root)

**Source:** `jon_shared_cmd_osd.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| show_default_screen | ShowDefaultScreen | 1 | - | - |
| show_lrf_measure_screen | ShowLRFMeasureScreen | 2 | - | - |
| show_lrf_result_screen | ShowLRFResultScreen | 3 | - | - |
| show_lrf_result_simplified_screen | ShowLRFResultSimplifiedScreen | 4 | - | - |
| enable_heat_osd | EnableHeatOSD | 5 | - | - |
| disable_heat_osd | DisableHeatOSD | 6 | - | - |
| enable_day_osd | EnableDayOSD | 7 | - | - |
| disable_day_osd | DisableDayOSD | 8 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_osd.proto` for complete context
