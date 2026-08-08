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


### Related State

- [[proto/ser.JonGuiDataRotary#platform_bank]]


### Related Commands

- [[proto/cmd.RotaryPlatform.SetPlatformAzimuth]]
- [[proto/cmd.RotaryPlatform.SetPlatformElevation]]



### Implementation Notes

The `value` field is a bounded absolute angle (-180 to 180 degrees), so this command writes an absolute bank angle over its range rather than an incremental step. Although tagged `:stepper` for the fine-adjustment affordance, a bounded absolute `Set*` value of this shape is presented as a bounded absolute control (a `:slider` over its range), not a discrete stepper.



## Field Notes


### value (#1)

Angle value in degrees


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2



