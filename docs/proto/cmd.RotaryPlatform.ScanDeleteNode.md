---
id: cmd.RotaryPlatform.ScanDeleteNode
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanDeleteNode

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Deletes a waypoint from the rotary scan pattern at the specified index, updating the current scan node position after removal.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | index | int32 | >= 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Deletes a node from the scanning pattern


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.ScanAddNode]]
- [[proto/proto/cmd.RotaryPlatform.ScanUpdateNode]]
- [[proto/proto/cmd.RotaryPlatform.ScanRefreshNodeList]]


### Preconditions

- Scan pattern exists


### Implementation Notes

Part of scan pattern editor UI



## Field Notes


### index (#1)


#### Metadata

- **Semantic Type:** :count



