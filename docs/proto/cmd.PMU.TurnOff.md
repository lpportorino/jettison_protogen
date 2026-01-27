---
id: cmd.PMU.TurnOff
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# TurnOff

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Powers off the PMU hardware. This disables the physical power management circuitry.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout

### Purpose

Powers off the PMU hardware. Monitoring must be stopped (Stop) before powering off.

### Related State

- [[proto/ser.JonGuiDataPMU]]

### Related Commands

- [[proto/cmd.PMU.TurnOn]]
- [[proto/cmd.PMU.Stop]]

### Preconditions

- PMU monitoring should be stopped first




