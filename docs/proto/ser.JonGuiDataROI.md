---
id: ser.JonGuiDataROI
proto: jon_shared_data_types.proto
package: ser
type: message
---

# JonGuiDataROI

**Source:** `jon_shared_data_types.proto`

## Description

Region of Interest (ROI) for CV tracking. Defines a rectangular area in normalized coordinates where -1,-1 is top-left and 1,1 is bottom-right of the frame. Used to specify the initial tracking target or search area for computer vision algorithms.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | x1 | double | >= -1, <= 1 |
| 2 | y1 | double | >= -1, <= 1 |
| 3 | x2 | double | >= -1, <= 1 |
| 4 | y2 | double | >= -1, <= 1 |




## Field Notes


### x1 (#1)

Left edge in NDC (-1.0 to 1.0)


### y1 (#2)

Top edge in NDC (-1.0 to 1.0)


### x2 (#3)

Right edge in NDC (-1.0 to 1.0)


### y2 (#4)

Bottom edge in NDC (-1.0 to 1.0)



