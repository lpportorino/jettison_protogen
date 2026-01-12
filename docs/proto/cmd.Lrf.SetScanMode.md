---
id: cmd.Lrf.SetScanMode
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# SetScanMode

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Configures the scanning frequency mode of the Laser Rangefinder device, allowing selection between predefined continuous scan rates ranging from 1 Hz to 200 Hz.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | mode | [[proto/ser.JonGuiDataLrfScanModes]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :fire-and-forget


### Purpose

Set LRF scan mode


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataLrf]]




### Implementation Notes

Currently commented out in cmdLRF.ts - may be deprecated or not yet implemented



