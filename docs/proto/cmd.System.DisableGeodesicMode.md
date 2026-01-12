---
id: cmd.System.DisableGeodesicMode
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# DisableGeodesicMode

**Source:** `jon_shared_cmd_system.proto`

## Description

Disables triangulation-based positioning by stopping the rotary alignment timer and returning the system to standard coordinate display mode. When geodesic mode is enabled, the system performs continuous rotary calibration to triangulate position; this command stops that alignment process.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Disables geodesic triangulation positioning mode


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataSystem]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.System.EnableGeodesicMode]]


### Preconditions



### Implementation Notes

Uses jonGeodesicModeButton component. Toggle button with triangulation icon. Pending state until server confirms via ser.JonGuiDataSystem.geodesic_mode.



