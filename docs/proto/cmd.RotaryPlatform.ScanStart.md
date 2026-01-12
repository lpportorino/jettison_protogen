---
id: cmd.RotaryPlatform.ScanStart
proto: jon_shared_cmd_rotary.proto
package: cmd.RotaryPlatform
type: message
---

# ScanStart

**Source:** `jon_shared_cmd_rotary.proto`

## Description

Begins automated execution of a pre-defined scan pattern on the rotary platform, sequentially positioning the platform at each configured scan node with specified zoom, azimuth, elevation, and dwell time parameters.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Starts automated scan pattern execution



### Related Commands

- [[proto/proto/cmd.RotaryPlatform.ScanStop]]
- [[proto/proto/cmd.RotaryPlatform.ScanPause]]
- [[proto/proto/cmd.RotaryPlatform.ScanAddNode]]


### Preconditions

- Rotary platform must be started
- At least one scan node must be defined




