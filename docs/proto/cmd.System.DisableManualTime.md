---
id: cmd.System.DisableManualTime
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# DisableManualTime

**Source:** `jon_shared_cmd_system.proto`

## Description

Disables manual time mode and returns the device to automatically using GPS time instead of a manually-set timestamp. When triggered via the Manual Time Control UI toggle, it sets `use_manual_time` to false, allowing the device to synchronize with GPS time.

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

- [[proto/proto/proto/ser.JonGuiDataSystem]]
- [[proto/proto/proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/proto/proto/cmd.System.EnableManualTime]]



### Implementation Notes

Used in time configuration UI to switch between manual and automatic time



