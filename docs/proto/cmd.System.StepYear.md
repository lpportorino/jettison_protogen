---
id: cmd.System.StepYear
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StepYear

**Source:** `jon_shared_cmd_system.proto`

## Description

Increments or decrements the system year value by a specified offset when in manual time mode. The UI provides buttons to adjust the year by -5, -1, +1, or +5 years through the Manual Time Control component.

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

- [[proto/proto/proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/proto/proto/cmd.System.StepMonth]]
- [[proto/proto/proto/cmd.System.StepDay]]
- [[proto/proto/proto/cmd.System.EnableManualTime]]


### Preconditions

- Manual time mode must be enabled




## Field Notes


### offset (#1)


#### Metadata

- **Semantic Type:** :count
- **Precision:** 0



