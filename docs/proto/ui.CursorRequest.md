---
id: ui.CursorRequest
proto: ui/ui_input.proto
package: ui
type: message
---

# CursorRequest

**Source:** `ui/ui_input.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | cursor | [[proto/ui.CursorType]] | defined enum value only, not in: 0 |




## Field Notes


### cursor (#1)

The cursor the WASM asks the host to render. Closed to defined `CursorType` members and refusing the zero value, so a request always names a cursor rather than leaving the host to choose one.



