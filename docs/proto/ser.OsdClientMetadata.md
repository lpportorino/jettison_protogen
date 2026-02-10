---
id: ser.OsdClientMetadata
proto: opaque/osd_client_metadata.proto
package: ser
type: message
---

# OsdClientMetadata

**Source:** `opaque/osd_client_metadata.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | canvas_width_px | uint32 | > 0, <= 40960 |
| 2 | canvas_height_px | uint32 | > 0, <= 40960 |
| 3 | device_pixel_ratio | float | >= 0.1, <= 10 |
| 4 | osd_buffer_width | uint32 | > 0, <= 8192 |
| 5 | osd_buffer_height | uint32 | > 0, <= 8192 |
| 6 | video_proxy_ndc_x | float | >= -1, <= 1 |
| 7 | video_proxy_ndc_y | float | >= -1, <= 1 |
| 8 | video_proxy_ndc_width | float | >= 0, <= 2 |
| 9 | video_proxy_ndc_height | float | >= 0, <= 2 |
| 10 | scale_factor | float | >= 0.01, <= 100 |
| 11 | is_sharp_mode | bool | - |
| 12 | theme_hue | float | >= 0, <= 360 |
| 13 | theme_chroma | float | >= 0, <= 1 |
| 14 | theme_lightness | float | >= 0, <= 200 |




