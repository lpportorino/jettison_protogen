---
id: ui.WasmToHost
proto: ui/ui_input.proto
package: ui
type: message
---

# WasmToHost

**Source:** `ui/ui_input.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | version | uint32 | >= 1, <= 255 |
| 2 | hover | [[proto/ui.HoverState]] | - |
| 3 | cursor | [[proto/ui.CursorRequest]] | - |


## Oneofs


### report (required)

Fields: #2, #3





## Field Notes


### version (#1)

Envelope version, carrying the same fail-fast guard as `HostToWasm.version`: checked first against the current value, with `gte: 1` rejecting the proto3 default of 0.



