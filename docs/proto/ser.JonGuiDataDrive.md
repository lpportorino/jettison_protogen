---
id: ser.JonGuiDataDrive
proto: jon_shared_data_drive.proto
package: ser
type: message
---

# JonGuiDataDrive

**Source:** `jon_shared_data_drive.proto`

## Description

Status of the sandboxed drive programs (scan, point-of-interest look-at, transport park) hosted by eutropia's drive host. Exactly one program may own the rotary platform at a time; this message reports which one, its lifecycle state and phase, and the program-agnostic progress counters. Scan-specific readbacks (`is_scanning`, `scan_target`, `current_scan_node`) remain on `JonGuiDataRotary` and are written by the same host. Published on every state tick from the owning program's status block; all zeros when no program is loaded.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | program | [[proto/ser.JonGuiDataDriveProgram]] | defined enum value only |
| 2 | state | [[proto/ser.JonGuiDataDriveState]] | defined enum value only |
| 3 | phase | int32 | >= 0 |
| 4 | error_code | int32 | >= 0 |
| 5 | poi_index | int32 | >= -1, <= 9 |
| 6 | park_in_progress | bool | - |
| 7 | tables_generation | uint32 | - |
| 8 | wasm_version | int32 | >= 0 |
| 9 | rejected_commands | uint32 | - |




## Field Notes


### program (#1)

Program currently owning the platform; NONE when idle.


### state (#2)

Lifecycle state of the owning program. FAULT carries `error_code`; DONE is terminal for park (until the next EnterTransport) and for a completed POI look-at.


### phase (#3)

Program-defined phase index. Park: 0 PRE, 1 EL_APPROACH, 2 AZ_SLEW, 3 EL_DESCEND, 4 FORWARD, 5 DONE, 6 UNPARK_SETTLE. Scan/POI: 0 idle, 1 moving, 2 lingering.


#### Metadata

- **Semantic Type:** :count


### error_code (#4)

Drive error code (0 none, 1 precondition, 2 exclusion, 3 deadline, 4 aborted by operator, 5 tables stale, 6 held-axis drift, 7 park latch could not be released, 8 empty POI slot).


#### Metadata

- **Semantic Type:** :count


### poi_index (#5)

Slot being looked at by the POI program; -1 when the POI program is not active.


#### Metadata

- **Semantic Type:** :count


### park_in_progress (#6)

True from EnterTransport receipt until the transport latch has been forwarded to the rotary driver.


### tables_generation (#7)

Generation counter of the REST-derived scan-node and POI tables currently loaded into the programs' environment; bumps on every refresh.


#### Metadata

- **Semantic Type:** :count


### wasm_version (#8)

Version code of the owning program's WASM module (major*10000 + minor*100 + patch); 0 when no module is loaded.


#### Metadata

- **Semantic Type:** :count


### rejected_commands (#9)

Commands the host refused since the last program start: out of range, non-finite, or emitted by a program that does not own the platform.


#### Metadata

- **Semantic Type:** :count



