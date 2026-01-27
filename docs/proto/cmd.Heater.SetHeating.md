---
id: cmd.Heater.SetHeating
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# SetHeating

**Source:** `jon_shared_cmd_heater.proto`

## Description

Sets target temperatures and acceptable error margins for each of the three independent heating zones. The heater controller will attempt to maintain each zone at its target temperature within the specified error threshold.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target_0 | float | >= 0, <= 60 |
| 2 | target_1 | float | >= 0, <= 60 |
| 3 | target_2 | float | >= 0, <= 60 |
| 4 | temp_error_0 | float | >= 0, <= 40 |
| 5 | temp_error_1 | float | >= 0, <= 40 |
| 6 | temp_error_2 | float | >= 0, <= 40 |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :tabbed-config
- **Feedback:** :pending-timeout


### Purpose

Configures target temperatures and tolerances for the three heating zones. Each zone can be set independently.


### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.Start]]
- [[proto/cmd.Heater.GetStatus]]


### Preconditions

- Heater subsystem must be started




## Field Notes


### target_0 (#1)

Target temperature for heating zone 0.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### target_1 (#2)

Target temperature for heating zone 1.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### target_2 (#3)

Target temperature for heating zone 2.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### temp_error_0 (#4)

Acceptable temperature deviation from target for zone 0. Heating will activate when temperature falls below (target - error).


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### temp_error_1 (#5)

Acceptable temperature deviation from target for zone 1.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### temp_error_2 (#6)

Acceptable temperature deviation from target for zone 2.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1



