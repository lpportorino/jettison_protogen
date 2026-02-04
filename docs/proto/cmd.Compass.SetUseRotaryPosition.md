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

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataCompass]]
- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.Compass.Start]]
- [[proto/proto/proto/proto/proto/proto/cmd.Compass.Stop]]





## Field Notes


### flag (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `{value ? &amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;#39;Use Rotary&amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;#39; : &amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;#39;Use Compass&amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;amp;#39;}`



