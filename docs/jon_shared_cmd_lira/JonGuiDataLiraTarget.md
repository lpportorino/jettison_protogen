# JonGuiDataLiraTarget (cmd.Lira.JonGuiDataLiraTarget)

**Source:** `jon_shared_cmd_lira.proto`

## Description

Requests data or status information.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| timestamp | int64 | 1 | - | >= -180, <= 180 |
| target_longitude | double | 2 | - | >= -180, <= 180 |
| target_latitude | double | 3 | - | >= -90, <= 90 |
| target_altitude | double | 4 | - | >= -430.0, <= 100000.0 |
| target_azimuth | double | 5 | - | >= 0, < 360 |
| target_elevation | double | 6 | - | >= -90, <= 90 |
| distance | double | 7 | - | >= 0 |
| uuid_part1 | int32 | 8 | UUID as four fixed32 values (128 bits total) | - |
| uuid_part2 | int32 | 9 | - | - |
| uuid_part3 | int32 | 10 | - | - |
| uuid_part4 | int32 | 11 | - | - |

## Usage Context

Command sent from clients to control system behavior or request operations.

## Related Messages

- See `jon_shared_cmd_lira.proto` for complete context
