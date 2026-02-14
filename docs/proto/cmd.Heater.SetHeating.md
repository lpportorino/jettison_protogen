---
id: cmd.Heater.SetHeating
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# SetHeating

**Source:** `jon_shared_cmd_heater.proto`

## Description

<!-- NEEDS_REVIEW: Proto comment says "target power values per channel in watts" but field names use "target_X". Verify whether this is direct power control (watts) or temperature setpoints (degrees). The frontend uses SetAutomaticControlParams for temperature-based control, suggesting SetHeating may be legacy/low-level direct power control. -->

Sets target power values and acceptable temperature error margins for each of the three independent heating zones (day camera glass, LRF glass, and thermal camera glass). This is a low-level control interface that may be used for direct power control when automatic PID regulation is disabled.

**Note:** For temperature-based automatic control, use [[proto/cmd.Heater.SetAutomaticControlParams]] instead, which is the modern interface used by the web UI.

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

Configures power targets and temperature tolerances for the three glass heater zones. Each zone heats an optical window to prevent fogging/condensation:
- Zone 0: Day camera lens (60W max)
- Zone 1: LRF (laser rangefinder) lens (15W max)
- Zone 2: Thermal camera lens (60W max)


### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.Start]]
- [[proto/cmd.Heater.GetStatus]]
- [[proto/cmd.Heater.SetAutomaticControlParams]]
- [[proto/cmd.Heater.EnableAutomaticControl]]
- [[proto/cmd.Heater.DisableAutomaticControl]]


### Preconditions

- Heater subsystem must be started




## Field Notes


### target_0 (#1)

<!-- NEEDS_REVIEW: Verify if this is power (watts) or temperature (Celsius) -->
Target power/temperature setpoint for heating zone 0 (day camera glass heater). The day camera optical window is a 60W heating element.


#### Metadata

- **Semantic Type:** :power
- **Unit:** W
- **Precision:** 1


### target_1 (#2)

<!-- NEEDS_REVIEW: Verify if this is power (watts) or temperature (Celsius) -->
Target power/temperature setpoint for heating zone 1 (LRF glass heater). The laser rangefinder optical window uses a 15W heating element, smaller due to the smaller aperture size.


#### Metadata

- **Semantic Type:** :power
- **Unit:** W
- **Precision:** 1


### target_2 (#3)

<!-- NEEDS_REVIEW: Verify if this is power (watts) or temperature (Celsius) -->
Target power/temperature setpoint for heating zone 2 (thermal camera glass heater). The thermal camera germanium window uses a 60W heating element.


#### Metadata

- **Semantic Type:** :power
- **Unit:** W
- **Precision:** 1


### temp_error_0 (#4)

Acceptable temperature deviation threshold for zone 0 (day camera glass). Defines the temperature hysteresis band for on/off control. Heating activates when temperature falls below (target - error) and deactivates when it exceeds (target + error).


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### temp_error_1 (#5)

Acceptable temperature deviation threshold for zone 1 (LRF glass). Defines the temperature hysteresis band for the smaller 15W LRF heater.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1


### temp_error_2 (#6)

Acceptable temperature deviation threshold for zone 2 (thermal camera glass). Defines the temperature hysteresis band for the thermal camera window heater.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1



