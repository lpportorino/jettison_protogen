---
id: cmd.Compass.SetUseRotaryPosition
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# SetUseRotaryPosition

**Source:** `jon_shared_cmd_compass.proto`

## Description

Configures whether to use the rotary platform's encoded position as the primary compass/orientation source instead of the physical compass sensor. When enabled, the system derives azimuth readings from the rotary platform's positional encoders rather than the magnetometer.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | flag | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Enable/disable using rotary platform position as compass source


### Related State

- [[proto/ser.JonGuiDataCompass]]
- [[proto/ser.JonGuiDataRotary#use_rotary_as_compass]]


### Related Commands

- [[proto/cmd.Compass.Start]]
- [[proto/cmd.Compass.Stop]]





## Field Notes


### flag (#1)

Enable/disable flag


#### Metadata

- **Semantic Type:** :toggle-state
- **Display Format:** `{value ? 'Use Rotary' : 'Use Compass'}`



