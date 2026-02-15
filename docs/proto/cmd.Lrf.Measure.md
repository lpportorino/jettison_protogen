---
id: cmd.Lrf.Measure
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# Measure

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Initiates a single laser rangefinder measurement operation, optionally applying fog mode correction if enabled. Sends the appropriate UART bridge command to start a measured distance acquisition.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :sensor
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Trigger laser rangefinder measurement


### Related State

- [[proto/ser.JonGuiDataLrf]] - Contains `is_measuring` flag, `measure_id` sequence number, and `target` with measurement results


### Related Commands

- [[proto/cmd.Lrf.ScanOn]] - Enable continuous scanning mode
- [[proto/cmd.Lrf.ScanOff]] - Disable continuous scanning mode
- [[proto/cmd.Lrf.NewSession]] - Clear previous measurements and start new session
- [[proto/cmd.Lrf.EnableFogMode]] - Enable fog mode correction for measurements


### Preconditions

- LRF must be started
- Not currently measuring (`is_measuring` should be false)


### Implementation Notes

The frontend displays a "Measure" button in the LRF UI panel (`jon-lrf-ui` component). When clicked, the button enters a pending state for up to 2 seconds while waiting for measurement completion. The measurement result populates `ser.JonGuiDataLrf.target` with:
- Target GPS coordinates (latitude, longitude, altitude)
- 2D horizontal distance
- 3D slant range distance

If the measurement misses (no return), `distance2d` will be 0 and the target widget displays "MISS!".


