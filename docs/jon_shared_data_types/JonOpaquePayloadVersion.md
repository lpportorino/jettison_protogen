# JonOpaquePayloadVersion (ser.JonOpaquePayloadVersion)

**Source:** `jon_shared_data_types.proto`

## Description

State/data message.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| major | uint32 | 1 | - | - |
| minor | uint32 | 2 | - | - |
| build | uint64 | 3 | - | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_types.proto` for complete context
