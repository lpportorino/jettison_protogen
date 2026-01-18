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

### force (field 1)

If `true`, uses SIGKILL to immediately terminate the container before restarting. Use when the container is unresponsive. If `false`, waits for graceful shutdown before starting a new instance.




