---
id: cmd.System.StartRec
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StartRec

**Source:** `jon_shared_cmd_system.proto`

## Description

Starts video recording.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Starts video recording on the system.

### Related State

- [[ser.JonGuiDataRecOsd]]

### Preconditions

- System must be ready for recording

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The button should be disabled while recording is active.

## Fields (Empty Message)

| # | Field | Type | Constraints |
|---|-------|------|-------------|



