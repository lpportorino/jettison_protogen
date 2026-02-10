---
id: cmd.CV.SetAutoFocus
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# SetAutoFocus

**Source:** `jon_shared_cmd_cv.proto`

## Description

Enables or disables computer vision-based automatic focus for either the day or thermal camera channel, routing the command through the CV pipeline for software-controlled focus management. Different from cmd.HeatCamera.SetAutoFocus which is a direct hardware command.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | channel | [[proto/ser.JonGuiDataVideoChannel]] | defined enum value only, not in: 0 |
| 2 | value | bool | - |



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :pending-timeout


### Purpose

Enable/disable auto focus for day or heat camera via computer vision


### Related State

- [[proto/ser.JonGuiDataCV]]


### Related Commands

- [[proto/cmd.HeatCamera.SetAutoFocus]]





## Field Notes


### channel (#1)


#### Metadata

- **Semantic Type:** :enum-label


### value (#2)


#### Metadata

- **Semantic Type:** :enum-label



