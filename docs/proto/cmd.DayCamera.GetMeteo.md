---
id: cmd.DayCamera.GetMeteo
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :sensor
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Request meteorological data from day camera sensors


### Related State

- [[proto/proto/ser.JonGuiDataMeteo]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.GetMeteo]]
- [[proto/proto/cmd.Lrf.GetMeteo]]
- [[proto/proto/cmd.RotaryPlatform.GetMeteo]]



### Implementation Notes

Polling command - retrieves environmental sensor data



