---
id: cmd.Compass.Next
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# Next

**Source:** `jon_shared_cmd_compass.proto`

## Description

**UNUSED/ORPHANED MESSAGE**: This message is defined in jon_shared_cmd_compass.proto but is NOT wired into the cmd.Compass.Root oneof structure. The actual command used to advance compass calibration stages is cmd.Compass.CalibrateNext. This message can likely be removed from the proto file as dead code.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Advance to next step in compass calibration sequence



### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.Compass.CalibrateStartLong]]
- [[proto/proto/proto/proto/proto/proto/cmd.Compass.CalibrateStartShort]]


### Preconditions

- Compass calibration must be in progress




