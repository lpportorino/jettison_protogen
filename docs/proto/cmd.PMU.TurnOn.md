---
id: cmd.PMU.TurnOn
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# TurnOn

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Powers on the PMU hardware. This enables the physical power management circuitry.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Powers on the PMU hardware. After powering on, call Start to begin monitoring.


### Related State

- [[proto/ser.JonGuiDataPMU]]


### Related Commands

- [[proto/cmd.PMU.TurnOff]]
- [[proto/cmd.PMU.Start]]





