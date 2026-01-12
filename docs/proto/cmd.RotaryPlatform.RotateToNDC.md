---
id: cmd.RotaryPlatform.RotateToNDC
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateToNDC

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Commands the rotary platform to rotate and point toward a specific normalized device coordinate (NDC) location in a video frame, with synchronized timestamps from both the video frame and system state.

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
- **Feedback:** :poll-confirm


### Purpose

Rotate rotary platform to aim at Normalized Device Coordinates (NDC) on video frame


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.HaltWithNDC]]


### Preconditions

- Video frame data must be available
- System monotonic time must be synced


### Implementation Notes

Uses frame timestamps and state time for synchronization. NDC coordinates are normalized to [-1, 1] range.



## Field Notes


### channel (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `Video channel (day/heat)`


### x (#2)


#### Metadata

- **Semantic Type:** :normalized
- **Display Format:** `X coordinate`


### y (#3)


#### Metadata

- **Semantic Type:** :normalized
- **Display Format:** `Y coordinate`



