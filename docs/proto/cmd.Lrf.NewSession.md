---
id: cmd.Lrf.NewSession
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# NewSession

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Increments the LRF session counter to create a new targeting session. Each session groups related targeting operations together, and the command atomically increments a persistent session ID counter displayed in the UI.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :actuator
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Start new LRF (Laser Range Finder) measurement session


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/proto/proto/proto/proto/cmd.Lrf.Measure]]
- [[proto/proto/proto/proto/proto/proto/cmd.Lrf.Start]]



### Implementation Notes

Begins fresh measurement session, clearing previous results



