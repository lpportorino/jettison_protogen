---
id: cmd.Lrf.ScanOff
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# ScanOff

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Disables continuous LRF scanning mode by sending a stop command to the laser rangefinder device and clearing the scanning and measuring state flags.

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

- [[proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/cmd.Lrf.ScanOn]]
- [[proto/cmd.Lrf.Measure]]


### Preconditions

- LRF must be started
- Scanning mode should be active


### Implementation Notes

Frontend function `lrfScanOff()` in `cmdLRF.ts` sends this command. Forms a toggle pair with ScanOn for controlling continuous scanning mode. When disabled, the device stops sending repeated measurements and clears the measuring state flags.


