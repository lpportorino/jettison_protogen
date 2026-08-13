---
id: ser.JonGuiDataCV
proto: jon_shared_data_cv.proto
package: ser
type: message
---

# JonGuiDataCV

**Source:** `jon_shared_data_cv.proto`

## Description

CV Gateway state enrichment message: the CV subsystem's per-tick state as it appears on the STATE plane, for both day and heat camera channels.

This message is populated by the CV Gateway and embedded in `JonGUIState` before being written to shared memory. It provides real-time visibility into:
- Autofocus sweep progress and state
- Current and best sharpness measurements, and the sharpness metrics carrying their temporal derivatives
- The regions of interest each camera operation is using — focus, track, zoom, fx — per channel
- CV bridge container status, exit reason, uptime and restart count
- Camera 3D pose and velocity, per channel
- Tracked objects, each carrying a UUID for joining against external data
- Whether the Ring-Trinity board tracker is running

The ROI coordinates use Normalized Device Coordinates (NDC) ranging from -1 to 1, where (0,0) is the center of the frame.

**This message is the STATE plane, and it is not the whole CV surface.** The richer CV output — object detections ([[proto/ser.ObjectDetectionsDay]], [[proto/ser.ObjectDetectionsHeat]]), SAM tracking, the aggregated [[proto/ser.CvMeta]], and the Ring-Trinity metric pose [[proto/ser.TrinityTracking]] — does not travel here. It rides `JonGUIState.opaque_payloads` as [[proto/ser.JonOpaquePayload]] entries and is decoded only by the consumers that handle each payload type, the OSD overlay path among them. A consumer of this message does not parse those payloads.

That split is why a fact an opaque payload already carries can also appear here, in the reduced form a state consumer can act on — `trinity_tracking_active` (#90) is exactly that shape. The two are different contracts with different consumers and different evolution boundaries; neither is a copy of the other, and neither suppresses the other.

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
| 90 | trinity_tracking_active | bool | - |
| 91 | zoom_roi_active_day | bool | - |
| 92 | zoom_roi_active_heat | bool | - |
| 100 | stab_correction_day | [[proto/ser.JonGuiDataStabCorrection]] | - |
| 101 | stab_correction_heat | [[proto/ser.JonGuiDataStabCorrection]] | - |


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


### _stab_correction_day

Fields: #100


### _stab_correction_heat

Fields: #101





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


### trinity_tracking_active (#90)

Whether the Ring-Trinity board tracker is **running**. It follows the tracker's actual run state, which [[proto/cmd.CV.StartTrackTrinity]] and [[proto/cmd.CV.StopTrackTrinity]] are what change.

**This is the STATE plane's answer, and it exists for the toggle affordance.** A consumer reading `JonGUIState` can enable, disable and reflect the trinity control from this field alone; it never has to decode `JonGUIState.opaque_payloads`, which the state plane does not parse.

**The pose travels on the other plane, to a different consumer, and this field does not serve it.** [[proto/ser.TrinityTracking]] rides `JonGUIState.opaque_payloads` and is routed to the OSD overlay, which renders the metric pose. That consumer needs the pose, the per-axis sigmas, the observability figures and the board identity — a bool would tell it nothing. The two are not copies of one another and neither suppresses the other: different contracts, different consumers, different evolution boundaries.

**It collapses the tracker's states on purpose.** `LOCKED`, `SEARCHING`, `DEGRADED` and `BOARD_MISMATCH` are all `true` here, because a toggle asks only whether tracking is RUNNING; `TRINITY_TRACKING_STATUS_IDLE` is `false`, as is the tracker not being up at all. Anything that must tell those apart — is there a lock, is the pose valid, is this the board that was asked for — reads `TrinityTracking.status` ([[proto/ser.TrinityTrackingStatus]]) from the opaque payload, which is the authoritative and richer value. This field cannot answer that and must not be read as though it could.

It carries no validation constraint, and none is available: `false` is proto3's zero default, so nothing on the wire distinguishes "not tracking" from "this field was never populated". That is the same absence-versus-state hazard [[proto/ser.TrinityTrackingStatus]] documents one plane over, and it is why this field answers the toggle question only, rather than standing in for the tracker's health.



