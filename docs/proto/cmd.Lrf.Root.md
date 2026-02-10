---
id: cmd.Lrf.Root
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# Root

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Root command message for Laser Range Finder (LRF) operations that routes to various LRF subcommands using a oneof field. Commands include starting/stopping the LRF, measuring distances, controlling scanning and refinement modes, managing target designators, enabling fog mode, and requesting meteorological data.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | measure | [[proto/cmd.Lrf.Measure]] | - |
| 2 | scan_on | [[proto/cmd.Lrf.ScanOn]] | - |
| 3 | scan_off | [[proto/cmd.Lrf.ScanOff]] | - |
| 4 | start | [[proto/cmd.Lrf.Start]] | - |
| 5 | stop | [[proto/cmd.Lrf.Stop]] | - |
| 6 | target_designator_off | [[proto/cmd.Lrf.TargetDesignatorOff]] | - |
| 7 | target_designator_on_mode_a | [[proto/cmd.Lrf.TargetDesignatorOnModeA]] | - |
| 8 | target_designator_on_mode_b | [[proto/cmd.Lrf.TargetDesignatorOnModeB]] | - |
| 9 | enable_fog_mode | [[proto/cmd.Lrf.EnableFogMode]] | - |
| 10 | disable_fog_mode | [[proto/cmd.Lrf.DisableFogMode]] | - |
| 11 | set_scan_mode | [[proto/cmd.Lrf.SetScanMode]] | - |
| 12 | new_session | [[proto/cmd.Lrf.NewSession]] | - |
| 13 | get_meteo | [[proto/cmd.Lrf.GetMeteo]] | - |
| 14 | refine_on | [[proto/cmd.Lrf.RefineOn]] | - |
| 15 | refine_off | [[proto/cmd.Lrf.RefineOff]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8, #9, #10, #11, #12, #13, #14, #15




## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :state-machine-menu


### Purpose

Root message container for laser rangefinder commands


### Related State

- [[proto/ser.JonGuiDataLrf]]




### Implementation Notes

Oneof wrapper containing start, stop, measure, scanOn, scanOff, enableFogMode, disableFogMode, targetDesignatorOff, targetDesignatorOnModeA, targetDesignatorOnModeB, getMeteo, etc.



