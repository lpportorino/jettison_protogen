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
| 0 | JON_GUI_DATA_CLIENT_TYPE_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_CLIENT_TYPE_INTERNAL_CV | Internal CV module |
| 2 | JON_GUI_DATA_CLIENT_TYPE_LOCAL_NETWORK | Local network client |
| 3 | JON_GUI_DATA_CLIENT_TYPE_CERTIFICATE_PROTECTED | Certificate-authenticated client |
| 4 | JON_GUI_DATA_CLIENT_TYPE_LIRA | LIRA integration client |

