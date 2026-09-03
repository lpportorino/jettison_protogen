---
id: ser.JonGuiDataDriveProgram
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataDriveProgram

**Source:** `jon_shared_data_types.proto`

## Description

Identifies which sandboxed drive program owns the rotary platform: none, the scan-pattern walker, the point-of-interest look-at, or the transport-park sequencer.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_DRIVE_PROGRAM_NONE | No drive program owns the platform; operator and tracker commands pass through untouched. |
| 1 | JON_GUI_DATA_DRIVE_PROGRAM_SCAN | The scan-pattern walker owns the platform (started by ScanStart). |
| 2 | JON_GUI_DATA_DRIVE_PROGRAM_POI | The point-of-interest look-at owns the platform (started by PoiLookAt). |
| 3 | JON_GUI_DATA_DRIVE_PROGRAM_PARK | The transport-park sequencer owns the platform (started by System.EnterTransport). |

