---
id: ser.JonGuiDataStabCorrection
proto: jon_shared_data_cv.proto
package: ser
type: message
---

# JonGuiDataStabCorrection

**Source:** `jon_shared_data_cv.proto`

## Description

One video channel's display-stabilisation correction, in that channel's delivered-FX-raster pixels (day 1920x1080, heat 900x720). The value is what the pixel applier ADDS to image position — a scene feature at raw pixel p renders at p + C — so display-space consumers add it to scene-locked overlay positions and subtract it from operator input (taps, drags) before that input becomes a command. The anchor is the LRF crosshair (the digital-zoom centre), not the raster centre. Published by the eutropia stabilisation smoother on JonGuiDataCV.stab_correction_day/_heat; it reflects the smoother's output, not a receipt that pixels were actually warped (with the FX bypass engaged the display shows raw pixels while this value still carries the smoother's correction).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | x_px | float | - |
| 2 | y_px | float | - |




