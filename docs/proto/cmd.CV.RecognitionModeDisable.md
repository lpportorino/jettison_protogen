---
id: cmd.CV.RecognitionModeDisable
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# RecognitionModeDisable

**Source:** `jon_shared_cmd_cv.proto`

## Description

Disables the AI-powered computer vision recognition mode, stopping automatic object detection and classification in the video feed. Paired with [[proto/cmd.CV.RecognitionModeEnable]]; the two back a single toggle rather than two independent buttons.

The readback is `recognition_mode` (#23) on [[proto/ser.JonGuiDataSystem]], which goes `false` once this command is applied. That is the only recognition flag in the schema — [[proto/ser.JonGuiDataCV]] carries none, so a consumer reflecting this toggle reads the SYSTEM state message and not the CV one.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Disables computer vision object recognition mode



### Related Commands

- [[proto/cmd.CV.RecognitionModeEnable]]



### Implementation Notes

Stops CV-based object detection and recognition



