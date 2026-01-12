---
id: cmd.RotaryPlatform.ScanSelectNode
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanSelectNode

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.ScanNext]]
- [[proto/proto/cmd.RotaryPlatform.ScanPrev]]





## Field Notes


### index (#1)


#### Metadata

- **Semantic Type:** :count
- **Unit:** index
- **Precision:** 0



