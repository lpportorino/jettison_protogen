---
id: cmd.CV.StopTrackTrinity
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# StopTrackTrinity

**Source:** `jon_shared_cmd_cv.proto`

## Description

Stop tracking the Ring-Trinity board.

Symmetric with [[proto/cmd.CV.StopTrack]] for NDC tracking; takes no fields.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :poll-confirm


### Purpose

Stops the Ring-Trinity board tracker. Paired with [[proto/cmd.CV.StartTrackTrinity]]; the two back a single toggle rather than two independent buttons.


### Related State

- [[proto/ser.JonGuiDataCV]]
- [[proto/ser.TrinityTracking]]
- [[proto/ser.TrinityTrackingStatus]]


### Related Commands

- [[proto/cmd.CV.StartTrackTrinity]]



### Implementation Notes

**Confirmed on the STATE plane** by `JonGuiDataCV.trinity_tracking_active` (#90) going `false`.

**On the opaque plane the tracker keeps publishing** while its process is up, reporting `TRINITY_TRACKING_STATUS_IDLE` — see [[proto/ser.TrinityTrackingStatus]]. The [[proto/ser.TrinityTracking]] payload going ABSENT is a different fact, namely that the producer is down, and must not be read as "stopped".



