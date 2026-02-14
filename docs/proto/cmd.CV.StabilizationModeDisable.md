---
id: cmd.CV.StabilizationModeDisable
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# StabilizationModeDisable

**Source:** `jon_shared_cmd_cv.proto`

## Description

Disables the computer vision image stabilization mode, allowing the camera to respond freely to manual movement instead of compensating for shake and vibration. Tooltip: "Disable Image Stabilization - allows manual camera movement".

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Disables computer vision stabilization mode, allowing raw camera movement without shake compensation


### Related State

- [[proto/ser.JonGuiDataSystem]] (stabilization_mode boolean field)


### Related Commands

- [[proto/cmd.CV.StabilizationModeEnable]]


### Implementation Notes

Empty message - trigger only. The frontend implements this as a toggle button that pairs with StabilizationModeEnable. The button tracks pending state and clears it either when the system state confirms the change or after a 2000ms timeout. Accessible via keyboard shortcut: System > Stabilization > Disable (y > s > d). Tooltip when stabilization is enabled: "Disable Image Stabilization - allows manual camera movement"



