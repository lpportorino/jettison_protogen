---
id: cmd.System.StopALl
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StopALl

**Source:** `jon_shared_cmd_system.proto`

## Description

Shuts down all active system subsystems including cameras, sensors, and platform components. Triggered from the UI via a "Stop All Systems" button, this command represents the counterpart to StartALl for cleanly halting all system operations.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Stops all system components and subsystems



### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.System.StartALl]]



### Implementation Notes

Mass stop command for all devices



