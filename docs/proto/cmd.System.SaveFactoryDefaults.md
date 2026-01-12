---
id: cmd.System.SaveFactoryDefaults
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# SaveFactoryDefaults

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

Saves current configuration as factory defaults


### Related State

- [[proto/proto/ser.JonGuiDataSystem]]



### Preconditions



### Implementation Notes

Uses jonSaveFactoryDefaultsButton component. Typically requires user confirmation due to critical nature of operation.



