---
id: ui.PointerEvent
proto: ui/ui_input.proto
package: ui
type: message
---

# PointerEvent

**Source:** `ui/ui_input.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | phase | [[proto/ui.PointerPhase]] | defined enum value only, not in: 0 |
| 2 | kind | [[proto/ui.PointerKind]] | defined enum value only, not in: 0 |
| 3 | pointer_id | uint32 | - |
| 4 | x | double | >= -1, <= 1 |
| 5 | y | double | >= -1, <= 1 |
| 6 | event_time | uint64 | - |




## Field Notes


### phase (#1)

The pointer phase — down, move, up or cancel. Closed and refusing the zero value; the WASM re-checks this at its own decode boundary, because nanopb strips the annotation and the guard has to survive the strip.


### kind (#2)

Which device produced the event — mouse, touch or pen. Closed and refusing the zero value, on the same self-validation rule as `phase`.


### x (#4)

Pointer position in normalized device coordinates, +x to the right. Clamped to [-1, 1] at the decode boundary.


### y (#5)

Pointer position in normalized device coordinates, +y UP. Clamped to [-1, 1] at the decode boundary.



