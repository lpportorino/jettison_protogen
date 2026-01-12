---
id: ser.JonGuiDataDayCamGlassHeater
proto: jon_shared_data_day_cam_glass_heater.proto
package: ser
type: message
---

# JonGuiDataDayCamGlassHeater

**Source:** `jon_shared_data_day_cam_glass_heater.proto`

## Description

Represents the operational state of the day camera's glass heater, which maintains camera lens temperature to prevent fogging and ice formation. Contains a temperature reading, on/off status, and activation state flag.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | temperature | double | >= -273.15, <= 660.32 |
| 2 | status | bool | - |
| 3 | is_started | bool | - |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Glass heater status for day camera (prevents fogging and ice)



### Related Commands

- [[proto/proto/proto/cmd.DayCamera.GlassHeater.TurnOn]]
- [[proto/proto/proto/cmd.DayCamera.GlassHeater.TurnOff]]



### Implementation Notes

Toggle button in command palette with heater icon, 2 second pending timeout



## Field Notes


### temperature (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `Boolean status (on/off)`



