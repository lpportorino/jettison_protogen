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

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Stop video recording


### Related State

- [[proto/proto/proto/ser.JonGuiDataSystemRecording]]


### Related Commands

- [[proto/proto/proto/cmd.System.StartRec]]
- [[proto/proto/proto/cmd.System.MarkRecImportant]]





