---
id: ser.JonGuiDataCompassUnits
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataCompassUnits

**Source:** `jon_shared_data_types.proto`

## Description

Specifies the angular unit system for displaying compass bearing measurements. Supports four standard angle measurement units: degrees (0-360), mils (0-6400 military/tactical), gradians (0-400), and milliradians (0-2000).

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_COMPASS_UNITS_UNSPECIFIED | Protobuf default zero, meaning no angular unit was selected. It is a placeholder for an unset field, never a renderable unit; no message in the current proto surface carries this enum, so nothing validates it away. |
| 1 | JON_GUI_DATA_COMPASS_UNITS_DEGREES | Bearings shown in degrees: 360 to the full circle, so a right angle is 90 and a full turn wraps at 360 back to 0. |
| 2 | JON_GUI_DATA_COMPASS_UNITS_MILS | Bearings shown in NATO mils: 6400 to the full circle. The division is chosen so one mil subtends roughly one metre at one kilometre, which is what makes it the fire-control unit — an aiming error in mils reads directly as a miss distance in metres per kilometre of range. |
| 3 | JON_GUI_DATA_COMPASS_UNITS_GRAD | Bearings shown in gradians: 400 to the full circle, so a right angle is exactly 100. The metric angular unit, decimal rather than sexagesimal. |
| 4 | JON_GUI_DATA_COMPASS_UNITS_MRAD | Bearings shown in milliradians, the SI-derived angle: one mrad is one thousandth of a radian, so a full circle is roughly 6283 mrad and one mrad subtends one metre at one kilometre. Not interchangeable with the NATO mil above, which is about 2 percent smaller. |

