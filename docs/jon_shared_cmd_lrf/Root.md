# Root (cmd.Lrf.Root)

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| measure | Measure | 1 | - | - |
| scan_on | ScanOn | 2 | - | - |
| scan_off | ScanOff | 3 | - | - |
| start | Start | 4 | - | - |
| stop | Stop | 5 | - | - |
| target_designator_off | TargetDesignatorOff | 6 | - | - |
| target_designator_on_mode_a | TargetDesignatorOnModeA | 7 | - | - |
| target_designator_on_mode_b | TargetDesignatorOnModeB | 8 | - | - |
| enable_fog_mode | EnableFogMode | 9 | - | - |
| disable_fog_mode | DisableFogMode | 10 | - | - |
| set_scan_mode | SetScanMode | 11 | - | - |
| new_session | NewSession | 12 | - | - |
| get_meteo | GetMeteo | 13 | - | - |
| refine_on | RefineOn | 14 | - | - |
| refine_off | RefineOff | 15 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_lrf.proto` for complete context
