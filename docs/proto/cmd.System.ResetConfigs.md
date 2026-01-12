---
id: cmd.System.ResetConfigs
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# ResetConfigs

**Source:** `jon_shared_cmd_system.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Reset all system configurations to defaults


### Related State

- [[proto/proto/ser.JonGuiDataSystem]]


### Related Commands

- [[proto/proto/cmd.System.SaveFactoryDefaults]]
- [[proto/proto/cmd.System.WipeUserData]]



### Implementation Notes

Destructive operation - should have confirmation prompt



