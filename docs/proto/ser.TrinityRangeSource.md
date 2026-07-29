---
id: ser.TrinityRangeSource
proto: opaque/trinity_tracking.proto
package: ser
type: enum
---

# TrinityRangeSource

**Source:** `opaque/trinity_tracking.proto`

## Description

How `TrinityTracking.position_z_m` was obtained.

Not cosmetic. Monocular range from the board's apparent size degrades with the square of range
(~27 mm at 10 m on day, ~676 mm at 50 m), while a direct LRF distance is roughly range-independent
and is the millimetre-class option. The two differ by more than an order of magnitude at 50 m and
look identical on the wire, so a consumer must be able to tell which it received.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | TRINITY_RANGE_SOURCE_UNSPECIFIED | Proto3 zero default, never legitimately emitted: `TrinityTracking.range_source` (#19) carries `defined_only` + `not_in:[0]`, so a payload leaving it unset does not pass the sender's validation gate. |
| 1 | TRINITY_RANGE_SOURCE_BOARD_EXTENT | Monocular range, solved from the board's apparent SIZE in the image. The weakest axis: because dZ/Z = dS/S and the board subtends few pixels, error grows with the SQUARE of range — ~27 mm at 10 m on day, ~676 mm at 50 m. Expect `sigma_range_m` far larger than `sigma_position_m` here (27 mm vs 1.1 mm at 10 m day). |
| 2 | TRINITY_RANGE_SOURCE_LRF | Direct laser-rangefinder distance. Roughly range-independent rather than degrading with range squared, so this is the millimetre-class option; `sigma_range_m` may be SMALLER than `sigma_position_m`, which is the reverse of the BOARD_EXTENT case — never assume the relation without reading this field. |
| 3 | TRINITY_RANGE_SOURCE_FUSED | LRF distance supplying Z, with lateral position and orientation still derived from the board. Combines the range-independent Z of LRF with the board's own lateral/angular solution. |

