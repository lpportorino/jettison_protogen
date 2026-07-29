---
id: ser.JonGuiDataExtBatStatus
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataExtBatStatus

**Source:** `jon_shared_data_types.proto`

## Description

Represents the operational state of an external battery pack, indicating whether the battery is actively charging, discharging, or performing cell balancing. Displayed in the UI with color-coded indicators and pulsing animations for charging/balancing states.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_EXT_BAT_STATUS_UNSPECIFIED | Proto3 zero default — and here it IS observable on the wire, unlike most enum fields in this schema: neither carrier constrains the field (`JonGuiDataPower.ext_bat_status` #11 and `JonGuiDataSystem.ext_bat_status` #26 both declare it bare), so a producer that never sets it sends 0 and a consumer must treat that as "no status reported", never as a battery state. |
| 1 | JON_GUI_DATA_EXT_BAT_STATUS_CHARGING | The external pack is taking charge. This field carries the pack's MODE only — the level rides separately on the sibling `ext_bat_capacity`, an int32 percentage constrained to 0-100 on `JonGuiDataSystem` (#25). |
| 2 | JON_GUI_DATA_EXT_BAT_STATUS_DISCHARGING | Net current is leaving the external pack — it is supplying the platform. |
| 3 | JON_GUI_DATA_EXT_BAT_STATUS_BALANCING | The pack is equalising charge across its series-connected cells. A single enum field admits one value, so this is reported INSTEAD of CHARGING or DISCHARGING rather than alongside either. |

