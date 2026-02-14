---
id: cmd.Frozen
proto: jon_shared_cmd.proto
package: cmd
type: message
---

# Frozen

**Source:** `jon_shared_cmd.proto`

## Description

A session lifecycle command sent by the frontend when the browser tab is being closed or becomes hidden. This parameterless message notifies the backend that the client session is frozen/suspended. The command is explicitly allowed in readonly mode (alongside Ping) and is sent immediately without buffering to ensure timely notification even during page unload.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget

### Purpose

Signals to the backend that the client session is being suspended or terminated. Sent automatically when the browser tab is closed (beforeunload event) or hidden (visibilitychange to hidden). This allows the backend to clean up session state or stop sending data to disconnected clients.

### Related Commands

- [[cmd.Ping]] - Also allowed in readonly mode, used for keepalive

### Notes

- Sent without command buffering for immediate delivery
- Explicitly whitelisted in readonly mode
- No response expected from backend



