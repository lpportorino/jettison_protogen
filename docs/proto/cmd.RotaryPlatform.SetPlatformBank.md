---
id: cmd.RotaryPlatform.SetPlatformBank
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetPlatformBank

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Sets the rotary platform's roll/bank angle to a specific value between -180 and 180 degrees. This command adjusts the platform's rotation around its longitudinal axis, allowing it to tilt left or right independently of azimuth and elevation adjustments.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= -180, < 180 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :stepper
- **Feedback:** :fire-and-forget


### Purpose

Sets platform bank angle (roll) correction



### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.SetPlatformAzimuth]]
- [[proto/proto/proto/proto/proto/proto/cmd.RotaryPlatform.SetPlatformElevation]]





## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2



