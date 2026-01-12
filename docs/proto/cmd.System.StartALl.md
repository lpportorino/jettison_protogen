---
id: cmd.System.StartALl
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StartALl

**Source:** `jon_shared_cmd_system.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Start all system components (cameras, sensors, platform)


### Related State

- [[proto/proto/ser.JonGuiDataSystem]]
- [[proto/proto/ser.JonGuiDataCameraDay]]
- [[proto/proto/ser.JonGuiDataCameraHeat]]
- [[proto/proto/ser.JonGuiDataRotary]]
- [[proto/proto/ser.JonGuiDataCompass]]
- [[proto/proto/ser.JonGuiDataLrf]]
- [[proto/proto/ser.JonGuiDataGps]]


### Related Commands

- [[proto/proto/cmd.System.StopALl]]



### Implementation Notes

Typo in proto: 'StartALl' should be 'StartAll'



