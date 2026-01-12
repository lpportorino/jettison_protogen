# ClientLogBatch (jon.logs.ClientLogBatch)

**Source:** `jon_client_logs.proto`

## Description

Client logging data.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| version | uint32 | 1 | Protocol version for future compatibility | >= 1, <= 1 |
| entries | repeated ClientLogEntry | 2 | - | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_client_logs.proto` for complete context
