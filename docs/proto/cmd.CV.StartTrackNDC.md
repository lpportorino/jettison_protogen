---
id: cmd.CV.StartTrackNDC
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# StartTrackNDC

**Source:** `jon_shared_cmd_cv.proto`

## Description

Initiates object tracking at a specific point using normalized device coordinates (NDC), where the user clicks on a video feed to begin tracking an object at that location. Includes frame and state timestamps for synchronization between frontend and backend video processing pipelines.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | channel | [[proto/ser.JonGuiDataVideoChannel]] | defined enum value only, not in: 0 |
| 2 | x | double | >= -1, <= 1 |
| 3 | y | double | >= -1, <= 1 |
| 4 | frame_time | uint64 | - |
| 5 | state_time | uint64 | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Starts video tracking at normalized device coordinates



### Related Commands

- [[proto/cmd.CV.StopTrack]]



### Implementation Notes

Implemented. This is the command the ROI rubber-band gesture sends: it is
pre-encoded into the gesture-surface template on every overlay screen, with the
two corner NDC pairs patched into fixed-width slots at fire time.



## Field Notes


### channel (#1)

Video channel selector


### x (#2)

X coordinate in NDC (-1.0 to 1.0)


### y (#3)

Y coordinate in NDC (-1.0 to 1.0)


### frame_time (#4)

Frame timestamp for synchronization


### state_time (#5)

State snapshot timestamp for synchronization



