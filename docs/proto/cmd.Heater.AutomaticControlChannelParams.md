---
id: cmd.Heater.AutomaticControlChannelParams
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# AutomaticControlChannelParams

**Source:** `jon_shared_cmd_heater.proto`

## Description

Per-channel parameters for the heater automatic (PID) control loop. This sub-message is used by `SetAutomaticControlParams` to configure one of the three heating channels (channel 0 = day camera glass, channel 1 = LRF glass, channel 2 = heat camera glass). Currently contains only the target temperature setpoint; PID tuning gains (kp, ki, kd) are loaded separately from Redis via config_editor.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | target_temperature | float | >= 0, <= 60 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :slider
- **Feedback:** :pending-timeout



### Related State

- [[proto/ser.JonGuiDataHeater]]
- [[proto/ser.JonGuiDataHeaterChannelStatus]]


### Related Commands

- [[proto/cmd.Heater.SetAutomaticControlParams]]
- [[proto/cmd.Heater.EnableAutomaticControl]]
- [[proto/cmd.Heater.DisableAutomaticControl]]





## Field Notes


### target_temperature (#1)

Desired temperature setpoint for this heating channel in degrees Celsius. The PID controller computes the error between this target and the current measured temperature, then outputs an appropriate heating power level. When updated, PID integral and derivative accumulators are reset to prevent windup from stale state. The value is persisted to manifold state storage via the sync timer. Defaults to 10°C in production builds.


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1



