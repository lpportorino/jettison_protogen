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
| 40 | roi_focus_day | [[proto/ser.JonGuiDataROI]] | - |
| 41 | roi_track_day | [[proto/ser.JonGuiDataROI]] | - |
| 42 | roi_zoom_day | [[proto/ser.JonGuiDataROI]] | - |
| 43 | roi_fx_day | [[proto/ser.JonGuiDataROI]] | - |
| 50 | roi_focus_heat | [[proto/ser.JonGuiDataROI]] | - |
| 51 | roi_track_heat | [[proto/ser.JonGuiDataROI]] | - |
| 52 | roi_zoom_heat | [[proto/ser.JonGuiDataROI]] | - |
| 53 | roi_fx_heat | [[proto/ser.JonGuiDataROI]] | - |
| 60 | sharpness_metrics_day | [[proto/ser.JonGuiDataSharpness]] | - |
| 61 | sharpness_metrics_heat | [[proto/ser.JonGuiDataSharpness]] | - |
| 70 | camera_transform_day | [[proto/ser.JonGuiDataTransform3D]] | - |
| 71 | camera_transform_heat | [[proto/ser.JonGuiDataTransform3D]] | - |
| 80 | tracked_objects | repeated [[proto/ser.JonGuiDataTrackedObject]] | - |


## Oneofs


### _roi_focus_day

Fields: #40


### _roi_track_day

Fields: #41


### _roi_zoom_day

Fields: #42


### _roi_fx_day

Fields: #43


### _roi_focus_heat

Fields: #50


### _roi_track_heat

Fields: #51


### _roi_zoom_heat

Fields: #52


### _roi_fx_heat

Fields: #53


### _sharpness_metrics_day

Fields: #60


### _sharpness_metrics_heat

Fields: #61


### _camera_transform_day

Fields: #70


### _camera_transform_heat

Fields: #71





## Field Notes


### autofocus_state_day (#1)

See related enum for valid values


### sharpness_day (#2)

Focus sharpness metric


### best_sharpness_day (#3)

Best sharpness found during autofocus sweep


### sweep_progress_day (#4)

Percentage value (0-100)


### best_focus_pos_day (#5)

Normalized value (0.0 to 1.0)


### autofocus_state_heat (#10)

See related enum for valid values


### sharpness_heat (#11)

Focus sharpness metric


### best_sharpness_heat (#12)

Best sharpness found during autofocus sweep


### sweep_progress_heat (#13)

Percentage value (0-100)


### best_focus_pos_heat (#14)

Normalized value (0.0 to 1.0)


### roi_x1 (#20)

Left edge in NDC (-1.0 to 1.0)


### roi_y1 (#21)

Top edge in NDC (-1.0 to 1.0)


### roi_x2 (#22)

Right edge in NDC (-1.0 to 1.0)


### roi_y2 (#23)

Bottom edge in NDC (-1.0 to 1.0)


### bridge_status (#30)

See related enum for valid values


### last_exit_reason (#31)

See related enum for valid values


### bridge_uptime_ms (#32)

CV bridge uptime in milliseconds


### restart_count (#33)

CV bridge restart count


### roi_focus_day (#40)

ROI edge in NDC (-1.0 to 1.0)


### roi_track_day (#41)

ROI edge in NDC (-1.0 to 1.0)


### roi_zoom_day (#42)

ROI edge in NDC (-1.0 to 1.0)


### roi_fx_day (#43)

ROI edge in NDC (-1.0 to 1.0)


### roi_focus_heat (#50)

ROI edge in NDC (-1.0 to 1.0)


### roi_track_heat (#51)

ROI edge in NDC (-1.0 to 1.0)


### roi_zoom_heat (#52)

ROI edge in NDC (-1.0 to 1.0)


### roi_fx_heat (#53)

ROI edge in NDC (-1.0 to 1.0)


### sharpness_metrics_day (#60)

Focus sharpness metric


### sharpness_metrics_heat (#61)

Focus sharpness metric


### camera_transform_day (#70)

See [[proto/ser.JonGuiDataTransform3D]]


### camera_transform_heat (#71)

See [[proto/ser.JonGuiDataTransform3D]]


### tracked_objects (#80)

See [[proto/ser.JonGuiDataTrackedObject]]



