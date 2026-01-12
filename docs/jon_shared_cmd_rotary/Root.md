# Root (cmd.RotaryPlatform.Root)

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Root container for all commands in this subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| start | Start | 1 | - | - |
| stop | Stop | 2 | - | - |
| axis | Axis | 3 | - | - |
| set_platform_azimuth | SetPlatformAzimuth | 4 | - | - |
| set_platform_elevation | SetPlatformElevation | 5 | - | - |
| set_platform_bank | SetPlatformBank | 6 | - | - |
| halt | Halt | 7 | - | - |
| set_use_rotary_as_compass | setUseRotaryAsCompass | 8 | - | - |
| rotate_to_gps | RotateToGPS | 9 | - | - |
| set_origin_gps | SetOriginGPS | 10 | - | - |
| set_mode | SetMode | 11 | - | - |
| rotate_to_ndc | RotateToNDC | 12 | - | - |
| scan_start | ScanStart | 13 | - | - |
| scan_stop | ScanStop | 14 | - | - |
| scan_pause | ScanPause | 15 | - | - |
| scan_unpause | ScanUnpause | 16 | - | - |
| get_meteo | GetMeteo | 17 | - | - |
| scan_prev | ScanPrev | 18 | - | - |
| scan_next | ScanNext | 19 | - | - |
| scan_refresh_node_list | ScanRefreshNodeList | 20 | - | - |
| scan_select_node | ScanSelectNode | 21 | - | - |
| scan_delete_node | ScanDeleteNode | 22 | - | - |
| scan_update_node | ScanUpdateNode | 23 | - | - |
| scan_add_node | ScanAddNode | 24 | - | - |
| halt_with_ndc | HaltWithNDC | 25 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_rotary.proto` for complete context
