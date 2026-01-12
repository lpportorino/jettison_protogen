---
id: cmd.RotaryPlatform.GetMeteo
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_rotary.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :sensor
- **UI Pattern:** :action-button
- **Feedback:** :poll-confirm


### Purpose

Requests meteorological data from rotary platform sensors


### Related State

- [[proto/proto/ser.JonGuiDataRotary]]


### Related Commands

- [[proto/proto/cmd.Compass.GetMeteo]]


### Preconditions



### Implementation Notes

No parameters. Response expected via state message.



