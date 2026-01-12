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


### Purpose

Disable computer vision stabilization mode


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCV]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.CV.StabilizationModeEnable]]



### Implementation Notes

Empty message - trigger only



