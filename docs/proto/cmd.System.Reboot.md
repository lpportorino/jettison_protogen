---
id: cmd.System.Reboot
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# Reboot

**Source:** `jon_shared_cmd_system.proto`

## Description

Restarts the system after gracefully shutting down services, allowing users to reconnect after the system comes back online. Unlike PowerOff which completely shuts down requiring manual restart, Reboot executes `/sbin/reboot` and the system automatically restarts.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :lifecycle
- **UI Pattern:** :action-button
- **Feedback:** :fire-and-forget


### Purpose

Reboot the entire system





### Implementation Notes

Critical operation - requires user confirmation



