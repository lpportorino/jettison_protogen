---
id: cmd.Lrf.NewSession
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# NewSession

**Source:** `jon_shared_cmd_lrf.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataLrf]]


### Related Commands

- [[proto/proto/cmd.Lrf.Measure]]
- [[proto/proto/cmd.Lrf.Start]]



### Implementation Notes

Begins fresh measurement session, clearing previous results



