---
id: cmd.CV.DumpStart
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# DumpStart

**Source:** `jon_shared_cmd_cv.proto`

## Description

Initiates recording of computer vision frame data to disk for debugging and analysis purposes. Only available in factory mode (URL parameter ui=factory). The state is tracked via data.System.cvDumping boolean field.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Start dumping computer vision frames to disk for debugging


### Related State

- [[proto/ser.JonGuiDataSystem#cv_dumping]]


### Related Commands

- [[proto/cmd.CV.DumpStop]]



### Implementation Notes

Used for debugging CV algorithms



