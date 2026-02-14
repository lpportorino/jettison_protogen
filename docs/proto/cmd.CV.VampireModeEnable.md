---
id: cmd.CV.VampireModeEnable
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# VampireModeEnable

**Source:** `jon_shared_cmd_cv.proto`

## Description

Enables vampire mode for the computer vision system, which causes the cameras to actively avoid looking at the sun to protect sensors and prevent image overexposure. When enabled, the system prevents cameras from pointing at bright light sources.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Enables sun avoidance behavior in the computer vision system to protect camera sensors from damage and prevent image overexposure when pointing at bright light sources.


### Related State

- [[proto/ser.JonGuiDataSystem]] - `vampireMode` boolean field reflects the current state


### Related Commands

- [[proto/cmd.CV.VampireModeDisable]]


### Preconditions

None - can be enabled at any time


### Implementation Notes

The frontend provides a toggle button (`jon-vampire-mode-button`) with keyboard shortcut `v` > `e`. The button shows pending state while waiting for confirmation from the backend. Currently marked as "NOT IMPLEMENTED" in some frontend code paths, indicating feature may not be fully deployed.



