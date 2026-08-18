---
id: ui.ChartProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# ChartProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | type | [[proto/ui.ChartType]] | defined enum value only |
| 2 | point_count | uint32 | - |
| 3 | has_div_lines | bool | - |
| 4 | hdiv_count | uint32 | <= 255 |
| 5 | vdiv_count | uint32 | <= 255 |
| 6 | series | repeated [[proto/ui.ChartSeries]] | max-items: 8 |
| 7 | fade_area | bool | - |




## Field Notes


### type (#1)

Chart draw type, direct-cast to `lv_chart_type_t` (parity-gated). `CHART_TYPE_NONE` (0) keeps the LVGL default, LINE.


### hdiv_count (#4)

Number of horizontal division lines. Applied only when `has_div_lines` is set, because 0 is a valid explicit count rather than an absent value.


### vdiv_count (#5)

Number of vertical division lines, on the same explicit-presence rule as `hdiv_count`.


### series (#6)

The chart's data series, at most eight.



