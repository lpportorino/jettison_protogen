---
id: cmd.System.ResetConfigs
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# ResetConfigs

**Source:** `jon_shared_cmd_system.proto`

## Description

Resets all device configurations to their default values. The command prompts for user confirmation to prevent accidental resets, and causes the system to reload with factory default settings after a server restart.

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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataSystem]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.System.SaveFactoryDefaults]]
- [[proto/proto/proto/proto/proto/proto/cmd.System.WipeUserData]]



### Implementation Notes

Destructive operation - should have confirmation prompt



