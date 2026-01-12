---
id: cmd.System.StopRec
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StopRec

**Source:** `jon_shared_cmd_system.proto`

## Description

Stops video recording.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Stops video recording on the system.

### Related State

- [[ser.JonGuiDataRecOsd]]

### Preconditions

- Recording must be active

### Implementation Notes

Expect state confirmation within ~500ms. Implement 2s timeout for pending indicator. The button should be disabled while recording is not active.

## Fields (Empty Message)

| # | Field | Type | Constraints |
|---|-------|------|-------------|



