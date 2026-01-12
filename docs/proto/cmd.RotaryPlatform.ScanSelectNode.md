---
id: cmd.RotaryPlatform.ScanSelectNode
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanSelectNode

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Commands the rotary platform to select a specific scan waypoint by its index. When triggered from the UI (typically after adding or deleting nodes), it updates the backend state to reflect which waypoint is currently selected, enabling synchronization between the UI node list and server-side scan management.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | index | int32 | >= 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :enum-picker
- **Feedback:** :fire-and-forget


### Purpose

Selects specific scan waypoint by index


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanNext]]
- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanPrev]]





## Field Notes


### index (#1)


#### Metadata

- **Semantic Type:** :count
- **Unit:** index
- **Precision:** 0



