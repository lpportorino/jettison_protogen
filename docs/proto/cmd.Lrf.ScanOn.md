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
- Scanning mode should be inactive


### Implementation Notes

Frontend function `lrfScanOn()` in `cmdLRF.ts` sends this command. Forms a toggle pair with ScanOff for controlling continuous scanning mode. When enabled, the device performs repeated distance measurements at the configured scan rate (see SetScanMode). The `is_measuring` and `is_scanning` flags in LRF state reflect the current mode.


