---
id: ser.OsdClientMetadata
proto: opaque/osd_client_metadata.proto
package: ser
type: message
---

# OsdClientMetadata

**Source:** `opaque/osd_client_metadata.proto`

## Description

Client-side canvas and rendering metadata for resolution-aware OSD overlay compositing. Injected by the frontend into `JonGUIState.opaque_payloads` so that the server-side OSD renderer (WASM or native) can correctly map its fixed-resolution framebuffer onto the client's variable-size display canvas. Carries the physical canvas dimensions, device pixel ratio, the NDC bounding box of the video proxy quad, the computed scale factor between OSD buffer pixels and physical display pixels, and the current UI theme parameters (OKLCH color space and sharp/smooth mode).

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



## Interaction

- **Category:** :settings








## Field Notes


### canvas_width_px (#1)

Physical canvas width in pixels, computed as CSS layout width multiplied by `devicePixelRatio`. Represents the actual backing-store resolution of the HTML canvas element. Supports up to 8K at 5x DPR (40960).
- **Semantic Type:** count
- **Unit:** px


### canvas_height_px (#2)

Physical canvas height in pixels, computed as CSS layout height multiplied by `devicePixelRatio`. Represents the actual backing-store resolution of the HTML canvas element. Supports up to 8K at 5x DPR (40960).
- **Semantic Type:** count
- **Unit:** px


### device_pixel_ratio (#3)

The browser's `window.devicePixelRatio` value, indicating the ratio of physical pixels to CSS pixels. Typical values are 1.0 for standard displays, 2.0 for Retina/HiDPI, and 3.0 for high-density mobile screens.
- **Semantic Type:** raw
- **Precision:** 1


### osd_buffer_width (#4)

Width of the OSD framebuffer in pixels. This is the fixed rendering resolution of the OSD layer: 1920 for the day camera channel, 900 for the heat camera channel.
- **Semantic Type:** count
- **Unit:** px


### osd_buffer_height (#5)

Height of the OSD framebuffer in pixels. This is the fixed rendering resolution of the OSD layer: 1080 for the day camera channel, 720 for the heat camera channel.
- **Semantic Type:** count
- **Unit:** px


### video_proxy_ndc_x (#6)

X coordinate of the video proxy quad's origin in Normalized Device Coordinates (NDC, range -1 to 1). Defines where the video feed is positioned on the client canvas. For gallery/fullscreen views this is 0 (left edge of viewport center).
- **Semantic Type:** coordinate-viewport
- **Precision:** 4


### video_proxy_ndc_y (#7)

Y coordinate of the video proxy quad's origin in Normalized Device Coordinates (NDC, range -1 to 1). For gallery/fullscreen views this is 0 (top edge of viewport center).
- **Semantic Type:** coordinate-viewport
- **Precision:** 4


### video_proxy_ndc_width (#8)

Width of the video proxy quad in NDC units (range 0 to 2, where 2 spans the full NDC range from -1 to 1). For gallery/fullscreen views this is 1 (half the NDC range, covering the viewport from center to right).
- **Semantic Type:** coordinate-viewport
- **Precision:** 4


### video_proxy_ndc_height (#9)

Height of the video proxy quad in NDC units (range 0 to 2, where 2 spans the full NDC range from -1 to 1). For gallery/fullscreen views this is 1 (half the NDC range, covering the viewport from center to bottom).
- **Semantic Type:** coordinate-viewport
- **Precision:** 4


### scale_factor (#10)

Ratio of OSD buffer pixels to video proxy physical pixels on the display. Computed as `osd_buffer_pixels / proxy_physical_pixels`. Used by the OSD renderer to correctly scale text, crosshairs, and other overlay elements so they appear at consistent physical sizes regardless of display resolution.
- **Semantic Type:** raw
- **Precision:** 2


### is_sharp_mode (#11)

Whether the UI is using the high-contrast "sharp" theme mode versus the smooth OKLCH-based "default" mode. When true, the OSD renderer should use high-contrast colors and hard edges; when false, it uses the OKLCH theme colors from the `theme_hue`, `theme_chroma`, and `theme_lightness` fields.
- **Semantic Type:** toggle-state


### theme_hue (#12)

OKLCH hue angle for the UI theme's base color. Only meaningful when `is_sharp_mode` is false. Default is 120 (green). Full rotation around the color wheel from 0 to 360 degrees.
- **Semantic Type:** angle
- **Unit:** deg
- **Precision:** 0


### theme_chroma (#13)

OKLCH chroma (saturation) for the UI theme's base color. Only meaningful when `is_sharp_mode` is false. Default is 0.1; the theme picker allows values up to 0.8. Range 0 (achromatic) to 1 (fully saturated).
- **Semantic Type:** normalized
- **Precision:** 2


### theme_lightness (#14)

OKLCH lightness for the UI theme's base color. Only meaningful when `is_sharp_mode` is false. Default is 50. Range extends to 200 to support HDR displays.
- **Semantic Type:** percentage
- **Unit:** %
- **Precision:** 0



