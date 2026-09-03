---
id: ser.JonGuiDataDriveState
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataDriveState

**Source:** `jon_shared_data_types.proto`

## Description

Lifecycle of the owning drive program. IDLE: loaded, not running. ARMED: start requested, waiting for the platform to be still and any halt re-assert window to elapse. RUNNING: driving. PAUSED: scan paused by the operator. DONE: finished (park forwarded, POI arrived). FAULT: aborted with an `error_code` on JonGuiDataDrive; a HALT was emitted.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_DRIVE_STATE_UNSPECIFIED | No program loaded or status not yet published. |
| 1 | JON_GUI_DATA_DRIVE_STATE_IDLE | Program loaded and not running. |
| 2 | JON_GUI_DATA_DRIVE_STATE_ARMED | Start requested; waiting for the platform to be still and any halt re-assert window to elapse before motion. |
| 3 | JON_GUI_DATA_DRIVE_STATE_RUNNING | Program is driving the platform. |
| 4 | JON_GUI_DATA_DRIVE_STATE_PAUSED | Scan paused by the operator (ScanPause or an operator override); ScanUnpause resumes. |
| 5 | JON_GUI_DATA_DRIVE_STATE_DONE | Program finished: park forwarded the transport latch, or the POI look-at arrived. |
| 6 | JON_GUI_DATA_DRIVE_STATE_FAULT | Program aborted; JonGuiDataDrive.error_code says why and a HALT was emitted. |

