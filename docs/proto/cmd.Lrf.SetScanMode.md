---
id: cmd.Lrf.SetScanMode
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# SetScanMode

**Source:** `jon_shared_cmd_lrf.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataLrf]]




### Implementation Notes

Currently commented out in cmdLRF.ts - may be deprecated or not yet implemented



