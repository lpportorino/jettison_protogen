# JonGuiDataRecOsd (ser.JonGuiDataRecOsd)

**Source:** `jon_shared_data_rec_osd.proto`

## Description

State/data message.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| screen | JonGuiDataRecOsdScreen | 1 | - | must not be 0/UNSPECIFIED, must be defined enum value |
| heat_osd_enabled | bool | 2 | - | - |
| day_osd_enabled | bool | 3 | - | - |
| heat_crosshair_offset_horizontal | int32 | 4 | - | - |
| heat_crosshair_offset_vertical | int32 | 5 | - | - |
| day_crosshair_offset_horizontal | int32 | 6 | - | - |
| day_crosshair_offset_vertical | int32 | 7 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_rec_osd.proto` for complete context
