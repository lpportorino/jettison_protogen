---
id: ser.JonGuiDataCV
proto: jon_shared_data_cv.proto
package: ser
type: message
---

# JonGuiDataCV

**Source:** `jon_shared_data_cv.proto`

## Description

CV Gateway state enrichment message containing autofocus metrics and sweep status for both day and heat camera channels.

This message is populated by the CV Gateway and embedded in `JonGUIState` before being written to shared memory. It provides real-time visibility into:
- Autofocus sweep progress and state
- Current and best sharpness measurements
- Region of interest (ROI) used for sharpness calculation

The ROI coordinates use Normalized Device Coordinates (NDC) ranging from -1 to 1, where (0,0) is the center of the frame.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | autofocus_state_day | [[proto/ser.JonGuiDataCV.AutofocusState]] | defined enum value only |
| 2 | sharpness_day | double | >= 0 |
| 3 | best_sharpness_day | double | >= 0 |
| 4 | sweep_progress_day | int32 | >= 0, <= 100 |
| 5 | best_focus_pos_day | double | >= 0, <= 1 |
| 10 | autofocus_state_heat | [[proto/ser.JonGuiDataCV.AutofocusState]] | defined enum value only |
| 11 | sharpness_heat | double | >= 0 |
| 12 | best_sharpness_heat | double | >= 0 |
| 13 | sweep_progress_heat | int32 | >= 0, <= 100 |
| 14 | best_focus_pos_heat | double | >= 0, <= 1 |
| 20 | roi_x1 | double | >= -1, <= 1 |
| 21 | roi_y1 | double | >= -1, <= 1 |
| 22 | roi_x2 | double | >= -1, <= 1 |
| 23 | roi_y2 | double | >= -1, <= 1 |
| 30 | bridge_status | [[proto/ser.JonGuiDataCV.CvBridgeStatus]] | defined enum value only |
| 31 | last_exit_reason | [[proto/ser.JonGuiDataCV.CvBridgeExitReason]] | defined enum value only |
| 32 | bridge_uptime_ms | int64 | >= 0 |
| 33 | restart_count | int32 | >= 0 |

## CV Bridge Status Fields

Fields 30-33 provide visibility into the CV Bridge Docker container status:

### bridge_status (field 30)

Current operational status of the CV Bridge container:
- `CV_BRIDGE_STATUS_UNSPECIFIED` (0) - Unknown/default state
- `CV_BRIDGE_STATUS_STOPPED` (1) - Container is not running
- `CV_BRIDGE_STATUS_STARTING` (2) - Container is starting up
- `CV_BRIDGE_STATUS_RUNNING` (3) - Container is healthy and processing frames
- `CV_BRIDGE_STATUS_STOPPING` (4) - Graceful shutdown in progress
- `CV_BRIDGE_STATUS_CRASHED` (5) - Container exited unexpectedly
- `CV_BRIDGE_STATUS_RESTARTING` (6) - Auto-restart in progress

### last_exit_reason (field 31)

Reason for the last CV Bridge container termination:
- `CV_BRIDGE_EXIT_REASON_UNSPECIFIED` (0) - Unknown/default
- `CV_BRIDGE_EXIT_REASON_NOT_STARTED` (1) - Never started since system boot
- `CV_BRIDGE_EXIT_REASON_NORMAL` (2) - Clean shutdown via BridgeStop command
- `CV_BRIDGE_EXIT_REASON_ERROR` (3) - Internal application error
- `CV_BRIDGE_EXIT_REASON_CUDA_ERROR` (4) - CUDA/GPU failure
- `CV_BRIDGE_EXIT_REASON_IPC_ERROR` (5) - Lost connection to CUDA IPC producer
- `CV_BRIDGE_EXIT_REASON_OOM` (6) - Out of memory (killed by OOM)
- `CV_BRIDGE_EXIT_REASON_TIMEOUT` (7) - Watchdog timeout (unresponsive)
- `CV_BRIDGE_EXIT_REASON_SIGNAL` (8) - Killed by external signal (SIGKILL, SIGTERM)

### bridge_uptime_ms (field 32)

Milliseconds since the CV Bridge container last started. Resets to 0 on each restart.

### restart_count (field 33)

Number of times the CV Bridge container has been restarted since system boot. Used to track stability and detect restart loops.




