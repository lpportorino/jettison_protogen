---
id: cmd.System.SetTimeAndZone
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# SetTimeAndZone

**Source:** `jon_shared_cmd_system.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/proto/cmd.System.EnableManualTime]]
- [[proto/proto/cmd.System.SyncBrowserTimeAndZone]]
- [[proto/proto/cmd.System.StepTimeZone]]



### Implementation Notes

Combined time and timezone configuration command



## Field Notes


### timestamp (#1)


#### Metadata

- **Semantic Type:** :timestamp
- **Display Format:** `{value}`


### zone_id (#2)


#### Metadata

- **Semantic Type:** :count
- **Display Format:** `Zone ID: {value}`



