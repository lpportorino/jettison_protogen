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


### Purpose

Disables computer vision object recognition mode



### Related Commands

- [[proto/proto/proto/cmd.CV.RecognitionModeEnable]]



### Implementation Notes

Stops CV-based object detection and recognition



