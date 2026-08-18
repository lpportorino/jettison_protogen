---
id: ui.ChartSeries
proto: ui/ui_ast.proto
package: ui
type: message
---

# ChartSeries

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | color | [[proto/ui.Color]] | - |
| 2 | axis | [[proto/ui.ChartAxis]] | defined enum value only |
| 3 | values | repeated int32 | max-items: 32 |




## Field Notes


### axis (#2)

The Y axis this series attaches to, direct-cast to `lv_chart_axis_t` (parity-gated, sparse bitmask values). `CHART_AXIS_PRIMARY_Y` (0) is the default.


### values (#3)

Frozen-frame data points, written BY INDEX — point i is `values[i]`. Points past the end of the list keep `LV_CHART_POINT_NONE`, so a short list leaves the rest of the chart empty rather than shifting it.



