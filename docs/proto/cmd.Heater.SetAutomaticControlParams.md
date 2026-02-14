---
id: cmd.Heater.SetAutomaticControlParams
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# SetAutomaticControlParams

**Source:** `jon_shared_cmd_heater.proto`

## Description

Configures target temperatures for the PID-based automatic heating control system across all three heater channels. Each channel parameter contains a `target_temperature` (0--60 C) that the PID controller will regulate toward. Only channels present in the message are updated; omitted channels retain their previous targets. On receipt the heater module resets PID integral and derivative accumulators for all channels to prevent windup when targets change, and persists the new targets to manifold state storage so they survive restarts.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | channel_0 | [[proto/cmd.Heater.AutomaticControlChannelParams]] | - |
| 2 | channel_1 | [[proto/cmd.Heater.AutomaticControlChannelParams]] | - |
| 3 | channel_2 | [[proto/cmd.Heater.AutomaticControlChannelParams]] | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :tabbed-config
- **Feedback:** :pending-timeout



### Related State

- [[proto/ser.JonGuiDataHeater]]


### Related Commands

- [[proto/cmd.Heater.EnableAutomaticControl]]
- [[proto/cmd.Heater.DisableAutomaticControl]]
- [[proto/cmd.Heater.AutomaticControlChannelParams]]





## Field Notes


### channel_0 (#1)

Day camera glass heater. <!-- NEEDS_REVIEW: verify 60W power rating from hardware specs --> Sets the target temperature for the day camera optical window. The PID controller drives this channel's heating element to maintain the glass at the specified temperature, preventing fogging and condensation on the day camera optics.


### channel_1 (#2)

Laser rangefinder (LRF) glass heater. <!-- NEEDS_REVIEW: verify 15W power rating from hardware specs --> Sets the target temperature for the LRF optical window. This channel has a lower power budget than the other two channels, reflecting the smaller glass area of the LRF aperture.


### channel_2 (#3)

Thermal/heat camera glass heater. <!-- NEEDS_REVIEW: verify 60W power rating from hardware specs --> Sets the target temperature for the thermal camera optical window. Keeps the heat camera germanium window at the specified temperature to maintain consistent thermal imaging performance and prevent condensation.



