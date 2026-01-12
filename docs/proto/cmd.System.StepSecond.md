---
id: cmd.System.StepSecond
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StepSecond

**Source:** `jon_shared_cmd_system.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | offset | int32 | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Increments or decrements system time by seconds


### Related State

- [[proto/proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/proto/cmd.System.StepMinute]]
- [[proto/proto/cmd.System.StepHour]]
- [[proto/proto/cmd.System.EnableManualTime]]


### Preconditions

- Manual time mode should be enabled


### Implementation Notes

Used with stepper UI with -5/-1/+1/+5 buttons



## Field Notes


### offset (#1)


#### Metadata

- **Semantic Type:** :raw



