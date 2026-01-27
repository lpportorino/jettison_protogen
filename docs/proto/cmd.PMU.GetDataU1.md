---
id: cmd.PMU.GetDataU1
proto: jon_shared_cmd_pmu.proto
package: cmd.PMU
type: message
---

# GetDataU1

**Source:** `jon_shared_cmd_pmu.proto`

## Description

Requests sensor data from Unit 1. Retrieves readings from the U1 sensor module.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :sensor
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Requests data from the U1 sensor unit. Response is delivered via ser.JonGuiDataPMU.


### Related State

- [[proto/ser.JonGuiDataPMU]]



### Preconditions

- PMU must be started




