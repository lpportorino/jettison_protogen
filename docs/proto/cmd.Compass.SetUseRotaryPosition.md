---
id: cmd.Compass.SetUseRotaryPosition
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# SetUseRotaryPosition

**Source:** `jon_shared_cmd_compass.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCompass]]
- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.Compass.Start]]
- [[proto/proto/cmd.Compass.Stop]]





## Field Notes


### flag (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `{value ? &amp;#39;Use Rotary&amp;#39; : &amp;#39;Use Compass&amp;#39;}`



