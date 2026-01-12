# JonOpaquePayload (ser.JonOpaquePayload)

**Source:** `jon_shared_data_types.proto`

## Description

State/data message.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| type_uuid | string | 1 | UUIDv7 identifying the payload type (e.g., "019415a9-5c34-7def-8000-000000000001") | - |
| version | JonOpaquePayloadVersion | 2 | Structured version - handler decides compatibility logic | - |
| payload | bytes | 3 | Opaque binary payload | - |

## Usage Context

State data broadcast from backend to clients, typically via WebSocket/WebTransport.

## Related Messages

- See `jon_shared_data_types.proto` for complete context
