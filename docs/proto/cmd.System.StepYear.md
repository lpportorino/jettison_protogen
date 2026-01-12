---
id: cmd.System.StepYear
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StepYear

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

Increments or decrements system year by offset


### Related State

- [[proto/proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/proto/cmd.System.StepMonth]]
- [[proto/proto/cmd.System.StepDay]]
- [[proto/proto/cmd.System.EnableManualTime]]


### Preconditions

- Manual time mode must be enabled




## Field Notes


### offset (#1)


#### Metadata

- **Semantic Type:** :count
- **Precision:** 0



