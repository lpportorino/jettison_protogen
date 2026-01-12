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

- [[proto/proto/ser.JonGuiDataSystem]]


### Related Commands

- [[proto/proto/cmd.CV.VampireModeDisable]]


### Preconditions



### Implementation Notes

Vampire mode likely relates to low-light or nighttime CV processing optimization.



