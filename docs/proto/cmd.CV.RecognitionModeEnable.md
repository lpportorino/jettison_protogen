---
id: cmd.CV.RecognitionModeEnable
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# RecognitionModeEnable

**Source:** `jon_shared_cmd_cv.proto`

## Description

Enables AI-powered object recognition mode on the computer vision system, activating automatic detection and classification of objects in the video feed. Sets `system.recognitionMode` to true in system state. UI renders a toggle button with bracket-and-question-mark icon; tooltip reads "Start Recognition - Enable AI object recognition and tracking".

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget

### Related State

- [[proto/ser.JonGuiDataSystem]] - `recognitionMode` field reflects current state

### Purpose

Activates CV-based object recognition and detection capabilities



### Related Commands

- [[proto/cmd.CV.RecognitionModeDisable]]



### Implementation Notes

Implemented in `jonCognitionButton.ts` as a toggle button that reads `system.recognitionMode` state and calls `recognitionModeEnable()` when recognition is currently disabled. Also accessible via keyboard shortcut `y > o > e` (System > Recognition Mode > Enable).



