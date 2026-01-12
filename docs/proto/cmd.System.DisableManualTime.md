---
id: cmd.System.DisableManualTime
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# DisableManualTime

**Source:** `jon_shared_cmd_system.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Disables manual time control and reverts to system time


### Related State

- [[proto/proto/ser.JonGuiDataSystem]]
- [[proto/proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/proto/cmd.System.EnableManualTime]]



### Implementation Notes

Used in time configuration UI to switch between manual and automatic time



