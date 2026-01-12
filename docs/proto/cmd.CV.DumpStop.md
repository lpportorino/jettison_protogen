---
id: cmd.CV.DumpStop
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# DumpStop

**Source:** `jon_shared_cmd_cv.proto`

## Description

Stops the computer vision frame dumping process that was previously initiated with DumpStart, ceasing the export of CV data to disk. Sets the cvDumping state to false when processed.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Stop dumping computer vision frames to disk



### Related Commands

- [[proto/proto/proto/proto/proto/cmd.CV.DumpStart]]



### Implementation Notes

Used for debugging CV algorithms



