---
id: cmd.CV.RecognitionModeEnable
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# RecognitionModeEnable

**Source:** `jon_shared_cmd_cv.proto`

## Description

Enables AI-powered object recognition mode on the computer vision system, which activates detection and classification of objects in the video feed. UI displays a bracket icon with question mark and tooltip "Enable AI object recognition and tracking".

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Enables computer vision recognition mode


### Related State

- [[proto/ser.JonGuiDataSystem#recognition_mode]]


### Related Commands

- [[proto/cmd.CV.RecognitionModeDisable]]



### Implementation Notes

Implemented. This command is one pole of a generated toggle screen — the
Enable/Disable pair collapses into a single control whose two states send the
two commands.



