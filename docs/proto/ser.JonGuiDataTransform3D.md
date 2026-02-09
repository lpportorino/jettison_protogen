---
id: ser.JonGuiDataTransform3D
proto: jon_shared_data_types.proto
package: ser
type: message
---

# JonGuiDataTransform3D

**Source:** `jon_shared_data_types.proto`

## Description

Complete 3D transform including position, orientation, and motion state. Represents a tracked object's pose and velocity in the world coordinate frame. Position is in meters, orientation is a unit quaternion, velocities are in m/s and rad/s respectively.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | position | [[proto/ser.JonGuiDataVector3]] | required |
| 2 | orientation | [[proto/ser.JonGuiDataQuaternion]] | required |
| 3 | linear_velocity | [[proto/ser.JonGuiDataVector3]] | required |
| 4 | angular_velocity | [[proto/ser.JonGuiDataVector3]] | required |




