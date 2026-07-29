---
id: ser.TrinityTracking
proto: opaque/trinity_tracking.proto
package: ser
type: message
---

# TrinityTracking

**Source:** `opaque/trinity_tracking.proto`

## Description

Full-precision pose of the Ring-Trinity golden fiducial board, injected into
`JonGUIState.opaque_payloads` by the trinity tracker at track rate.

Unlike the SAM tracking payloads — which carry NDC in `[-1, 1]` because a bounding box is a
screen artifact — this carries a **metric** pose in metres plus a unit quaternion. The board is
the ground-truth judge other measurements are scored against, so quantising it to screen
coordinates would destroy the precision it exists to provide.

**Precision is anisotropic, and the encoding is not the limit.** A `double` resolves to ~1.8e-12
mm at 10 m — about twelve orders of magnitude finer than a millimetre. The optics bind instead.
Lateral error is `sigma_px * range / focal_px`: at 0.4 px centre localisation that is ~1.1 mm at
10 m on day (focal_px 3517) and ~3.0 mm on heat (focal_px 1320). Range from apparent size obeys
`dZ/Z = dS/S` and is far worse — ~27 mm at 10 m on day, degrading with the square of range.
Millimetre-class is therefore reachable laterally at short range and **not** reachable in range
from board extent alone, which is why `sigma_range_m` is separate from `sigma_position_m` and why
`range_source` exists.

**The two-fold ambiguity is surfaced rather than hidden.** A planar target under near-affine
projection admits two poses that reprojection error cannot separate, so a payload emitting only
the chosen one is silently wrong about half the time at range. `alternate` carries the rejected
solution and `ambiguity_resolved` says whether the fork was closed.

