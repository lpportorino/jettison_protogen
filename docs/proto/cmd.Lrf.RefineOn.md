---
id: cmd.Lrf.RefineOn
proto: jon_shared_cmd_lrf.proto
package: cmd.Lrf
type: message
---

# RefineOn

**Source:** `jon_shared_cmd_lrf.proto`

## Description

Enables LRF refine mode for precise targeting adjustments. When activated, sets `is_refining` to true in the LRF state, enabling fine-grained control for accurate distance measurements and target designation. The refine button appears in the UI command palette while this mode is active.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :settings
- **UI Pattern:** :toggle
- **Feedback:** :fire-and-forget


### Purpose

Enables LRF refine mode for precision targeting and distance measurements


### Related State

- [[proto/ser.JonGuiDataLrf]] - `is_refining` field reflects current refine mode state


### Related Commands

- [[proto/cmd.Lrf.RefineOff]] - Disables refine mode (toggle pair)
- [[proto/cmd.Lrf.Measure]] - Take distance measurement (often used with refine mode)


### Preconditions

- LRF must be started (`is_started` = true)


### Implementation Notes

Refine mode provides higher precision measurements for accurate target designation. The frontend displays a refine button in the command palette that allows the operator to disable refine mode when active. This is part of a toggle pair with RefineOff - only one state can be active at a time.




