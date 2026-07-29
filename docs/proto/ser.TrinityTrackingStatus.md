---
id: ser.TrinityTrackingStatus
proto: opaque/trinity_tracking.proto
package: ser
type: enum
---

# TrinityTrackingStatus

**Source:** `opaque/trinity_tracking.proto`

## Description

Tracking state for this tick.

`DEGRADED` is the load-bearing member: the board was found but is too small or too oblique for a
pose, so position may be approximate while orientation is **not** valid. A small planar target
loses orientation observability long before it loses position, and a single "tracking" state would
force the consumer to trust both or neither.

`BOARD_MISMATCH` fires when `StartTrackTrinity.expect_board` was set and a different board was
detected, instead of silently producing a pose against different geometry.

**ABSENCE AND `IDLE` ARE DIFFERENT FACTS.** The payload is published whenever the tracker PROCESS is up, including when it is not tracking — that is what `IDLE` is for. `IDLE` present means the tracker is up and deliberately not tracking; the payload being ABSENT means the producer is down, has not published yet, or the payload was dropped. A consumer that reads "no payload" as "not tracking" reports a crashed tracker as a stopped one — one reading for two states that need opposite responses.

**A consumer that only needs "is tracking on" never has to reach this enum.**
`trinity_tracking_active` (#90) on [[proto/ser.JonGuiDataCV]] answers that on the STATE plane, with
no opaque payload to decode: `LOCKED`, `SEARCHING`, `DEGRADED` and `BOARD_MISMATCH` are all `true`
there and `IDLE` is `false`. It exists for the toggle affordance and is not a substitute — this
enum remains the authoritative value for anything that must distinguish a lock from a search, a
degraded solve, or a board mismatch.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | TRINITY_TRACKING_STATUS_UNSPECIFIED | Proto3 zero default, never legitimately emitted: `TrinityTracking.status` (#3) carries `defined_only` + `not_in:[0]`, so a payload leaving it unset does not pass the sender's validation gate. |
| 1 | TRINITY_TRACKING_STATUS_LOCKED | Tracking; the pose fields are valid — both position (`position_x_m`/`y`/`z`) and the orientation quaternion may be read. |
| 2 | TRINITY_TRACKING_STATUS_SEARCHING | No board found this tick. The pose fields are stale or unset and must NOT be read as a pose; only the timestamp and status carry meaning. |
| 3 | TRINITY_TRACKING_STATUS_DEGRADED | Board found but too small or too oblique to solve a pose: position may be approximate and orientation is NOT valid. A small planar target loses orientation observability long before it loses position, so this state exists to let a consumer keep the position and discard the quaternion rather than trusting or discarding both. |
| 4 | TRINITY_TRACKING_STATUS_BOARD_MISMATCH | The tracker is running but the board it detected is not the one `cmd.CV.StartTrackTrinity.expect_board` requested. Reachable only when that optional field was SET; emitted instead of a pose, because a pose computed against different geometry is wrong by a scale factor and looks entirely plausible. |
| 5 | TRINITY_TRACKING_STATUS_IDLE | Up, and NOT tracking — awaiting `StartTrackTrinity`, or stopped by `StopTrackTrinity`. Pose, sigma and observability fields are meaningless in this state and MUST NOT be read; only `board_version` and `capture_time_ns` stay valid. **This is the value a consumer polls to answer "are we tracking?"** — see the note above on why payload ABSENCE cannot answer it. |

