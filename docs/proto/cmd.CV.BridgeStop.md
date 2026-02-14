---
id: cmd.CV.BridgeStop
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# BridgeStop

**Source:** `jon_shared_cmd_cv.proto`

## Description

Stops the CV Bridge Docker container.

Gracefully shuts down the CV Bridge container. The bridge_status field will transition to STOPPING, then to STOPPED once the container exits. The last_exit_reason will be set to NORMAL.

When the CV Bridge is stopped, fanout operates in bypass mode - state continues to flow but without CV enrichment (autofocus metrics will be stale/default).

## Interaction

| Property | Value |
|----------|-------|
| Category | `:lifecycle` |
| UI Pattern | `:action-button` |
| Feedback | `:pending-timeout` |
| Timeout | 5000ms |
| Related State | [[ser.JonGuiDataCV]] |
| Related Commands | [[cmd.CV.BridgeStart]], [[cmd.CV.BridgeRestart]] |
| Preconditions | CV Bridge must be running |

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | force | bool | - |

## Field Notes

### force

Controls the signal sent to terminate the container:
- `false` (default): Send SIGTERM for graceful shutdown. The bridge will complete in-flight operations and clean up resources before exiting.
- `true`: Send SIGKILL for immediate termination. Use when the container is unresponsive or stuck.

Graceful shutdown is preferred unless the bridge is not responding to normal stop requests.
