---
id: cmd.RotaryPlatform.setUseRotaryAsCompass
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# setUseRotaryAsCompass

**Source:** `jon_shared_cmd_rotary.proto`

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

Enables or disables using rotary platform position as compass heading


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.Compass.SetUseRotaryPosition]]



### Implementation Notes

Configuration toggle for compass data source



## Field Notes


### flag (#1)


#### Metadata

- **Semantic Type:** :raw



