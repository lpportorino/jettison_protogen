---
id: ui.Lifecycle
proto: ui/ui_input.proto
package: ui
type: message
---

# Lifecycle

**Source:** `ui/ui_input.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | theme | [[proto/ui.ThemeMode]] | defined enum value only, not in: 0 |
| 2 | focused | bool | - |
| 3 | visible | bool | - |




## Field Notes


### theme (#1)

The theme the host is currently in. Closed to defined `ThemeMode` members and refusing the zero value: a lifecycle push naming no theme would leave the WASM restyling to a default the host never asked for.



