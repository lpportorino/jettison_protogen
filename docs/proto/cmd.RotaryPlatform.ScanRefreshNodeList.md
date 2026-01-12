---
id: cmd.RotaryPlatform.ScanRefreshNodeList
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanRefreshNodeList

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Refreshes scan node list from configuration


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.ScanStart]]
- [[proto/proto/cmd.RotaryPlatform.ScanSelectNode]]



### Implementation Notes

Updates scan waypoint list



