---
id: cmd.Compass.Start
proto: jon_shared_cmd_compass.proto
package: cmd.Compass
type: message
---

# Start

**Source:** `jon_shared_cmd_compass.proto`

## Description

Initializes and powers on the compass/IMU sensor subsystem, transitioning it from stopped to started state and enabling azimuth, elevation, and bank angle readings. Sets device_status to STARTED in the manifold global state.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

_Parameterless command - no fields required._

## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms

### Purpose

Start compass/IMU sensor system


### Related State

- [[proto/ser.JonGuiDataCompass]]


### Related Commands

- [[proto/cmd.Compass.Stop]]





