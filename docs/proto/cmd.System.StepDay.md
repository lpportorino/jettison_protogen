---
id: cmd.System.StepDay
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StepDay

**Source:** `jon_shared_cmd_system.proto`

## Description

Increments or decrements the day value of the manually-set system time by a signed integer offset. Positive values advance to future days, negative values go to previous days. The UI provides buttons for -5, -1, +1, and +5 day adjustments.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | offset | int32 | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Increments or decrements system day by offset


### Related State

- [[proto/ser.JonGuiDataTime#manual_timestamp]]


### Related Commands

- [[proto/cmd.System.StepMonth]]
- [[proto/cmd.System.StepYear]]
- [[proto/cmd.System.EnableManualTime]]


### Preconditions

- Manual time mode must be enabled




## Field Notes


### offset (#1)

Step offset value


#### Metadata

- **Semantic Type:** :count
- **Precision:** 0



