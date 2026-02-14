---
id: cmd.CV.DumpStart
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# DumpStart

**Source:** `jon_shared_cmd_cv.proto`

## Description

Starts a CV dump session, switching autofocus and auto-diaphragm logging from 1% sampling to 100% logging rate. Used for debugging computer vision algorithms by capturing detailed frame-by-frame data to TimescaleDB.

When active, the following tables receive full-rate logging:
- `autofocus_log`: 60Hz (up from 0.6Hz)
- `autofocus_events`: AF state transitions
- `auto_diaphragm_log`: 30Hz (up from 0.3Hz)
- `cv_state_pad_audit`: 140/s (up from 1.4/s)
- `cv_cmd_pad_audit`: ~2/s (up from 0.02/s)

Only available in developer mode (factory UI). State is tracked via `ser.JonGuiDataSystem.cv_dumping` boolean field.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

Enable 100% CV logging rate for autofocus/diaphragm debugging


### Related State

- [[proto/ser.JonGuiDataSystem]] (`cv_dumping` field tracks active state)
- [[proto/ser.JonGuiDataCV]] (sharpness metrics and autofocus state)


### Related Commands

- [[proto/cmd.CV.DumpStop]]


### Preconditions

- Developer mode enabled (URL parameter `ui=factory` or hidden unless in factory mode)


### Implementation Notes

The frontend implements this as a toggle button (`jon-cv-dump-button`) that sends DumpStart when off and DumpStop when on. The button shows a pending state for up to 2 seconds while waiting for the `cv_dumping` state to update. Dump sessions are stored in TimescaleDB with session UUIDs for later analysis.



