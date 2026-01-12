---
id: ser.JonGuiDataLrf
proto: jon_shared_data_lrf.proto
package: ser
type: message
---

# JonGuiDataLrf

**Source:** `jon_shared_data_lrf.proto`

## Description

Encapsulates the operational state of a Laser Range Finder (LRF) device, tracking scanning/measuring modes, measurement progress, laser pointer modes, fog mode, refinement status, and targeting data including precise georeferenced measurements with target/observer coordinates and distances.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | is_scanning | bool | - |
| 2 | is_measuring | bool | - |
| 3 | measure_id | int32 | >= 0 |
| 4 | target | [[proto/ser.JonGuiDataTarget]] | - |
| 5 | pointer_mode | [[proto/ser.JonGuiDatatLrfLaserPointerModes]] | defined enum value only |
| 6 | fogModeEnabled | bool | - |
| 7 | is_refining | bool | - |
| 8 | is_continuous_measuring | bool | - |
| 9 | is_started | bool | - |



## Interaction

- **Category:** :sensor
- **UI Pattern:** :indicator
- **Feedback:** :poll-confirm


### Purpose

Laser rangefinder measurement data and status



### Related Commands

- [[proto/proto/proto/proto/proto/cmd.Lrf.Measure]]
- [[proto/proto/proto/proto/proto/cmd.Lrf.ScanOn]]
- [[proto/proto/proto/proto/proto/cmd.Lrf.ScanOff]]



### Implementation Notes

Displays distance measurements, scan status, and LRF operational state



