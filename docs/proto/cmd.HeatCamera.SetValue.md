---
id: cmd.HeatCamera.SetValue
proto: jon_shared_cmd_heat_camera.proto
package: cmd.HeatCamera
type: message
---

# SetValue

**Source:** `jon_shared_cmd_heat_camera.proto`

## Description

<!-- NEEDS_REVIEW: This message is defined but not referenced in HeatCamera.Root oneof - appears to be orphaned/unused. Unlike DayCamera.SetValue which is used within Focus and Zoom composites, HeatCamera.SetValue has no wire path. May be reserved for future use or legacy. -->
Generic message for setting a normalized thermal camera value (0-1 range). Defined in the HeatCamera command protocol but currently not wired into the Root command oneof. The HeatCamera Zoom composite uses SetZoomTableValue (integer table index) instead of SetValue (normalized float).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |



## Interaction

- **Category:** :actuator <!-- NEEDS_REVIEW: Would be :actuator if used, but message is orphaned -->
- **UI Pattern:** :slider <!-- NEEDS_REVIEW: Would be :slider based on normalized value constraint, but not currently used -->
- **Feedback:** :fire-and-forget <!-- NEEDS_REVIEW: Speculative - message is not wired -->


### Purpose

<!-- NEEDS_REVIEW: Message is defined but not wired into command dispatch -->
Intended for setting normalized thermal camera parameter values (0.0-1.0 range). Currently orphaned - not reachable via HeatCamera.Root command dispatch.


### Related State

- [[proto/ser.JonGuiDataCameraHeat]]


### Related Commands

- [[proto/cmd.HeatCamera.Zoom]] - Parent composite (uses SetZoomTableValue instead, not SetValue)
- [[proto/cmd.HeatCamera.SetZoomTableValue]] - Active zoom setter using integer table index
- [[proto/cmd.DayCamera.SetValue]] - Analogous message in DayCamera that IS wired into Focus and Zoom composites


### Implementation Notes

**ORPHANED MESSAGE**: This message is defined in `jon_shared_cmd_heat_camera.proto` but is NOT referenced in the HeatCamera.Root oneof command dispatch. Unlike `cmd.DayCamera.SetValue` which is actively used within Focus and Zoom composite commands, HeatCamera.SetValue has no wire path to the backend.

The HeatCamera Zoom composite uses `SetZoomTableValue` (integer table index) for zoom control, not normalized float values. This message may be:
1. Reserved for future normalized zoom control
2. Legacy from an earlier design
3. Intended for a different thermal camera parameter not yet implemented

No frontend usage exists in `cmdHeatCamera.ts`.



## Field Notes


### value (#1)

Normalized value (0.0 to 1.0) representing a thermal camera parameter position. Constrained to the unit interval by proto validation.


#### Metadata

- **Semantic Type:** :normalized
- **Unit:** (unitless, 0-1 range)
- **Precision:** 4 decimal places (typical for motor positioning if used)



