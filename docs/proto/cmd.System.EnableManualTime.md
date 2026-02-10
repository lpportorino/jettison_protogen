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

- [[proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/cmd.System.DisableManualTime]]
- [[proto/cmd.System.StepYear]]
- [[proto/cmd.System.StepMonth]]
- [[proto/cmd.System.StepDay]]
- [[proto/cmd.System.StepHour]]
- [[proto/cmd.System.StepMinute]]
- [[proto/cmd.System.StepSecond]]
- [[proto/cmd.System.SyncBrowserTimeAndZone]]



### Implementation Notes

Allows manual time adjustment via stepper controls



