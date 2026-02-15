---
id: cmd.System.StepMinute
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StepMinute

**Source:** `jon_shared_cmd_system.proto`

## Description

Increments or decrements the device's manual time minute value by the specified offset. Positive values increment and negative values decrement the minute. The UI provides buttons for -5, -1, +1, and +5 minute adjustments.

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

- [[proto/ser.JonGuiDataTime]]


### Related Commands

- [[proto/cmd.System.SetTimeAndZone]]
- [[proto/cmd.System.StepHour]]


### Preconditions

- Manual time mode must be enabled




## Field Notes


### offset (#1)

Step offset value


#### Metadata

- **Semantic Type:** :count
- **Unit:** minutes
- **Precision:** 0



