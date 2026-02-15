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

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | force | bool | - |




