---
id: cmd.Lrf.ScanOn
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# ScanOn

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Initiates continuous laser rangefinder (LRF) scanning mode, allowing the device to perform repeated distance measurements in a scan pattern until the scan is stopped.

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

- [[proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/cmd.Lrf.ScanOff]]
- [[proto/cmd.Lrf.Measure]]


### Preconditions

- LRF must be started




