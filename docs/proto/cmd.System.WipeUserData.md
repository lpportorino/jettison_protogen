---
id: cmd.System.WipeUserData
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# WipeUserData

**Source:** `jon_shared_cmd_system.proto`

## Description

*No description yet.*

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

- [[proto/proto/cmd.System.ResetConfigs]]
- [[proto/proto/cmd.System.SaveFactoryDefaults]]


### Preconditions

- User confirmation required


### Implementation Notes

Destructive operation, requires confirmation dialog in UI



