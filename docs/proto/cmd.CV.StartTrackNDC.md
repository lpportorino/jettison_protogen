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

Implemented in the wire vocabulary, and NOT pre-encoded by anything in this
repository — read the plane caveat below for why that is deliberate rather than
an omission.

IT IS THE TRACK GESTURE'S COMMAND, NOT THE ROI RUBBER-BAND'S, and this note
previously said otherwise. It carries ONE point (`x`, `y`), which the field
table above shows directly; a rubber-band rectangle is a `ser.JonGuiDataROI` on
the `cmd.{Day,Heat}Camera.{Focus,Track,Zoom,Fx}ROI` family and has two corners.
The retired sentence described this command as carrying "the two corner NDC
pairs", which conflated the two families — and that conflation is exactly where
the y-plane defect below came from, so it is recorded rather than quietly
replaced.

THE Y PLANE OF THIS COMMAND IS UNRESOLVED. This page states no sense and
`jon_shared_cmd_cv.proto` states none. The sentence that used to claim it shares
the pointer plane made the same claim of the ROI surface, where it was measured
false. A producer that pre-encodes a y slot for this command must therefore
state its `ui.NdcYSense` explicitly and cannot infer one; nothing in this
repository settles it, and settling it needs the device.



## Field Notes


### channel (#1)

Video channel selector


### x (#2)

X coordinate in NDC (-1.0 to 1.0)


### y (#3)

Y coordinate in NDC (-1.0 to 1.0)


### frame_time (#4)

Frame timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns


### state_time (#5)

State snapshot timestamp for synchronization


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns



