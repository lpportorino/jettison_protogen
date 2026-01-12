---
id: cmd.Noop
proto: jon_shared_cmd.proto
package: cmd
type: message
---

# Noop

**Source:** `jon_shared_cmd.proto`

## Description

A no-operation command used as a placeholder in the command protocol payload; allows clients to send a valid command message without triggering any action on the device or system.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

No operation command for testing/keepalive





### Implementation Notes

Used for protocol testing and connection verification



