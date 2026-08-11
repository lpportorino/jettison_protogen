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

<!-- NEEDS_REVIEW: quantity contradiction, unresolved on purpose. jon_shared_cmd_heater.proto comments target_0/1/2 as "Target power values per channel in watts"; this page declares them :temperature with unit °C. Which one is correct depends on what the heater firmware does with the value, so it is not decidable from this repository — settling it is a coordinated change with the firmware, never an edit here. Do not flip the semantic type or the unit on either side without that determination. The sibling temp_error_N fields are NOT in question: proto and page agree they are Celsius. -->

Target temperature for heating zone 0.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### target_1 (#2)

<!-- NEEDS_REVIEW: quantity contradiction, unresolved on purpose. jon_shared_cmd_heater.proto comments target_0/1/2 as "Target power values per channel in watts"; this page declares them :temperature with unit °C. Which one is correct depends on what the heater firmware does with the value, so it is not decidable from this repository — settling it is a coordinated change with the firmware, never an edit here. Do not flip the semantic type or the unit on either side without that determination. The sibling temp_error_N fields are NOT in question: proto and page agree they are Celsius. -->

Target temperature for heating zone 1.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### target_2 (#3)

<!-- NEEDS_REVIEW: quantity contradiction, unresolved on purpose. jon_shared_cmd_heater.proto comments target_0/1/2 as "Target power values per channel in watts"; this page declares them :temperature with unit °C. Which one is correct depends on what the heater firmware does with the value, so it is not decidable from this repository — settling it is a coordinated change with the firmware, never an edit here. Do not flip the semantic type or the unit on either side without that determination. The sibling temp_error_N fields are NOT in question: proto and page agree they are Celsius. -->

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



