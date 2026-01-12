# JonGuiDataLrf (ser.JonGuiDataLrf)

**Source:** `jon_shared_data_lrf.proto`

## Description

State data for Lrf subsystem.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| is_scanning | bool | 1 | - | - |
| is_measuring | bool | 2 | - | - |
| measure_id | int32 | 3 | - | - |
| target | JonGuiDataTarget | 4 | - | must be defined enum value |
| pointer_mode | JonGuiDatatLrfLaserPointerModes | 5 | - | must be defined enum value |
| fogModeEnabled | bool | 6 | - | - |
| is_refining | bool | 7 | - | - |
| is_continuous_measuring | bool | 8 | - | - |
| is_started | bool | 9 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_lrf.proto` for complete context
