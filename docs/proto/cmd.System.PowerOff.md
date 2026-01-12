---
id: cmd.System.PowerOff
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# PowerOff

**Source:** `jon_shared_cmd_system.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Initiates full system shutdown




### Preconditions

- User confirmation required


### Implementation Notes

Requires modal confirmation, monitors server disconnect to verify shutdown



