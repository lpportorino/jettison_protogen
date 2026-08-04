---
id: cmd.RotaryPlatform.RotateAzimuth
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# RotateAzimuth

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Continuously rotates the azimuth axis at a specified speed in a specified direction (clockwise or counter-clockwise). This command initiates ongoing rotation until halted by a separate halt command.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | speed | double | >= 0, <= 1 |
| 2 | direction | [[proto/ser.JonGuiDataRotaryDirection]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :directional-mover
- **Feedback:** :fire-and-forget


### Purpose

Continuously rotate azimuth axis at specified speed and direction



### Related Commands

- [[proto/cmd.RotaryPlatform.HaltAzimuth]]
- [[proto/cmd.RotaryPlatform.RotateAzimuthTo]]





## Field Notes


### speed (#1)

Movement speed (0.0=stopped, 1.0=maximum)


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value * 100}%`


### direction (#2)

Rotation direction


#### Metadata

- **Semantic Type:** :enum-label



