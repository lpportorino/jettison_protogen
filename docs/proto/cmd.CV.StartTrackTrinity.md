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




## Field Notes


### channel (#1)

Which video pipeline the trinity tracker runs on — see [[proto/ser.JonGuiDataVideoChannel]], `HEAT` or `DAY`. `UNSPECIFIED` (0) is excluded because there is no default to fall back on: the tracker has to be pointed at one pipeline, and since the board is self-locating and there is exactly one of it in a run, this is the command's only mandatory parameter.

**The choice sets the precision regime, not merely the source.** [[proto/ser.TrinityTracking]] records the two focal lengths behind its measured figures — 3517 px on day, 1320 px on heat — and lateral error is `sigma_px * range / focal_px`. So at 0.4 px centre localisation the same board at 10 m resolves to roughly 1.1 mm laterally on day and roughly 3.0 mm on heat, and at 50 m to roughly 5.7 mm against roughly 15.2 mm. Range, already the weak axis on both channels, scales the same way. The consequence is reported on the resulting pose by its per-axis sigmas rather than by anything in this command, so a consumer comparing poses across channels must read those rather than assume one grade of ground truth.



