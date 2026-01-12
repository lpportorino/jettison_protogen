---
id: cmd.CV.StopTrack
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# StopTrack

**Source:** `jon_shared_cmd_cv.proto`

## Description

Stops active video tracking on both day and thermal cameras. When sent from the frontend, it is forwarded to both pipeline command channels to terminate automatic target following. The tracking button (with corner brackets icon) only appears when system.tracking is true.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops active video tracking



### Related Commands

- [[proto/proto/cmd.CV.StartTrackNDC]]



### Implementation Notes

Not implemented in current frontend version



