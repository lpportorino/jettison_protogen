---
id: cmd.System.EnableGeodesicMode
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# EnableGeodesicMode

**Source:** `jon_shared_cmd_system.proto`

## Description

Enables geodesic/geographic coordinate mode, switching the system from local coordinate positioning to geographic coordinate positioning based on triangulation. This allows the system to track and display object positions using geographic coordinates rather than local reference frames.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Enable geodesic/geographic coordinate mode for position calculations


### Related State

- [[proto/ser.JonGuiDataSystem#geodesic_mode]]


### Related Commands

- [[proto/cmd.System.DisableGeodesicMode]]



### Implementation Notes

Empty message - trigger only



