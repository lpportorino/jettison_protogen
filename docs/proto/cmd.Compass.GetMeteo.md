---
id: cmd.Compass.GetMeteo
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# GetMeteo

**Source:** `jon_shared_cmd_compass.proto`

## Description

Requests meteorological sensor data (temperature, humidity, pressure) from the compass module's environmental sensors. This command is periodically requested by a system timer (every 600ms) rather than being triggered by user interaction, allowing continuous monitoring of environmental conditions.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Requests meteorological data from compass sensor


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataCompass]]




### Implementation Notes

Queries compass for environmental sensor readings (temperature, pressure, etc.)



