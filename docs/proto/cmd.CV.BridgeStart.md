---
id: cmd.CV.BridgeStart
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# BridgeStart

**Source:** `jon_shared_cmd_cv.proto`

## Description

Starts the CV Bridge Docker container.

The CV Bridge is an isolated Docker container running the CV Gateway application that:
- Consumes CUDA IPC frames from pipeline_day and pipeline_heat
- Computes autofocus sharpness metrics using CUDA kernels
- Controls camera lens focus via CAN bus
- Reports status back to fanout for state enrichment

If the container is already running, this command has no effect. The bridge_status field in JonGuiDataCV will transition from STOPPED to STARTING, then to RUNNING once initialized.

## Interaction

| Property | Value |
|----------|-------|
| Category | `:lifecycle` |
| UI Pattern | `:action-button` |
| Feedback | `:pending-timeout` |
| Timeout | 5000ms |
| Related State | [[proto/ser.JonGuiDataCV]] (`bridge_status`, `last_exit_reason`, `bridge_uptime_ms`) |
| Related Commands | [[proto/cmd.CV.BridgeStop]], [[proto/cmd.CV.BridgeRestart]] |

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

*This message has no fields.*

## Notes

- This is a lifecycle command that starts the CV Bridge (bezoar) Docker container
- The command is idempotent - sending it when the bridge is already running has no effect
- Monitor `cv.bridge_status` in state to track startup progress: `STOPPED` -> `STARTING` -> `RUNNING`
- If startup fails, check `cv.last_exit_reason` for diagnostic information
- The bridge is critical for state enrichment and autofocus functionality; pipelines may operate in degraded mode without it
