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

- [[proto/proto/proto/cmd.CV.StopTrack]]



### Implementation Notes

Not implemented in current frontend version



