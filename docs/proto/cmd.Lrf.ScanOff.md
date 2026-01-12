---
id: cmd.Lrf.ScanOff
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# ScanOff

**Source:** `jon_shared_cmd_lrf.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Disables continuous LRF scanning mode


### Related State

- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.Lrf.ScanOn]]
- [[proto/proto/cmd.Lrf.Measure]]


### Preconditions

- LRF must be started




