---
id: ser.JonGuiDataSystemLocalizations
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataSystemLocalizations

**Source:** `jon_shared_data_types.proto`

## Description

Specifies the UI language setting for the system, supporting four languages: English (EN), Ukrainian (UA), Arabic (AR), and Czech (CS). Users can switch the interface language via the Language control palette, which updates both the UI and device state.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_SYSTEM_LOCALIZATION_UNSPECIFIED | Protobuf default zero, meaning the field was never set. Both fields typed by this enum — `ser.JonGuiDataSystem.loc` and `cmd.System.SetLocalization.loc` — carry `not_in: [0]`, so neither a reported system state nor a language-change command may leave the selection unset. |
| 1 | JON_GUI_DATA_SYSTEM_LOCALIZATION_EN | English. The suffix matches the ISO 639-1 language code `en`. |
| 2 | JON_GUI_DATA_SYSTEM_LOCALIZATION_UA | Ukrainian. The suffix is the ISO 3166-1 COUNTRY code for Ukraine, not the language code — ISO 639-1 for Ukrainian is `uk` — so a consumer must not lower-case this suffix and expect a valid locale identifier. |
| 3 | JON_GUI_DATA_SYSTEM_LOCALIZATION_AR | Arabic. The suffix matches the ISO 639-1 language code `ar`. |
| 4 | JON_GUI_DATA_SYSTEM_LOCALIZATION_CS | Czech. The suffix matches the ISO 639-1 language code `cs`. |

