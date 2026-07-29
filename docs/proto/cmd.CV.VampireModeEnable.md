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

Enables vampire mode for computer vision processing


### Related State

- [[proto/ser.JonGuiDataSystem]]


### Related Commands

- [[proto/cmd.CV.VampireModeDisable]]



### Implementation Notes

The readback is `vampire_mode` (#19) on [[proto/ser.JonGuiDataSystem]], which goes `true` once this command is applied; [[proto/ser.JonGuiDataCV]] carries no vampire-mode flag, so the toggle reflects off the SYSTEM state message.



