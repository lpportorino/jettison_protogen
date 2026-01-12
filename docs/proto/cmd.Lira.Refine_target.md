---
id: cmd.Lira.Refine_target
proto: jon_shared_cmd_lira.proto
package: cmd.Lira
type: message
---

# Refine_target

**Source:** `jon_shared_cmd_lira.proto`

## Description

Updates target tracking coordinates by accepting a refined target location with GPS coordinates (latitude, longitude, altitude), azimuth/elevation angles, distance, and UUID to store in the system's last_target tracking state.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target | [[proto/cmd.Lira.JonGuiDataLiraTarget]] | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Refines target positioning using LIRA rangefinding data


### Related State

- [[proto/proto/proto/proto/proto/ser.JonGuiDataLira]]



### Preconditions

- LIRA subsystem must be active
- Valid target data available


### Implementation Notes

Complex command with geographic coordinates, angles, and UUID for target tracking.



## Field Notes


### target (#1)


#### Metadata

- **Semantic Type:** :timestamp
- **Unit:** microseconds



