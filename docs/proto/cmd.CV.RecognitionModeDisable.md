---
id: cmd.CV.RecognitionModeDisable
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# RecognitionModeDisable

**Source:** `jon_shared_cmd_cv.proto`

## Description

Disables the AI-powered computer vision recognition mode, stopping automatic object detection and classification in the video feed. The backend sets cv.recognition_mode_enabled to false and state is reflected in system.recognitionMode.

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

Disables computer vision object recognition mode



### Related Commands

- [[proto/cmd.CV.RecognitionModeEnable]]



### Implementation Notes

Implemented in `jonCognitionButton.ts` as a toggle button that reads `system.recognitionMode` state and calls `recognitionModeDisable()` when recognition is currently enabled. Also accessible via keyboard shortcut `y > o > d` (System > Recognition Mode > Disable).



