---
id: cmd.Ping
proto: jon_shared_cmd.proto
package: cmd
type: message
---

# Ping

**Source:** `jon_shared_cmd.proto`

## Description

A lightweight keepalive command that allows clients to update their session heartbeat timestamp, enabling the server to detect disconnected sessions and automatically halt ongoing operations like camera movements or scanning.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|



## Interaction

- **Category:** :diagnostic
- **UI Pattern:** :action-button
- **Feedback:** :pending-timeout


### Purpose

Test connectivity and measure round-trip latency







