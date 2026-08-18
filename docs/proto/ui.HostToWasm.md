---
id: ui.HostToWasm
proto: ui/ui_input.proto
package: ui
type: message
---

# HostToWasm

**Source:** `ui/ui_input.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | version | uint32 | >= 1, <= 255 |
| 2 | pointer | [[proto/ui.PointerEvent]] | - |
| 3 | lifecycle | [[proto/ui.Lifecycle]] | - |


## Oneofs


### event (required)

Fields: #2, #3





## Field Notes


### version (#1)

Envelope version. The consumer checks it against the current value FIRST and answers a mismatch with an error — there is no migration branch and no version sniffing. `gte: 1` rejects the proto3 default of 0, so an unset version cannot read as a valid one.



