---
id: cmd.CV.VampireModeDisable
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# VampireModeDisable

**Source:** `jon_shared_cmd_cv.proto`

## Description

Disables vampire mode (sun avoidance) in the computer vision system, allowing cameras to look directly at bright light sources like the sun without automatic avoidance behavior. Sets cv.vampire_mode_enabled to false in the backend state.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Disables vampire mode in computer vision


### Related State

- [[proto/ser.JonGuiDataSystem]]


### Related Commands

- [[proto/cmd.CV.VampireModeEnable]]


### Implementation Notes

Implemented in `jonVampireModeButton.ts` as a toggle button in the command palette. The button displays the current state from `ser.JonGuiDataSystem.vampire_mode` and toggles between Enable/Disable commands. Uses a 2000ms pending timeout for visual feedback while waiting for state confirmation.



