---
id: cmd.System.StepMinute
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StepMinute

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

Increments or decrements system time by minutes


### Related State

- [[proto/proto/ser.JonGuiDataSystem]]


### Related Commands

- [[proto/proto/cmd.System.SetTimeAndZone]]
- [[proto/proto/cmd.System.StepHour]]


### Preconditions

- Manual time mode must be enabled




## Field Notes


### offset (#1)


#### Metadata

- **Semantic Type:** :count
- **Unit:** minutes
- **Precision:** 0



