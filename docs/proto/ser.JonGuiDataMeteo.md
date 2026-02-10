---
id: ser.JonGuiDataMeteo
proto: jon_shared_data_types.proto
package: ser
type: message
---

# JonGuiDataMeteo

**Source:** `jon_shared_data_types.proto`

## Description

Represents environmental sensor data containing atmospheric measurements: temperature (in degrees Celsius), humidity (as a percentage), and pressure (in Pascal units). Used for ballistics calculations and system monitoring across multiple subsystems.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | temperature | double | >= -273.15, <= 150 |
| 2 | humidity | double | >= 0, <= 100 |
| 3 | pressure | double | >= 0, <= 120000 |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget


### Purpose

Meteorological sensor data (temperature, pressure, humidity, etc.)



### Related Commands

- [[proto/cmd.DayCamera.GetMeteo]]
- [[proto/cmd.HeatCamera.GetMeteo]]
- [[proto/cmd.Lrf.GetMeteo]]



### Implementation Notes

Environmental sensor readings used for ballistics calculations and system monitoring



## Field Notes


### temperature (#1)

Temperature in degrees Celsius


### humidity (#2)

Relative humidity percentage (0-100)


### pressure (#3)

Atmospheric pressure in pascals



