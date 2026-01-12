---
id: cmd.System.StepMonth
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StepMonth

**Source:** `jon_shared_cmd_system.proto`

## Description

Adjusts the manually set time by incrementing or decrementing the month value by a specified offset. The UI provides arrow buttons to step the month forward or backward by 1 or 5 units at a time when manual time mode is enabled.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | offset | int32 | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Increments or decrements system date by months


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataSystem]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.System.SetTimeAndZone]]
- [[proto/proto/proto/proto/proto/proto/cmd.System.StepYear]]


### Preconditions

- Manual time mode must be enabled




## Field Notes


### offset (#1)


#### Metadata

- **Semantic Type:** :count
- **Unit:** months
- **Precision:** 0



