---
id: cmd.PMU.PowerOff
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# PowerOff

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Initiates a complete system power off. This will shut down the entire system including the compute module.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|

## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget

### Purpose

Triggers a full system power off. This is a one-way operation - the system will not respond after this command.

### Related State

- [[proto/ser.JonGuiDataPMU]]

### Preconditions

- User confirmation recommended before executing




