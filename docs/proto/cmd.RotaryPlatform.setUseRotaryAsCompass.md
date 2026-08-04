---
id: cmd.RotaryPlatform.setUseRotaryAsCompass
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# setUseRotaryAsCompass

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Toggles whether the rotary platform's position readings are used as the primary compass heading source. When enabled, the platform's orientation is stored in the `use_platform_positioning` state flag and used by the rotary subsystem to determine heading.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | flag | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Enables or disables using rotary platform position as compass heading


### Related State

- [[proto/ser.JonGuiDataRotary#use_rotary_as_compass]]


### Related Commands

- [[proto/cmd.Compass.SetUseRotaryPosition]]



### Implementation Notes

Configuration toggle for compass data source



## Field Notes


### flag (#1)

Enable/disable flag


#### Metadata

- **Semantic Type:** :raw



