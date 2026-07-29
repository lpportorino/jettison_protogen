---
id: ser.TrinityAltPose
proto: opaque/trinity_tracking.proto
package: ser
type: message
---

# TrinityAltPose

**Source:** `opaque/trinity_tracking.proto`

## Description

The pose the disambiguator did **not** choose.

Present when the near-affine two-fold ambiguity admitted a second solution that reprojection error
could not separate from the chosen one. It is carried so a consumer can see the fork and apply its
own prior — a scale prior, a temporal track, or an external range — rather than inheriting a
selection it cannot audit.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | position_x_m | double | - |
| 2 | position_y_m | double | - |
| 3 | position_z_m | double | - |
| 4 | quat_w | double | - |
| 5 | quat_x | double | - |
| 6 | quat_y | double | - |
| 7 | quat_z | double | - |
| 8 | reprojection_rms_px | double | - |




