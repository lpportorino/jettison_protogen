---
id: ser.JonOpaquePayloadVersion
proto: jon_shared_data_types.proto
package: ser
type: message
---

# JonOpaquePayloadVersion

**Source:** `jon_shared_data_types.proto`

## Description

Structured version triplet (major, minor, build) that enables version-aware handling of opaque subsystem-specific payloads, supporting both build numbers and millisecond-precision timestamps for the build field. Allows handlers to perform version compatibility checks through simple numeric comparisons without string parsing.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | major | uint32 | - |
| 2 | minor | uint32 | - |
| 3 | build | uint64 | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator


### Purpose

Version information for opaque payload extension mechanism





### Implementation Notes

Part of platform-agnostic extension system for custom message payloads



