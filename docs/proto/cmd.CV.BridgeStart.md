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

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|




