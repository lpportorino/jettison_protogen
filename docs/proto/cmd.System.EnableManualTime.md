---
id: cmd.System.EnableManualTime
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# EnableManualTime

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

Enables manual time mode instead of GPS time synchronization


### Related State

- [[proto/proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/proto/cmd.System.DisableManualTime]]
- [[proto/proto/cmd.System.StepYear]]
- [[proto/proto/cmd.System.StepMonth]]
- [[proto/proto/cmd.System.StepDay]]
- [[proto/proto/cmd.System.StepHour]]
- [[proto/proto/cmd.System.StepMinute]]
- [[proto/proto/cmd.System.StepSecond]]
- [[proto/proto/cmd.System.SyncBrowserTimeAndZone]]



### Implementation Notes

Allows manual time adjustment via stepper controls



