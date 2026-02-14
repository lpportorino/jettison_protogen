---
id: cmd.CV.DumpStop
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# DumpStop

**Source:** `jon_shared_cmd_cv.proto`

## Description

Stops the high-rate computer vision logging session that was previously initiated with DumpStart. When processed, the system returns to 1% sampling rate for CV audit tables (autofocus_log, auto_diaphragm_log, cv_state_pad_audit, cv_cmd_pad_audit) and sets the `data.System.cvDumping` state to false. Creates a dump session record in TimescaleDB with event_type='stop' and calculates session duration. Only available in developer mode (URL parameter ui=factory).

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout
- **Timeout:** 2000ms


### Purpose

End high-rate CV logging session and return to normal 1% sampling



### Related Commands

- [[proto/cmd.CV.DumpStart]]



### Implementation Notes

Used for debugging CV algorithms. The frontend implements this as part of a toggle button pair (jon-cv-dump-button) that shows "Start CV Dump" or "Stop CV Dump" based on the current `cvDumping` state. The button is only visible in developer mode. Uses optimistic UI with a 2-second pending timeout that clears when the state confirmation arrives via the state WebSocket. Dump sessions are recorded in TimescaleDB (bezoar database, dump_sessions table) with session_id, start/stop times, and duration.

### Preconditions

- A dump session must be active (cvDumping == true)
- Developer mode must be enabled (URL parameter ui=factory)

### Related State

- [[proto/ser.JonGuiDataSystem]] - Contains `cvDumping` boolean field



