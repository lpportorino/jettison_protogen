---
id: cmd.System.MarkRecImportant
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# MarkRecImportant

**Source:** `jon_shared_cmd_system.proto`

## Description

Marks the currently active recording as important by toggling an `importantRecEnabled` flag on the device state. This indicates to the system that the current video/recording session should be flagged for preservation or special handling.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Marks the current recording as important for preservation


### Related State

- [[proto/ser.JonGuiDataSystem#important_rec_enabled]]


### Related Commands

- [[proto/cmd.System.UnmarkRecImportant]]



### Implementation Notes

Toggle button with visual indicator for important recording flag



