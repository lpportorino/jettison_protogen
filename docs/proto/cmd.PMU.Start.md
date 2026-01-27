---
id: cmd.PMU.Start
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# Start

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Starts PMU monitoring and control. Enables power monitoring, current sensing, and temperature reporting.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout

### Purpose

Begins PMU monitoring operations. The PMU hardware must be powered on (TurnOn) before monitoring can start.

### Related State

- [[proto/ser.JonGuiDataPMU]]

### Related Commands

- [[proto/cmd.PMU.Stop]]
- [[proto/cmd.PMU.TurnOn]]

### Preconditions

- PMU must be powered on (TurnOn)




