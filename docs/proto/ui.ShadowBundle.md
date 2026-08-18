---
id: ui.ShadowBundle
proto: ui/ui_ast.proto
package: ui
type: message
---

# ShadowBundle

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | width | uint32 | - |
| 2 | offset_x | int32 | - |
| 3 | offset_y | int32 | - |
| 4 | spread | uint32 | - |
| 5 | opa | uint32 | <= 255 |




## Field Notes


### opa (#5)

Shadow opacity, 0-255 — LVGL's opacity range, where 255 is fully opaque.



