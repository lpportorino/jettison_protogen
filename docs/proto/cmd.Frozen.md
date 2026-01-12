---
id: cmd.Frozen
proto: jon_shared_cmd.proto
package: cmd
type: message
---

# Frozen

**Source:** `jon_shared_cmd.proto`

## Description

A diagnostic command message used for debug/test purposes to trigger or test the frozen state of the system. This parameterless command is allowed in readonly mode and is sent without buffering alongside ping messages for system testing.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Debug/test command for frozen state







