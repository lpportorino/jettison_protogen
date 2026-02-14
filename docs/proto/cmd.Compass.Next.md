---
id: cmd.Compass.Next
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# Next

**Source:** `jon_shared_cmd_compass.proto`

## Description

**UNUSED/ORPHANED MESSAGE**: This message is defined in `jon_shared_cmd_compass.proto` but is NOT wired into the `cmd.Compass.Root` oneof structure. The actual command used to advance compass calibration stages is [[proto/cmd.Compass.CalibrateNext]]. This message can likely be removed from the proto file as dead code.

<!-- NEEDS_REVIEW: Verify with backend team whether this message was intentionally left orphaned or should be removed from the proto definition -->

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

*No fields - empty message.*

## Interaction

- **Category:** :deprecated
- **UI Pattern:** N/A (not wired to Root oneof)
- **Feedback:** N/A

### Purpose

Originally intended to advance to the next step in a compass calibration sequence, but this functionality is implemented by [[proto/cmd.Compass.CalibrateNext]] instead.

### Related State

- [[proto/ser.JonGuiDataCompassCalibration]] (for reference only - this message cannot affect state)

### Related Commands

- [[proto/cmd.Compass.CalibrateNext]] - The active command that performs this function
- [[proto/cmd.Compass.CalibrateStartLong]]
- [[proto/cmd.Compass.CalibrateStartShort]]

### Preconditions

N/A - This message cannot be sent as it is not wired into the command routing.

## Field Notes

*No fields to document.*




