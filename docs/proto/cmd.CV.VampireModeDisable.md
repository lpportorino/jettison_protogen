---
id: cmd.CV.VampireModeDisable
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# VampireModeDisable

**Source:** `jon_shared_cmd_cv.proto`

## Description

Disables vampire mode (sun avoidance) in the computer vision system, allowing cameras to look directly at bright light sources like the sun without automatic avoidance behavior. Paired with [[proto/cmd.CV.VampireModeEnable]]; the two back a single toggle rather than two independent buttons.

The readback is `vampire_mode` (#19) on [[proto/ser.JonGuiDataSystem]], which goes `false` once this command is applied. That is the only vampire-mode flag in the schema — [[proto/ser.JonGuiDataCV]] carries none, so a consumer reflecting this toggle reads the SYSTEM state message and not the CV one.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Disables vampire mode in computer vision



### Related Commands

- [[proto/cmd.CV.VampireModeEnable]]



### Implementation Notes

Not implemented in current frontend version



