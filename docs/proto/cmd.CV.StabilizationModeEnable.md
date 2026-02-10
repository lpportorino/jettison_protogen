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
- **Feedback:** :fire-and-forget


### Purpose

Enables computer vision stabilization mode to reduce camera shake



### Related Commands

- [[proto/cmd.CV.StabilizationModeDisable]]



### Implementation Notes

Enables CV-based image stabilization



