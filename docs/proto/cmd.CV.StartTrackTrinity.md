---
id: cmd.CV.StartTrackTrinity
proto: jon_shared_cmd_cv.proto
package: cmd.CV
type: message
---

# StartTrackTrinity

**Source:** `jon_shared_cmd_cv.proto`

## Description

Begin tracking the Ring-Trinity golden fiducial board.

Unlike [[proto/cmd.CV.StartTrackNDC]] there is **no seed point**, and that is the board's whole
purpose: it is self-locating from its own geometry, so the operator does not have to put a cursor
on it. There is exactly one board in a run, so nothing needs disambiguating.

`expect_board` is optional. Unset means "track whatever Ring-Trinity board you find". When set, a
mismatch is reported as `TRINITY_TRACKING_STATUS_BOARD_MISMATCH` instead of yielding a pose
computed against different geometry — which would be wrong by a scale factor and look entirely
plausible.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | channel | [[proto/ser.JonGuiDataVideoChannel]] | defined enum value only, not in: 0 |
| 2 | expect_board | [[proto/ser.TrinityBoardVersion]] | - |



## Interaction

- **Category:** :actuator
- **UI Pattern:** :toggle
- **Feedback:** :poll-confirm


### Purpose

Starts the Ring-Trinity board tracker on one video channel. Paired with [[proto/cmd.CV.StopTrackTrinity]]; the two back a single toggle rather than two independent buttons.


### Related State

- [[proto/ser.JonGuiDataCV#trinity_tracking_active]]
- [[proto/ser.TrinityTracking]]
- [[proto/ser.TrinityTrackingStatus]]


### Related Commands

- [[proto/cmd.CV.StopTrackTrinity]]



### Implementation Notes

**The toggle reads back from the STATE plane.** `JonGuiDataCV.trinity_tracking_active` (#90) is `true` while the tracker is running and `false` once [[proto/cmd.CV.StopTrackTrinity]] has stopped it, so a control can reflect this command's effect without decoding an opaque payload. It is a single bit and says nothing about lock quality: `LOCKED`, `SEARCHING`, `DEGRADED` and `BOARD_MISMATCH` are all `true` there.

**The pose and the detailed status travel on the other plane.** [[proto/ser.TrinityTracking]] rides `JonGUIState.opaque_payloads` and reaches the OSD overlay, which renders the metric pose; a consumer that must distinguish a lock from a search, a degraded solve, or the board mismatch this command's `expect_board` can provoke reads [[proto/ser.TrinityTrackingStatus]] from that payload.



## Field Notes


### channel (#1)

Which video pipeline the trinity tracker runs on — see [[proto/ser.JonGuiDataVideoChannel]], `HEAT` or `DAY`. `UNSPECIFIED` (0) is excluded because there is no default to fall back on: the tracker has to be pointed at one pipeline, and since the board is self-locating and there is exactly one of it in a run, this is the command's only mandatory parameter.

**The choice sets the precision regime, not merely the source.** [[proto/ser.TrinityTracking]] records the two focal lengths behind its measured figures — 3517 px on day, 1320 px on heat — and lateral error is `sigma_px * range / focal_px`. So at 0.4 px centre localisation the same board at 10 m resolves to roughly 1.1 mm laterally on day and roughly 3.0 mm on heat, and at 50 m to roughly 5.7 mm against roughly 15.2 mm. Range, already the weak axis on both channels, scales the same way. The consequence is reported on the resulting pose by its per-axis sigmas rather than by anything in this command, so a consumer comparing poses across channels must read those rather than assume one grade of ground truth.


### expect_board (#2)

Which board the tracker should expect. **Optional**: unset means "track whatever Ring-Trinity board you find". Optionality here is proto3 message presence — [[proto/ser.TrinityBoardVersion]] carries no `required` constraint on this field, so unset and set are distinguishable without a sentinel.

**Setting it converts a silent scale error into a reported state.** With no expectation the tracker solves against whatever geometry it detects; a pose computed against a different board is wrong by a scale factor and looks entirely plausible, because every quantity in [[proto/ser.TrinityTracking]] is scaled by the board's real dimensions and none of them looks out of range. With an expectation the mismatch surfaces as `TRINITY_TRACKING_STATUS_BOARD_MISMATCH` instead. Populate it whenever the pose is being used as ground truth; leaving it unset is a convenience, not a default worth keeping when the numbers matter.

**BOARD_MISMATCH still counts as RUNNING, and that is the trap this field creates.** `JonGuiDataCV.trinity_tracking_active` (#90) collapses `LOCKED`, `SEARCHING`, `DEGRADED` and `BOARD_MISMATCH` all to `true`, so a toggle that reads only that bool shows this command succeeding while the tracker is reporting the wrong board. The mismatch is visible only in `status` on the [[proto/ser.TrinityTracking]] opaque payload — so a consumer that sets `expect_board` has, by doing so, taken on the obligation to read that payload, since the STATE plane cannot express the answer it just asked for.

**The schema does not state which components are matched.** [[proto/ser.TrinityBoardVersion]] carries both a human-readable `family`/`major`/`minor` tuple and the authoritative `geometry_sha256`, and nothing here says the tracker compares one, the other, or both. A consumer needing the strict check should populate the digest and additionally confirm the `board_version` reported back on the resulting [[proto/ser.TrinityTracking]] payload, rather than treating the absence of `BOARD_MISMATCH` as proof the digest matched.



