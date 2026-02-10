---
id: cmd.System.UnmarkRecImportant
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# UnmarkRecImportant

**Source:** `jon_shared_cmd_system.proto`

## Description

Removes the important flag from the current recording, allowing it to be treated as a normal recording. This command is invoked through the UI when users toggle off the "Mark as Important" button, working in tandem with MarkRecImportant to manage recording importance state.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Unmarks current recording as important


### Related State

- [[proto/ser.JonGuiDataSystem]]


### Related Commands

- [[proto/cmd.System.MarkRecImportant]]





