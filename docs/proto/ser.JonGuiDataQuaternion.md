---
id: ser.JonGuiDataQuaternion
proto: jon_shared_data_types.proto
package: ser
type: message
---

# JonGuiDataQuaternion

**Source:** `jon_shared_data_types.proto`

## Description

Unit quaternion representing 3D orientation (w + xi + yj + zk). Should be normalized (w² + x² + y² + z² = 1). Used for tracked object orientation in the world coordinate frame.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | w | double | required |
| 2 | x | double | required |
| 3 | y | double | required |
| 4 | z | double | required |




