---
id: cmd.Heater.SetSystemPower
proto: jon_shared_cmd_heater.proto
package: cmd.Heater
type: message
---

# SetSystemPower

**Source:** `jon_shared_cmd_heater.proto`

## Description

Relays the whole-system power draw, in watts, to the heater node, whose zone power budget subtracts it. The figure is a measurement rather than an operator setting: it is the PMU's INA236 reading (`ina_power` on [[proto/ser.JonGuiDataPMU]], in milliwatts, divided by 1000). The node subtracts its own draw (`power_W` on [[proto/ser.JonGuiDataHeater]]) and shares what remains of its whole-system current ceiling, at its measured rail voltage, between the three zones. A figure the node has not received recently is budgeted as 0 W, and each control step that runs that way is counted in `budget_unrelayed_steps` on [[proto/ser.JonGuiDataHeater]].

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | system_power_W | float | >= 0, <= 200 |




## Field Notes


### system_power_W (#1)

Whole-system power draw in watts. Bounded 0 to 200: the INA236 power register is a magnitude, and 200 W is the largest figure `ina_power`'s own validator admits (200000 mW), so every relay of a validated PMU reading fits.



