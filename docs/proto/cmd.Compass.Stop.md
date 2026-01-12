---
id: cmd.Compass.Stop
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# Stop

**Source:** `jon_shared_cmd_compass.proto`

## Description

Stops the compass/IMU sensor subsystem and powers down the device, preventing heading and orientation readings until restarted. Stopping the compass also prevents calibration operations from being initiated.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Stops the compass subsystem


### Related State

- [[proto/proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/proto/cmd.Compass.Start]]





