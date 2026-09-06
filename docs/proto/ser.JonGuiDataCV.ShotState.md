---
id: ser.JonGuiDataCV.ShotState
proto: jon_shared_data_cv.proto
package: ser.JonGuiDataCV
type: enum
---

# ShotState

**Source:** `jon_shared_data_cv.proto`

## Description

The lifecycle of the one-at-a-time cv-dump PHOTO ([[proto/cmd.CV.DumpShot]]), published by eutropia on [[proto/ser.JonGuiDataCV#shot_state]]. A press is accepted only in `IDLE`; `READY` and `FAILED` are the terminal states of the last shot and give way to `IDLE` on the next accepted press.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | SHOT_STATE_UNSPECIFIED | Never populated by eutropia; a reader treats it as "no shot support on this build" and hides the button |
| 1 | SHOT_STATE_IDLE | No shot in flight — a press is accepted |
| 2 | SHOT_STATE_CAPTURING | Waiting for the next RAW-armed frame on each channel; the ring copies are in progress |
| 3 | SHOT_STATE_WRITING | Planes copied out; PNG encode + bundle write in progress (seconds, off the frame path) |
| 4 | SHOT_STATE_READY | The bundle is on disk — shot_id names it and shot_seq has advanced |
| 5 | SHOT_STATE_FAILED | The shot was abandoned (a channel never published a RAW frame, or the write failed); the bundle, if any, carries the reason |