**The activity question is answered on the other plane.** This payload rides
`JonGUIState.opaque_payloads` and is decoded only by consumers that handle its type — the OSD
overlay, which renders the pose. A consumer that only needs to know whether the tracker is RUNNING
reads `trinity_tracking_active` (#90) on [[proto/ser.JonGuiDataCV]] instead, which sits on the
STATE plane and needs no payload decode. That flag is a single bit: it collapses `LOCKED`,
`SEARCHING`, `DEGRADED` and `BOARD_MISMATCH` to `true`, so `status` here stays the authoritative and
richer value and nothing about it is superseded. The two are different contracts for different
consumers, not two copies of one fact.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | board_version | [[proto/ser.TrinityBoardVersion]] | required |
| 2 | capture_time_ns | uint64 | > 0 |
| 3 | status | [[proto/ser.TrinityTrackingStatus]] | defined enum value only, not in: 0 |
| 4 | position_x_m | double | - |
| 5 | position_y_m | double | - |
| 6 | position_z_m | double | > 0 |
| 7 | quat_w | double | >= -1, <= 1 |
| 8 | quat_x | double | >= -1, <= 1 |
| 9 | quat_y | double | >= -1, <= 1 |
| 10 | quat_z | double | >= -1, <= 1 |
| 11 | sigma_position_m | double | - |
| 12 | sigma_range_m | double | - |
| 13 | sigma_orientation_mrad | double | - |
| 14 | ambiguity_resolved | bool | - |
| 15 | alternate | [[proto/ser.TrinityAltPose]] | - |
| 19 | range_source | [[proto/ser.TrinityRangeSource]] | defined enum value only, not in: 0 |
| 16 | anchors_seen | uint32 | <= 3 |
| 17 | board_extent_px | double | >= 0 |
| 18 | reprojection_rms_px | double | >= 0 |




## Field Notes


### board_version (#1)

Identifies **which physical board** this pose was computed against — the identity of the target, not the version of this message. Required, because a pose whose board is unstated cannot be checked against the geometry it assumes: every metric quantity here is scaled by the board's real dimensions, so a pose solved against a different board is wrong by a scale factor while looking entirely plausible.

[[proto/ser.JonOpaquePayload]] carries the payload's wire-format version separately, and the two move independently — a schema change does not reprint the board, and a new board revision does not change this message's shape.


### capture_time_ns (#2)

`CLOCK_BOOTTIME` nanoseconds of the **frame the pose was computed from**, not of the moment the message was assembled. It is the same clock domain as a cv-dump's `capture_time_ns` and its manifest `t0_boot_ns` / `t1_boot_ns`, so a pose joins against captured telemetry with no clock conversion.

`> 0` rejects the zero default. A `uint64` that was never populated arrives as 0, which is a syntactically perfect timestamp — without the bound it would join silently to whichever record sits at the start of a window, and a temporal join is the entire purpose of this field.

The identically-named `capture_time_ns` on [[proto/ser.CvChannelMeta]] and [[proto/ser.DetectionFrameMeta]] is documented as `CLOCK_MONOTONIC`. The field names agree; the clock domains do not, so a join across them is not a no-op.


### status (#3)

Says which of the pose fields mean anything this tick, and must be read before any of them. See [[proto/ser.TrinityTrackingStatus]]: `LOCKED` means the pose fields are valid; `SEARCHING` means no board was found this tick and they are stale or unset; `DEGRADED` means the board was found but is too small or too oblique to solve, so position may be approximate while orientation is **not** valid; `BOARD_MISMATCH` means the tracker is running but the board it detected is not the one [[proto/cmd.CV.StartTrackTrinity]] asked for.

Excluding `UNSPECIFIED` (0) is what forbids a pose arriving with its validity unstated. A consumer that reads `position_z_m` without first reading this field will read a stale pose during `SEARCHING` as though it were live.


### position_x_m (#4)

Lateral position of the board origin along **+X of the camera frame — to the right**, in metres. The frame this message declares is right-handed: +X right, +Y down, +Z forward along the optical axis.

Its 1-sigma is `sigma_position_m`, never `sigma_range_m`. Lateral is the **best** of the three axis groups: error is `sigma_px * range / focal_px`, so at 0.4 px centre localisation the same board resolves to roughly 1.1 mm at 10 m on day (focal_px 3517) and roughly 3.0 mm on heat (focal_px 1320), against roughly 5.7 mm and 15.2 mm at 50 m. Note it degrades LINEARLY with range, where `position_z_m` degrades with the square — which is why the two carry separate sigmas.

**It carries no constraint, and the absence is correct rather than an omission.** A lateral offset is signed and genuinely unbounded — a board to the left of the optical axis is negative and a distant one off frame centre is large — so there is no bound to impose. `position_z_m` is bounded only because zero or negative there is geometrically impossible, and nothing analogous holds here.

`status` decides whether the number means anything at all: valid under `LOCKED`, stale or unset under `SEARCHING`, approximate under `DEGRADED`, and meaningless under `IDLE`.


### position_y_m (#5)

Lateral position of the board origin along **+Y of the camera frame — DOWNWARD**, in metres. See `position_x_m` for the precision regime and the sigma to read; the two lateral axes share both.

**The Y SENSE is the trap, and it is the opposite of the pointer plane's.** The `cmd.*` pointer and gesture surface declares +y UP; this frame declares +y DOWN, so a helper written to map one to a screen overlay is wrong for the other and will compile, run and produce a plausible — vertically mirrored — result. The disagreement is invisible on the wire, because a `double` y is type-identical in both. Convert deliberately, and never reuse the pointer plane's transform here.


### position_z_m (#6)

Range from the camera to the board origin, in metres, along +Z of the camera frame this message declares — right-handed, +X right, +Y down, +Z forward along the optical axis.

**This is the weakest axis, and not by a small margin.** Range recovered from the board's apparent size obeys `dZ/Z = dS/S`, and the board subtends few pixels: roughly 27 mm at 10 m on day, against roughly 1.1 mm laterally at the same distance, and roughly 676 mm at 50 m. It degrades with the *square* of range, because the extent shrinks while the centre-localisation error stays fixed. The lateral figures quoted for `position_x_m` / `position_y_m` therefore do not transfer here, and this field's 1-sigma is `sigma_range_m`, never `sigma_position_m`.

Read `range_source` before using the number at all: under `TRINITY_RANGE_SOURCE_BOARD_EXTENT` it is monocular and cannot be millimetre-class at any useful distance, while under `TRINITY_RANGE_SOURCE_LRF` or `TRINITY_RANGE_SOURCE_FUSED` it is a direct rangefinder distance and can be.

`> 0` places the board in front of the camera. Zero or negative would put it at or behind the centre of projection, where nothing can be imaged — the bound rejects a degenerate solve, and is not a statement about minimum working range.


### quat_w (#7)

Scalar part of the unit quaternion rotating **board coordinates into camera coordinates**. The producer guarantees `|q| = 1` within 1e-9 across all four components.

A quaternion and not Euler angles, deliberately: Euler loses a degree of freedom at ±90° pitch — gimbal lock — and this board is routinely viewed near edge-on, where that singularity is reachable. A consumer that wants Euler can convert; a producer that emitted Euler could not un-lose the degree of freedom.

`>= -1, <= 1` is a *consequence* of unit norm rather than a check of it. All four components can sit inside the bound while `|q|` is not 1, so the range constraint cannot verify normalisation and does not attempt to — that remains the producer's 1e-9 guarantee, and a consumer building a rotation matrix should treat it as a guarantee rather than as something validation established.

Orientation is also the least precise of the three axis groups and the first to stop being observable: `sigma_orientation_mrad` is its 1-sigma, and under `TRINITY_TRACKING_STATUS_DEGRADED` orientation is not valid at all even where position still is.


### quat_x (#8)

First element of the same unit quaternion's vector part, taken in the axis order this message declares for the camera frame — +X right. See `quat_w` for the representation, the normalisation invariant, and what the `[-1, 1]` bound does and does not check.


### quat_y (#9)

Second element of the same unit quaternion's vector part, taken in the axis order this message declares for the camera frame — +Y down. See `quat_w` for the representation, the normalisation invariant, and what the `[-1, 1]` bound does and does not check.


### quat_z (#10)

Third element of the same unit quaternion's vector part, taken in the axis order this message declares for the camera frame — +Z forward, along the optical axis. See `quat_w` for the representation, the normalisation invariant, and what the `[-1, 1]` bound does and does not check.


### sigma_position_m (#11)

Isotropic 1-sigma uncertainty on **lateral** position — `position_x_m` and `position_y_m` together — in metres. It does **not** cover `position_z_m`, which has `sigma_range_m` of its own.

**A NEGATIVE VALUE MEANS "NOT ESTIMATED" and must be tested before use.** That sentinel is why the field carries no `gte: 0` constraint: the negative half of the range is load-bearing, so validation cannot reject it and does not try. A consumer that squares this straight into a variance turns the sentinel into a plausible positive number and gates on noise — read the sign first, then decide whether a sigma exists at all.

There are three sigmas rather than one because robustness and precision order the axes DIFFERENTLY, and a consumer needs both orderings. By precision, lateral is best, range is far worse, orientation worst. By robustness at far range, lateral and range stay observable while full 3-DoF orientation stops being so. A single scalar confidence would have to lie about at least one axis, so gate on the axis actually being used.


### sigma_range_m (#12)

1-sigma uncertainty on `position_z_m` **alone**, in metres — the range axis, and not part of `sigma_position_m`.

**Its relation to `sigma_position_m` is not fixed, and assuming one is the error this field exists to prevent.** Under `TRINITY_RANGE_SOURCE_BOARD_EXTENT` it is much LARGER — roughly 27 mm against roughly 1.1 mm at 10 m on day — because monocular range from apparent size obeys `dZ/Z = dS/S` and degrades with the square of range. With a rangefinder (`LRF`, `FUSED`) it can be SMALLER than the lateral sigma instead. Read `range_source` to know which case is in hand; nothing else on the message says.

A negative value means "not estimated", exactly as for `sigma_position_m`, and for the same reason carries no lower bound.


### sigma_orientation_mrad (#13)

1-sigma uncertainty on orientation, in **milliradians** — not radians and not degrees. The unit is in the field name because it is the only place it is stated; nothing in the type or the constraints carries it.

Orientation is the **least precise** of the three axis groups and the first to stop being observable as range grows, which is the near-affine regime that produces the two-fold ambiguity `alternate` carries. Under `TRINITY_TRACKING_STATUS_DEGRADED` orientation is **not valid at all** even where position still is, so this sigma is not the thing that tells a consumer to stop trusting the quaternion — `status` is, and it must be read first.

A negative value means "not estimated", as for the other two sigmas, and no lower bound is imposed for the same reason.


### ambiguity_resolved (#14)

Whether the planar two-fold fork was **closed**. A planar target under near-affine projection admits two poses that reprojection error cannot separate; `false` says the producer could not decide between them and chose one.

**`false` does not mean the pose is wrong — it means it is one of two, uncorroborated.** A consumer holding `false` at range should apply its own prior (a scale prior, a temporal track, an external range) across the chosen pose and `alternate`, rather than either discarding the payload or accepting the choice as settled.

**Do not infer this from anything else on the message.** `reprojection_rms_px` cannot separate the pair by construction — that is the whole reason this boolean exists rather than a residual comparison. Nor is it a restatement of `alternate`'s presence: the schema binds the two to each other in neither direction, so read this field for the fork's status and `alternate` for the other solution's numbers.


### alternate (#15)

The pose the disambiguator did **not** choose — see [[proto/ser.TrinityAltPose]]. Unset when only one solution exists.

It is carried so a consumer can see the fork and apply its own prior rather than inheriting a selection it cannot audit. **Do not choose between the two by comparing their `reprojection_rms_px` values**: the near-affine pair is precisely the case reprojection error cannot separate, so a comparison there is a decision made on noise. Use an independent constraint — a scale prior, temporal continuity, or a rangefinder distance — or carry both forward.

Note that [[proto/ser.TrinityAltPose]] repeats the pose fields with **no constraints at all**, where this message bounds `position_z_m` to `> 0` and each quaternion component to `[-1, 1]`. A rejected hypothesis is not held to the chosen pose's validity bounds, so a consumer that promotes the alternate must impose those checks itself; validation will not have done it.


### range_source (#19)

States **how `position_z_m` was obtained**, and it is load-bearing rather than descriptive: board-extent range and rangefinder range differ by more than an order of magnitude at 50 m and are indistinguishable in the numbers themselves. See [[proto/ser.TrinityRangeSource]] — `BOARD_EXTENT` is monocular from apparent size and degrades with the square of range; `LRF` is a direct rangefinder distance, roughly range-independent, and is the millimetre-class option; `FUSED` is LRF range with board-derived lateral position and orientation.

It also decides how the two sigmas read against each other. Under `BOARD_EXTENT`, `sigma_range_m` is much larger than `sigma_position_m` — 27 mm against 1.1 mm at 10 m on day; with an LRF it can be smaller. There is no fixed relation between them, and this field is the only thing that says which case a consumer is holding.

Excluding `UNSPECIFIED` (0) means a pose can never be emitted without declaring its range provenance, which is the whole reason the field exists.


### anchors_seen (#16)

One of the three observability quantities on this message — with `board_extent_px` and `reprojection_rms_px` — carried so a consumer can *judge* the pose rather than trust it.

A count of the board's anchor features resolved in the frame this pose came from. `<= 3` is the schema's statement that three is the full complement for a Ring-Trinity board: a higher value is a producer defect rather than a richer board, and validation rejects it.

**What the schema fixes is the ceiling, and nothing beyond it should be inferred here.** The proto defines neither what an anchor is physically nor any minimum count for a usable pose, so a quality gate must not be derived from this number alone. `status` is what says whether the pose is valid, and `sigma_position_m` / `sigma_range_m` / `sigma_orientation_mrad` are what quantify how well.


### board_extent_px (#17)

The board's apparent size in the image, in pixels — the quantity monocular range is solved from. Because `dZ/Z = dS/S`, a fractional error in this extent transfers directly into a fractional error in `position_z_m`; the board subtends few pixels, which is why range is the weak axis and why it degrades with the square of range, the extent shrinking while the centre-localisation error stays fixed.

It is reported whatever `range_source` says, but it only *produced* the range under `TRINITY_RANGE_SOURCE_BOARD_EXTENT`. Under `TRINITY_RANGE_SOURCE_LRF` and `TRINITY_RANGE_SOURCE_FUSED` the distance came from the rangefinder and this stays an observability figure.

`>= 0` is a type-level sanity bound — a length in pixels cannot be negative. It is not a validity signal; `status` is.


### reprojection_rms_px (#18)

Root-mean-square of the reprojection residuals, in pixels: how closely the solved pose reprojects the board's known geometry onto the image points actually observed. It measures the **self-consistency of the fit**.

**It is explicitly not a disambiguator, and treating it as one is the failure this message is shaped to prevent.** A planar target under near-affine projection admits two poses that reprojection error *cannot* separate — which is why [[proto/ser.TrinityAltPose]] carries a `reprojection_rms_px` of its own beside the chosen solution, and why `ambiguity_resolved` exists as a separate boolean rather than being inferred from a residual. Choosing between the two poses by comparing these two numbers is choosing on noise. A low value says the solve is internally consistent; it says nothing about whether it is the correct one of the pair.

`>= 0` follows from the quantity itself: an RMS cannot be negative.



