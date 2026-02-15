---
id: cmd.RotaryPlatform.Root
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# Root

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Root message for rotary platform commands that routes various control operations via a oneof field, including motion control (start, stop, halt), axis manipulation (azimuth, elevation), coordinate-based targeting (GPS, NDC), and scan operations.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | start | [[proto/cmd.RotaryPlatform.Start]] | - |
| 2 | stop | [[proto/cmd.RotaryPlatform.Stop]] | - |
| 3 | axis | [[proto/cmd.RotaryPlatform.Axis]] | - |
| 4 | set_platform_azimuth | [[proto/cmd.RotaryPlatform.SetPlatformAzimuth]] | - |
| 5 | set_platform_elevation | [[proto/cmd.RotaryPlatform.SetPlatformElevation]] | - |
| 6 | set_platform_bank | [[proto/cmd.RotaryPlatform.SetPlatformBank]] | - |
| 7 | halt | [[proto/cmd.RotaryPlatform.Halt]] | - |
| 8 | set_use_rotary_as_compass | [[proto/cmd.RotaryPlatform.setUseRotaryAsCompass]] | - |
| 9 | rotate_to_gps | [[proto/cmd.RotaryPlatform.RotateToGPS]] | - |
| 10 | set_origin_gps | [[proto/cmd.RotaryPlatform.SetOriginGPS]] | - |
| 11 | set_mode | [[proto/cmd.RotaryPlatform.SetMode]] | - |
| 12 | rotate_to_ndc | [[proto/cmd.RotaryPlatform.RotateToNDC]] | - |
| 13 | scan_start | [[proto/cmd.RotaryPlatform.ScanStart]] | - |
| 14 | scan_stop | [[proto/cmd.RotaryPlatform.ScanStop]] | - |
| 15 | scan_pause | [[proto/cmd.RotaryPlatform.ScanPause]] | - |
| 16 | scan_unpause | [[proto/cmd.RotaryPlatform.ScanUnpause]] | - |
| 17 | get_meteo | [[proto/cmd.RotaryPlatform.GetMeteo]] | - |
| 18 | scan_prev | [[proto/cmd.RotaryPlatform.ScanPrev]] | - |
| 19 | scan_next | [[proto/cmd.RotaryPlatform.ScanNext]] | - |
| 20 | scan_refresh_node_list | [[proto/cmd.RotaryPlatform.ScanRefreshNodeList]] | - |
| 21 | scan_select_node | [[proto/cmd.RotaryPlatform.ScanSelectNode]] | - |
| 22 | scan_delete_node | [[proto/cmd.RotaryPlatform.ScanDeleteNode]] | - |
| 23 | scan_update_node | [[proto/cmd.RotaryPlatform.ScanUpdateNode]] | - |
| 24 | scan_add_node | [[proto/cmd.RotaryPlatform.ScanAddNode]] | - |
| 25 | halt_with_ndc | [[proto/cmd.RotaryPlatform.HaltWithNDC]] | - |


## Oneofs


### cmd (required)

Fields: #1, #2, #3, #4, #5, #6, #7, #8, #9, #10, #11, #12, #13, #14, #15, #16, #17, #18, #19, #20, #21, #22, #23, #24, #25




## Interaction

- **Category:** :actuator
- **UI Pattern:** :command-router
- **Feedback:** :fire-and-forget


### Purpose

Container for rotary platform commands


### Related State

- [[proto/ser.JonGuiDataRotary]]




### Implementation Notes

Root message containing azimuth/elevation and scan commands



## Field Notes


### start (#1)

See [[proto/cmd.RotaryPlatform.Start]]


### stop (#2)

See [[proto/cmd.RotaryPlatform.Stop]]


### axis (#3)

See [[proto/cmd.RotaryPlatform.Axis]]


### set_platform_azimuth (#4)

See [[proto/cmd.RotaryPlatform.SetPlatformAzimuth]]


### set_platform_elevation (#5)

See [[proto/cmd.RotaryPlatform.SetPlatformElevation]]


### set_platform_bank (#6)

See [[proto/cmd.RotaryPlatform.SetPlatformBank]]


### halt (#7)

See [[proto/cmd.RotaryPlatform.Halt]]


### set_use_rotary_as_compass (#8)

See [[proto/cmd.RotaryPlatform.setUseRotaryAsCompass]]


### rotate_to_gps (#9)

See [[proto/cmd.RotaryPlatform.RotateToGPS]]


### set_origin_gps (#10)

See [[proto/cmd.RotaryPlatform.SetOriginGPS]]


### set_mode (#11)

See [[proto/cmd.RotaryPlatform.SetMode]]


### rotate_to_ndc (#12)

See [[proto/cmd.RotaryPlatform.RotateToNDC]]


### scan_start (#13)

See [[proto/cmd.RotaryPlatform.ScanStart]]


### scan_stop (#14)

See [[proto/cmd.RotaryPlatform.ScanStop]]


### scan_pause (#15)

See [[proto/cmd.RotaryPlatform.ScanPause]]


### scan_unpause (#16)

See [[proto/cmd.RotaryPlatform.ScanUnpause]]


### get_meteo (#17)

See [[proto/cmd.RotaryPlatform.GetMeteo]]


### scan_prev (#18)

See [[proto/cmd.RotaryPlatform.ScanPrev]]


### scan_next (#19)

See [[proto/cmd.RotaryPlatform.ScanNext]]


### scan_refresh_node_list (#20)

See [[proto/cmd.RotaryPlatform.ScanRefreshNodeList]]


### scan_select_node (#21)

See [[proto/cmd.RotaryPlatform.ScanSelectNode]]


### scan_delete_node (#22)

See [[proto/cmd.RotaryPlatform.ScanDeleteNode]]


### scan_update_node (#23)

See [[proto/cmd.RotaryPlatform.ScanUpdateNode]]


### scan_add_node (#24)

See [[proto/cmd.RotaryPlatform.ScanAddNode]]


### halt_with_ndc (#25)

See [[proto/cmd.RotaryPlatform.HaltWithNDC]]



