---
id: cmd.System.EnableManualTime
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# EnableManualTime

**Source:** `jon_shared_cmd_system.proto`

## Description

Switches the device from GPS-based time synchronization to manual time mode, allowing users to manually set and adjust the system time using step commands for individual time units (year, month, day, hour, minute, second).

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

- [[proto/proto/proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/proto/proto/cmd.System.DisableManualTime]]
- [[proto/proto/proto/cmd.System.StepYear]]
- [[proto/proto/proto/cmd.System.StepMonth]]
- [[proto/proto/proto/cmd.System.StepDay]]
- [[proto/proto/proto/cmd.System.StepHour]]
- [[proto/proto/proto/cmd.System.StepMinute]]
- [[proto/proto/proto/cmd.System.StepSecond]]
- [[proto/proto/proto/cmd.System.SyncBrowserTimeAndZone]]



### Implementation Notes

Allows manual time adjustment via stepper controls



