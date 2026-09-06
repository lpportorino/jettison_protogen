---
id: cmd.CV.DumpShot
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# DumpShot

**Source:** `jon_shared_cmd_cv.proto`

## Description

Takes the cv-dump PHOTO: ONE instant, every plane of BOTH channels' CUDA-IPC rings — the RAW pre-ISP frame, the native raster, the CLAHE plane and the operator picture — written as a cv_dump bundle whose `archive.pb` carries one `ShotCapture` per channel (with the whole 1024-byte control block verbatim) and one `ShotPlane` per file (`shots/day_p0_raw.rg12`, `shots/day_p1_native.png`, …). Only available in factory mode (URL parameter ui=factory), beside DumpStart.

The message is EMPTY on purpose. A shot is always both channels — the artifact's value is that they are the same instant, and a channel that is powered off appears in the bundle with no planes and a reason rather than being chosen away. Every capture parameter (zoom, fx mode, the ISP tokens) already rides the ring's control block, and the operator note arrives later over `PUT /note/{id}` exactly as a dump's does.

Consumed by eutropia at its command flow and never forwarded to manifold. Progress and result ride the STATE plane: [[proto/ser.JonGuiDataCV#shot_state]] (what the button renders), [[proto/ser.JonGuiDataCV#shot_seq]] (the increment that proves the shot landed) and [[proto/ser.JonGuiDataCV#shot_id]] (the bundle to link). A press while `shot_state` is not `IDLE` is refused, not queued.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :poll-confirm


### Purpose

Capture every ring plane of both channels at one instant as a cv_dump photo bundle


### Related State

- [[proto/ser.JonGuiDataCV#shot_seq]]
- [[proto/ser.JonGuiDataCV#shot_state]]
- [[proto/ser.JonGuiDataCV#shot_id]]


### Related Commands

- [[proto/cmd.CV.DumpStart]]
- [[proto/cmd.CV.DumpStop]]



### Implementation Notes

The button watches `shot_seq` for an increment (not the ack) and renders `shot_state`; a second press is refused while a shot is in flight. The capture takes the FIRST published frame of each channel whose control block carries a RAW plane after the command, so the instant is "the next frame", not the frame the operator's eye was on.



