---
id: cmd.RotaryPlatform.HaltWithNDC
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# HaltWithNDC

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Halts all rotary platform motion and records the final normalized device coordinates (NDC x, y) where the pan gesture ended, along with video frame and system monotonic timestamps for precise position tracking.

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

Halts platform movement at specified normalized device coordinates in video frame


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraDay]]
- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.RotateToNDC]]
- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.Halt]]


### Preconditions

- Frame data must be available


### Implementation Notes

Requires frame timestamp and system monotonic time for synchronization



## Field Notes


### channel (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `Channel: {value}`


### x (#2)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC
- **Precision:** 3
- **Display Format:** `x: {value}`


### y (#3)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** NDC
- **Precision:** 3
- **Display Format:** `y: {value}`


### frame_time (#4)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** ns
- **Display Format:** `{value} ns`


### state_time (#5)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** us
- **Display Format:** `{value} μs`



