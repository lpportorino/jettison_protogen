---
id: ser.JonGuiDataClientType
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataClientType

**Source:** `jon_shared_data_types.proto`

## Description

Categorizes different types of clients connecting to the system based on their connection method: internal computer vision systems (INTERNAL_CV), local network access (LOCAL_NETWORK), certificate-protected secured connections (CERTIFICATE_PROTECTED), and LIRA device interfaces (LIRA).

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_CLIENT_TYPE_UNSPECIFIED | Proto3 zero default, never legitimately emitted: the sole carrier `cmd.Root.client_type` (#5) applies `defined_only` + `not_in:[0]`, so an unset identity is a validation violation a sender-side protovalidate gate refuses — not a tolerated unknown. |
| 1 | JON_GUI_DATA_CLIENT_TYPE_INTERNAL_CV | The platform's own computer-vision subsystem acting as a command client, rather than an external connection. |
| 2 | JON_GUI_DATA_CLIENT_TYPE_LOCAL_NETWORK | A client reaching the platform over the local network. Both reference consumers send this: `docs/INTERFACE-CONTRACTS.md` §6 pins `client_type = 2` in golden vectors G1 (native desktop) and G1-B (browser HUD). |
| 3 | JON_GUI_DATA_CLIENT_TYPE_CERTIFICATE_PROTECTED | A client on a certificate-authenticated connection. Which certificate scheme, and what it authorises beyond LOCAL_NETWORK, is not specified anywhere in this repository. |
| 4 | JON_GUI_DATA_CLIENT_TYPE_LIRA | The LIRA target-designation interface — the client that issues `cmd.Lira.Root`, whose one command refines a geodetic target (lat/lon/alt, azimuth, elevation, distance). |

