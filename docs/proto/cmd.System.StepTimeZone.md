---
id: cmd.System.StepTimeZone
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StepTimeZone

**Source:** `jon_shared_cmd_system.proto`

## Description

Steps through available timezone options by a specified positive or negative index offset. The UI provides navigation buttons that cycle through the timezone list with offsets of -10, -1, +1, and +10 to select different timezones.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | offset | int32 | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Increment or decrement timezone offset





### Implementation Notes

Used with buttons for ±1 and ±10 hour adjustments



## Field Notes


### offset (#1)


#### Metadata

- **Semantic Type:** :raw



