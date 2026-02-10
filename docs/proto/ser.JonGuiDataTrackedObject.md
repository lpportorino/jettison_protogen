---
id: ser.JonGuiDataTrackedObject
proto: jon_shared_data_types.proto
package: ser
type: message
---

# JonGuiDataTrackedObject

**Source:** `jon_shared_data_types.proto`

## Description

A tracked object in the CV tracking system. Contains a unique UUID for object identity across frames, the object's 3D transform (position, orientation, velocities), the 2D bounding box in the current frame, and the tracking state (initializing, tracking, lost, etc.).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | uuid | string | min-len: 36, max-len: 36, pattern: ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ |
| 2 | transform | [[proto/ser.JonGuiDataTransform3D]] | required |
| 3 | bounding_box | [[proto/ser.JonGuiDataROI]] | required |
| 4 | state | [[proto/ser.JonGuiDataTrackedObject.TrackingState]] | defined enum value only, not in: 0 |




## Field Notes


### uuid (#1)

Object UUID


### transform (#2)

Required — see [[proto/ser.JonGuiDataTransform3D]]


### bounding_box (#3)

Required — see [[proto/ser.JonGuiDataROI]]


### state (#4)

See related enum for valid values



