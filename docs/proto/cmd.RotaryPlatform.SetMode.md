---
id: cmd.RotaryPlatform.SetMode
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetMode

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | mode | [[proto/ser.JonGuiDataRotaryMode]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :pending-timeout


### Purpose

Set rotary platform operational mode (speed/position/stabilization/targeting/tracking)


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.RotaryPlatform.Start]]
- [[proto/proto/cmd.RotaryPlatform.Halt]]


### Preconditions

- Rotary platform must be started




## Field Notes


### mode (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `{mode}`
- **Presets:** INITIALIZATION, SPEED, POSITION, STABILIZATION, TARGETING, VIDEO_TRACKER



