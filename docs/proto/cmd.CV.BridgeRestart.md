---
id: cmd.CV.BridgeRestart
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# BridgeRestart

**Source:** `jon_shared_cmd_cv.proto`

## Description

Restarts the CV Bridge Docker container.

Performs a stop followed by start of the CV Bridge container. The bridge_status will transition through STOPPING → STOPPED → STARTING → RUNNING. The restart_count will be incremented.

Use this command to recover from errors or apply configuration changes that require a full restart.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | force | bool | - |

## Interaction

| Property | Value |
|----------|-------|
| Category | `:lifecycle` |
| UI Pattern | `:action-button` |
| Feedback | `:pending-timeout` |
| Timeout | 10000ms |
| Related State | [[proto/ser.JonGuiDataCV]] |
| Related Commands | [[proto/cmd.CV.BridgeStart]], [[proto/cmd.CV.BridgeStop]] |

## Field Notes

### force (#1)

When true, forcefully terminates the CV Bridge container without waiting for graceful shutdown. Use when the bridge is unresponsive or stuck in an error state. When false (default), performs a graceful stop followed by start.

