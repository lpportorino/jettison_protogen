---
id: ser.JonOpaquePayload
proto: jon_shared_data_types.proto
package: ser
type: message
---

# JonOpaquePayload

**Source:** `jon_shared_data_types.proto`

## Description

Extensibility container that carries subsystem-specific binary payloads identified by UUIDv7 type markers and semantic versioning, allowing handlers to match payload types and verify version compatibility without the transport layer interpreting the binary data. Appears in both state and command messages as a repeated field to support multiple concurrent subsystem extensions.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | type_uuid | string | pattern: ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ |
| 2 | version | [[proto/ser.JonOpaquePayloadVersion]] | required |
| 3 | payload | bytes | min-len: 1 |



## Interaction

- **Category:** :diagnostic


### Purpose

Opaque binary payload container for extensibility





### Implementation Notes

Not directly used in UI - extension mechanism for custom data



