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




