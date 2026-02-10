---
id: cmd.System.StepHour
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StepHour

**Source:** `jon_shared_cmd_system.proto`

## Description

Adjusts the manual system time by incrementing or decrementing the hour value using a positive or negative offset. The UI provides arrow buttons for -5, -1, +1, and +5 hour adjustments when manual time mode is enabled.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | offset | int32 | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Adjust system clock hour by specified offset


### Related State

- [[proto/ser.JonGuiDataSystemTime]]


### Related Commands

- [[proto/cmd.System.StepMinute]]
- [[proto/cmd.System.StepSecond]]
- [[proto/cmd.System.StepDay]]
- [[proto/cmd.System.SetTimeAndZone]]


### Preconditions

- Manual time mode must be enabled




## Field Notes


### offset (#1)


#### Metadata

- **Semantic Type:** :raw
- **Display Format:** `Hour offset (integer)`



