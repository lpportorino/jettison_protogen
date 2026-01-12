---
id: cmd.RotaryPlatform.ScanRefreshNodeList
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanRefreshNodeList

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Triggers the server to refresh and resynchronize the current scanning pattern node list. The server updates its internal state and sends back signal notifications with the updated scan node information.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanStart]]
- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.ScanSelectNode]]



### Implementation Notes

Updates scan waypoint list



