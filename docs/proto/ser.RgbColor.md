---
id: ser.RgbColor
proto: jon_shared_data_lrf.proto
package: ser
type: message
---

# RgbColor

**Source:** `jon_shared_data_lrf.proto`

## Description

Represents an RGB color value with red, green, and blue components each constrained to 0-255, used in the UI to specify and display target marker colors for laser rangefinder measurements and on-screen display (OSD) configuration.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | red | uint32 | >= 0, <= 255 |
| 2 | green | uint32 | >= 0, <= 255 |
| 3 | blue | uint32 | >= 0, <= 255 |




## Field Notes


### red (#1)

Red color channel (0-255)


#### Metadata

- **Semantic Type:** :raw
- **Precision:** 0


### green (#2)

Green color channel (0-255)


#### Metadata

- **Semantic Type:** :raw
- **Precision:** 0


### blue (#3)

Blue color channel (0-255)


#### Metadata

- **Semantic Type:** :raw
- **Precision:** 0



