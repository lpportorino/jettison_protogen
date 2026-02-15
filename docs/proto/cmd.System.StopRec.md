---
id: cmd.System.StopRec
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StopRec

**Source:** `jon_shared_cmd_system.proto`

## Description

Instructs the device to immediately stop video recording. When received by the recording subsystem, this command ceases capture of thermal and day camera video data and finalizes the current recording file.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stop video recording


### Related State

- [[proto/ser.JonGuiDataRecOsd]]


### Related Commands

- [[proto/cmd.System.StartRec]]
- [[proto/cmd.System.MarkRecImportant]]





