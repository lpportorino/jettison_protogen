---
id: cmd.Gps.GetMeteo
proto: jon_shared_cmd_gps.proto
package: cmd.Gps
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_gps.proto`

## Description

Requests meteorological and diagnostic data from the GPS module. This parameterless fire-and-forget command triggers the GPS system to return health metrics and environmental sensor readings via state updates.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Requests meteorological/diagnostic data from GPS module



### Related Commands

- [[proto/proto/proto/cmd.Compass.GetMeteo]]
- [[proto/proto/proto/cmd.HeatCamera.GetMeteo]]
- [[proto/proto/proto/cmd.RotaryPlatform.GetMeteo]]





