---
id: cmd.System.SetTimeAndZone
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# SetTimeAndZone

**Source:** `jon_shared_cmd_system.proto`

## Description

Atomically sets both the device's system timestamp and timezone in a single operation. Contains a 64-bit Unix timestamp (in nanoseconds) and a 32-bit timezone ID, primarily used when syncing the device's time with the browser's current time and locale.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | timestamp | int64 | >= 0 |
| 2 | zone_id | int32 | >= 0, < 595 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :tabbed-config
- **Feedback:** :fire-and-forget


### Purpose

Sets system time and timezone simultaneously


### Related State

- [[proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/cmd.System.EnableManualTime]]
- [[proto/cmd.System.StepTimeZone]]



### Implementation Notes

Combined time and timezone configuration command



## Field Notes


### timestamp (#1)

Monotonic timestamp in microseconds


#### Metadata

- **Semantic Type:** :timestamp
- **Display Format:** `{value}`


### zone_id (#2)

IANA timezone identifier


#### Metadata

- **Semantic Type:** :count
- **Display Format:** `Zone ID: {value}`



