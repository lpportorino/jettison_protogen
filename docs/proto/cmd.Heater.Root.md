---
id: cmd.Heater.Root
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# Root

**Source:** `jon_shared_cmd_heater.proto`

## Description

Root command container for the heater subsystem, which manages three glass heating channels for optical windows: Day Camera Lens (Channel 0, 60W), Laser Rangefinder Lens (Channel 1, 15W), and Thermal Camera Lens (Channel 2, 60W). The heater system supports both manual control via `set_heating` and PID-based automatic temperature regulation via `enable_automatic_control` with configurable target temperatures per channel. Contains all heater-related commands as a required oneof.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start | [[proto/cmd.Heater.Start]] | - |
| 2 | stop | [[proto/cmd.Heater.Stop]] | - |
| 3 | set_heating | [[proto/cmd.Heater.SetHeating]] | - |
| 4 | get_status | [[proto/cmd.Heater.GetStatus]] | - |
| 5 | enable_automatic_control | [[proto/cmd.Heater.EnableAutomaticControl]] | - |
| 6 | disable_automatic_control | [[proto/cmd.Heater.DisableAutomaticControl]] | - |
| 7 | set_automatic_control_params | [[proto/cmd.Heater.SetAutomaticControlParams]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7




## Interaction

- **Category:** :settings
- **UI Pattern:** :tabbed-config
- **Feedback:** :fire-and-forget


### Purpose

Container message that wraps all heater commands. Exactly one command must be set. The heater system prevents fogging and condensation on optical windows by maintaining glass at configurable temperatures. The frontend UI (`jonHeaterPanel`) provides a toggle for automatic control and per-channel temperature sliders (0-60 C range).


### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Power.SetChannel]] - Controls S7_HEATER power rail that supplies the heater subsystem


### Usage Example

```json
// Enable automatic control
{"heater": {"enable_automatic_control": {}}}

// Set target temperatures for all channels (in Celsius)
{"heater": {"set_automatic_control_params": {
  "channel_0": {"target_temperature": 25},
  "channel_1": {"target_temperature": 20},
  "channel_2": {"target_temperature": 25}
}}}

// Disable automatic control
{"heater": {"disable_automatic_control": {}}}
```




## Field Notes


### start (#1)

Starts the heater subsystem. See [[proto/cmd.Heater.Start]]


### stop (#2)

Stops the heater subsystem and sets power output to zero. See [[proto/cmd.Heater.Stop]]


### set_heating (#3)

Manual heating control - directly sets power levels per channel. See [[proto/cmd.Heater.SetHeating]]


### get_status (#4)

Requests current heater status (bus voltage, current, power, channel temperatures). See [[proto/cmd.Heater.GetStatus]]


### enable_automatic_control (#5)

Enables PID-based automatic temperature regulation. When enabled, the heater module runs a 500ms control loop comparing current temperatures against targets. See [[proto/cmd.Heater.EnableAutomaticControl]]


### disable_automatic_control (#6)

Disables automatic temperature regulation and resets PID accumulators. See [[proto/cmd.Heater.DisableAutomaticControl]]


### set_automatic_control_params (#7)

Configures target temperatures (0-60 C) for all three heater channels when automatic control is enabled. See [[proto/cmd.Heater.SetAutomaticControlParams]]



