---
id: cmd.RotaryPlatform.SetPlatformBank
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# SetPlatformBank

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

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

- [[proto/proto/cmd.RotaryPlatform.SetPlatformAzimuth]]
- [[proto/proto/cmd.RotaryPlatform.SetPlatformElevation]]





## Field Notes


### value (#1)


#### Metadata

- **Semantic Type:** :angle
- **Unit:** degrees
- **Precision:** 2



