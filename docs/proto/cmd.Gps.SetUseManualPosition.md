---
id: cmd.Gps.SetUseManualPosition
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# SetUseManualPosition

**Source:** `jon_shared_cmd_gps.proto`

## Description

Toggles between GPS-based and manual position entry modes. When the flag is true, the system uses the manually configured position (set via SetManualPosition); when false, it uses live GPS coordinates from the receiver.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | flag | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Toggle between GPS-based and manual position entry


### Related State

- [[proto/ser.JonGuiDataGps#use_manual]]


### Related Commands

- [[proto/cmd.Gps.SetManualPosition]]





## Field Notes


### flag (#1)

Enable/disable flag


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `Boolean flag (true = use manual)`



