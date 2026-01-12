---
id: ser.JonGuiDataTime
proto: jon_shared_data_time.proto
package: ser
type: message
---

# JonGuiDataTime

**Source:** `jon_shared_data_time.proto`

## Description

Manages the device's current time state with support for both system and manually-set timestamps, allowing time zone context via zone_id while a boolean flag determines whether to use the manual override or system timestamp. Used throughout the frontend and backend to synchronize time-based operations across the device state distribution system.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | timestamp | int64 | >= 0 |
| 2 | manual_timestamp | int64 | >= 0 |
| 3 | zone_id | int32 | - |
| 4 | use_manual_time | bool | - |



## Interaction

- **Category:** :status


### Purpose

Displays system time and timezone configuration





### Implementation Notes

Read-only status information about system time



## Field Notes


### timestamp (#1)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds
- **Display Format:** `ISO 8601 datetime`


### manual_timestamp (#2)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds
- **Display Format:** `ISO 8601 datetime`


### zone_id (#3)


#### Metadata

- **Semantic Type:** :enum-label


### use_manual_time (#4)


#### Metadata

- **Semantic Type:** :raw



