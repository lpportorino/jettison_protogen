---
id: cmd.HeatCamera.FocusStepPlus
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# FocusStepPlus

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

Steps thermal camera focus one increment forward (outward). Provides precise, incremental focus adjustments.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :actuator
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget

### Purpose

Steps the thermal camera focus one increment forward (outward). Each invocation moves the focus motor by a single discrete step.

### Related State

- [[ser.JonGuiDataCameraHeat]]

### Related Commands

- [[cmd.HeatCamera.FocusStepMinus]]
- [[cmd.HeatCamera.FocusIn]]
- [[cmd.HeatCamera.FocusOut]]

### Implementation Notes

This provides fine-grained focus control compared to continuous motion commands. Each button press moves the focus by exactly one step. Useful for implementing stepper buttons or fine-tuning focus position.



