---
id: cmd.System.SetTimeZone
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# SetTimeZone

**Source:** `jon_shared_cmd_system.proto`

## Description

Sets the device's timezone using a numeric zone ID (0-594) that maps to standard IANA timezone names (e.g., America/New_York, UTC). The timezone ID is used to update all subsequent time display calculations.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | zone_id | int32 | >= 0, < 595 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout


### Purpose

Sets the system timezone


### Related State

- [[proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/cmd.System.StepTimeZone]]
- [[proto/cmd.System.SetTimeAndZone]]



### Implementation Notes

Uses numeric zone ID from timezone database



## Field Notes


### zone_id (#1)

IANA timezone identifier


#### Metadata

- **Semantic Type:** :count



