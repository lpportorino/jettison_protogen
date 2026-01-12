---
id: cmd.CV.SetAutoFocus
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# SetAutoFocus

**Source:** `jon_shared_cmd_cv.proto`

## Description

*No description yet.*

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

- [[proto/proto/ser.JonGuiDataCV]]


### Related Commands

- [[proto/proto/cmd.HeatCamera.SetAutoFocus]]





## Field Notes


### channel (#1)


#### Metadata

- **Semantic Type:** :enum-label


### value (#2)


#### Metadata

- **Semantic Type:** :enum-label



