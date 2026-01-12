---
id: cmd.Lrf.ScanOn
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# ScanOn

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

Enables continuous LRF scanning mode


### Related State

- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.Lrf.ScanOff]]
- [[proto/proto/cmd.Lrf.Measure]]


### Preconditions

- LRF must be started




