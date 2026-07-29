---
id: ser.TrinityAltPose
proto: opaque/trinity_tracking.proto
package: ser
type: message
---

# TrinityAltPose

**Source:** `opaque/trinity_tracking.proto`

## Description

The pose the disambiguator did **not** choose.

Present when the near-affine two-fold ambiguity admitted a second solution that reprojection error
could not separate from the chosen one. It is carried so a consumer can see the fork and apply its
own prior — a scale prior, a temporal track, or an external range — rather than inheriting a
selection it cannot audit.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | position_x_m | double | - |
| 2 | position_y_m | double | - |
| 3 | position_z_m | double | - |
| 4 | quat_w | double | - |
| 5 | quat_x | double | - |
| 6 | quat_y | double | - |
| 7 | quat_z | double | - |
| 8 | reprojection_rms_px | double | - |




## Field Notes


### position_x_m (#1)

Lateral position of the board origin in the alternate solution, along **+X of the camera frame — to the right**, in metres. The frame is the one [[proto/ser.TrinityTracking]] declares: right-handed, +X right, +Y down, +Z forward along the optical axis. Same quantity, same units, same frame as the chosen pose's `position_x_m` — this message differs from that one in status, not in meaning.

**Read `TrinityTracking.ambiguity_resolved` before reading any field here.** These numbers describe the solution the disambiguator REJECTED. They are present so a consumer can see the fork and apply its own prior, not so it can average, interpolate, or silently prefer them.


### position_y_m (#2)

Lateral position of the alternate solution along **+Y — DOWNWARD**, in metres. The downward sense is the camera frame's, and it is the opposite of the `cmd.*` pointer plane's +y UP; a transform written for that plane mirrors this one vertically while producing entirely plausible output.


### position_z_m (#3)

Range from the camera to the board origin in the alternate solution, in metres, along +Z.

**This field is NOT bounded `> 0`, where the chosen pose's `position_z_m` is** — and that asymmetry is the sharpest thing to know about this message. On [[proto/ser.TrinityTracking]] the bound rejects a degenerate solve that would place the board at or behind the centre of projection. Here nothing rejects it. A consumer that promotes the alternate — the whole reason it is carried — must impose that check itself, because validation did not.

The two-fold ambiguity is largely an orientation fork, so this value is often close to the chosen pose's range rather than wildly different. Closeness is not corroboration: it is what makes the pair hard to separate in the first place.


### quat_w (#4)

Scalar part of the alternate solution's quaternion, rotating **board coordinates into camera coordinates** — the same convention as [[proto/ser.TrinityTracking]]'s `quat_w`.

**Unlike the chosen pose's components, these carry no `[-1, 1]` bound**, and the producer's `|q| = 1` within 1e-9 guarantee is stated for the chosen pose. A consumer building a rotation matrix from the alternate should normalise defensively rather than assume it arrives unit.

Orientation is where the two solutions actually diverge. The near-affine two-fold ambiguity is a reflection of the board's plane, so the alternate is typically a substantially different attitude at a similar position — which is why `TrinityTracking.sigma_orientation_mrad` is the axis whose observability fails first, and why a consumer that only needs position may be able to proceed while one that needs attitude cannot.


### quat_x (#5)

First element of the alternate quaternion's vector part, in the camera frame's axis order — +X right. See `quat_w` for the convention, the missing bound, and the missing normalisation guarantee.


### quat_y (#6)

Second element of the alternate quaternion's vector part — +Y down. See `quat_w` for the convention, the missing bound, and the missing normalisation guarantee.


### quat_z (#7)

Third element of the alternate quaternion's vector part — +Z forward, along the optical axis. See `quat_w` for the convention, the missing bound, and the missing normalisation guarantee.


### reprojection_rms_px (#8)

Root-mean-square of the alternate solution's reprojection residuals, in pixels — how closely THIS pose reprojects the board's known geometry onto the observed image points.

**IT IS NOT A TIEBREAKER, and comparing it against the chosen pose's `reprojection_rms_px` is the single failure this message is shaped to prevent.** The near-affine two-fold ambiguity is by definition the case where reprojection error CANNOT separate the two poses; the two residuals are therefore expected to be close, and whichever is smaller is smaller by noise. Selecting on that comparison produces a confident answer that is wrong about half the time at range — the exact outcome carrying the alternate exists to avoid.

It is reported so a consumer can see that both solutions fit, which is the evidence FOR the ambiguity rather than a means of resolving it. Resolve with an independent constraint instead: a scale prior, temporal continuity across ticks, or a rangefinder distance (`TrinityTracking.range_source`). `TrinityTracking.ambiguity_resolved` is the producer's own statement about whether the fork was closed, and it is the field to read.

Unlike the chosen pose's `reprojection_rms_px`, this one carries **no `>= 0` bound**, so a negative value is representable here and would be a producer defect rather than a rejected message.



