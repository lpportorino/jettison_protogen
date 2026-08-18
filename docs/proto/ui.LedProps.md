---
id: ui.LedProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# LedProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | color | [[proto/ui.Color]] | - |
| 2 | brightness | uint32 | <= 255 |




## Field Notes


### brightness (#2)

LED brightness, 0-255 — the range `lv_led_set_brightness` takes.



