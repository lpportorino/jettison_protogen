---
id: cmd.System.EnterTransport
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# EnterTransport

**Source:** `jon_shared_cmd_system.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Enter transport/storage mode - safely prepares system for transport


### Related State

- [[proto/proto/ser.JonGuiDataSystem]]




### Implementation Notes

Lifecycle transition - typically stops all subsystems and powers down safely



