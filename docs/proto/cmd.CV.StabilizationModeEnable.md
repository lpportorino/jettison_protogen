---
id: cmd.CV.StabilizationModeEnable
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# StabilizationModeEnable

**Source:** `jon_shared_cmd_cv.proto`

## Description

Enables computer vision-based image stabilization to reduce camera shake and vibration in the video feed. The system applies real-time stabilization algorithms to compensate for camera movement, providing steadier video output.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Enables computer vision stabilization mode to reduce camera shake and vibration in the video feed


### Related State

- [[proto/ser.JonGuiDataSystem]] (stabilization_mode boolean field)


### Related Commands

- [[proto/cmd.CV.StabilizationModeDisable]]


### Implementation Notes

Empty message - trigger only. The frontend implements this as a toggle button that pairs with StabilizationModeDisable. The button tracks pending state and clears it either when the system state confirms the change or after a 2000ms timeout. Accessible via keyboard shortcut: System > Stabilization > Enable (y > s > e). Tooltip when stabilization is disabled: "Enable Image Stabilization - reduces camera shake and vibration"



