---
id: cmd.System.StartALl
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# StartALl

**Source:** `jon_shared_cmd_system.proto`

## Description

Triggers startup of all active system subsystems including cameras, sensors, and platform components. Sent from the UI via a "Start All Systems" button and represents the opposite action of the StopALl command.

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

- [[proto/ser.JonGuiDataSystem]]
- [[proto/ser.JonGuiDataCameraDay]]
- [[proto/ser.JonGuiDataCameraHeat]]
- [[proto/ser.JonGuiDataRotary]]
- [[proto/ser.JonGuiDataCompass]]
- [[proto/ser.JonGuiDataLrf]]
- [[proto/ser.JonGuiDataGps]]


### Related Commands

- [[proto/cmd.System.StopALl]]



### Implementation Notes

Typo in proto: 'StartALl' should be 'StartAll'



