---
id: cmd.PMU.Stop
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# Stop

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Stops PMU monitoring and control. Disables power monitoring while keeping hardware powered.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Stops PMU monitoring operations. The PMU hardware remains powered but monitoring is disabled.


### Related State

- [[proto/ser.JonGuiDataPMU]]


### Related Commands

- [[proto/cmd.PMU.Start]]





