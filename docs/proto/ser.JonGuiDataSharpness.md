---
id: ser.JonGuiDataSharpness
proto: jon_shared_data_types.proto
package: ser
type: message
---

# JonGuiDataSharpness

**Source:** `jon_shared_data_types.proto`

## Description

Image sharpness metric for autofocus. Contains the normalized sharpness value (0-1) along with first and second derivatives for tracking focus trend. Used by CV algorithms to determine optimal focus position by maximizing sharpness.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |
| 2 | derivative_1 | double | - |
| 3 | derivative_2 | double | - |




## Field Notes


### value (#1)

Normalized value (0.0 to 1.0)



