---
id: cmd.System.WipeUserData
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# WipeUserData

**Source:** `jon_shared_cmd_system.proto`

## Description

Permanently deletes all user data from the device, including all photos, videos, recordings, and custom settings. Requires explicit user confirmation through a modal dialog before execution due to the irreversible nature of the operation.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Wipes all user data from the system (factory reset)



### Related Commands

- [[proto/proto/proto/proto/proto/cmd.System.ResetConfigs]]
- [[proto/proto/proto/proto/proto/cmd.System.SaveFactoryDefaults]]


### Preconditions

- User confirmation required


### Implementation Notes

Destructive operation, requires confirmation dialog in UI



