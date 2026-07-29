---
id: ser.JonGuiDataClientApp
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataClientApp

**Source:** `jon_shared_data_types.proto`

## Description

Identifies the type of client application connecting to the system, enabling the server to differentiate between different UI implementations. Defines four application types: BROWSER_UI (web interface), BROWSER_MAP (map view), DESKTOP_NATIVE (desktop app), and MOBILE_NATIVE (mobile app).

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_CLIENT_APP_UNSPECIFIED | Proto3 zero default, never legitimately emitted: the sole carrier `cmd.Root.client_app` (#10) applies `defined_only` + `not_in:[0]`, so an unset application identity is a validation violation rather than a tolerated unknown. |
| 1 | JON_GUI_DATA_CLIENT_APP_BROWSER_UI | The browser HUD consumer. Pinned by golden vector G1-B in `docs/INTERFACE-CONTRACTS.md` §6, whose ping bytes `50 01` are field 10 = 1 — sent alongside `client_type` 2 (LOCAL_NETWORK), which is what makes this enum the APPLICATION axis and `JonGuiDataClientType` the CONNECTION axis: the same connection type carries different apps. |
| 2 | JON_GUI_DATA_CLIENT_APP_BROWSER_MAP | The browser map view. No golden vector in `docs/INTERFACE-CONTRACTS.md` §6 pins this value, and no consumer in this repository emits it. |
| 3 | JON_GUI_DATA_CLIENT_APP_DESKTOP_NATIVE | The native desktop consumer. Pinned by golden vector G1 in `docs/INTERFACE-CONTRACTS.md` §6, whose ping bytes `50 03` are field 10 = 3. |
| 4 | JON_GUI_DATA_CLIENT_APP_MOBILE_NATIVE | A native mobile application. No golden vector in `docs/INTERFACE-CONTRACTS.md` §6 pins this value, and no consumer in this repository emits it. |

