---
id: cmd.System.PowerOff
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# PowerOff

**Source:** `jon_shared_cmd_system.proto`

## Description

Triggers a controlled system shutdown sequence by creating a power-off flag that initiates the shutdown process. The frontend displays a confirmation dialog and monitors server disconnect to verify the system has powered down completely.

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



