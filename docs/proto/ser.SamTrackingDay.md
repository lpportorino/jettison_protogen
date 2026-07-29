---
id: ser.SamTrackingDay
proto: opaque/sam_tracking_day.proto
package: ser
type: message
---

# SamTrackingDay

**Source:** `opaque/sam_tracking_day.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | status | [[proto/ser.SamTrackingStatus]] | defined enum value only, not in: 0 |
| 2 | state | [[proto/ser.SamTrackingState]] | defined enum value only, not in: 0 |
| 3 | bbox_x1 | double | >= -1, <= 1 |
| 4 | bbox_y1 | double | >= -1, <= 1 |
| 5 | bbox_x2 | double | >= -1, <= 1 |
| 6 | bbox_y2 | double | >= -1, <= 1 |
| 7 | centroid_x | double | >= -1, <= 1 |
| 8 | centroid_y | double | >= -1, <= 1 |
| 9 | confidence | float | >= 0, <= 1 |
| 10 | iou | float | >= 0, <= 1 |
| 11 | mask_rle | bytes | max-len: 65536 |
| 12 | mask_width | uint32 | >= 1, <= 2048 |
| 13 | mask_height | uint32 | >= 1, <= 2048 |
| 14 | mask_pixels | uint32 | - |
| 15 | frame | [[proto/ser.SamTrackingFrameMeta]] | - |
| 16 | kalman | [[proto/ser.SamTrackingKalmanState]] | - |
| 17 | lost_frame_count | uint32 | <= 255 |
| 18 | latency_ns | uint64 | >= 0 |




## Field Notes


### status (#1)

Outcome code for this single tracking tick — whether the pipeline produced a result at all and, if not, which stage refused (the vocabulary is [[proto/ser.SamTrackingStatus]]: engine/IPC not initialised, tracking not started, CUDA IPC read timeout, TensorRT inference failure, or object lost). It answers "did this iteration work", which is a different question from `state` (#2)'s "where does the tracker sit in its state machine".

`defined_only` rejects any number that is not a member of the enum. That clause is doing real work in proto3, where an unrecognised enum number is NOT a decode error — the value passes through decoding rather than being refused, so without this clause a consumer built against today's vocabulary would accept a value minted by a newer producer and would have to notice the mismatch itself.

`not_in: [0]` rejects `SAM_TRACKING_STATUS_UNSPECIFIED`. Proto3 gives every absent scalar its zero value on decode, so an omitted `status` and an explicitly-zero `status` are indistinguishable on the wire; refusing zero is therefore what makes this field effectively mandatory — the producer must state an outcome and cannot leave it defaulted.

Both clauses are buf.validate DECLARATIONS carried in the descriptor. Enforcing them at run time requires the consuming language's protovalidate library; the generated bindings in this repo do not embed one, so a consumer that wants the guarantee must run the check itself.


### state (#2)

Position of the tracker in its state machine, drawn from [[proto/ser.SamTrackingState]], whose declared transition order is IDLE → STARTING → TRACKING ⇄ OCCLUDED → LOST. This is a PERSISTENT property carried across ticks, where `status` (#1) is the outcome of this one tick — two different axes, reported side by side.

The two vocabularies both contain a member named LOST and they are NOT interchangeable: `SAM_TRACKING_STATUS_LOST` is 6 and `SAM_TRACKING_STATE_LOST` is 5. Nothing in the numbers themselves distinguishes the two vocabularies, so a consumer that compares a raw value taken from one field against a constant belonging to the other gets a plausible answer that is about the wrong quantity.

`OCCLUDED` is declared as low confidence with the Kalman prediction standing in ([[proto/ser.SamTrackingKalmanState]], field #16). The message does not state which of the geometry fields are predicted rather than measured while in that state, so a consumer that needs to distinguish observed geometry from extrapolated geometry cannot do it from this field alone.

The `defined_only` and `not_in: [0]` clauses mean exactly what they mean on `status` (#1): numbers outside the enum are refused, and an unset field — which decodes as `SAM_TRACKING_STATE_UNSPECIFIED` — is refused, making the field effectively mandatory.


### bbox_x1 (#3)

Left edge of the tracked object's bounding box, in Normalized Device Coordinates. This message fixes the frame in its own comment: -1.0 is the LEFT edge of the video frame, +1.0 the RIGHT edge, and 0.0 the horizontal centre. It is the same convention as [[proto/ser.JonGuiDataROI]] and [[proto/ser.ObjectDetection]].

**NDC is a screen quantity — a fraction of the frame, not a length.** It carries no metres, no pixels and no angle. The same physical object holds the same NDC width only at a fixed zoom and range; change either and the number moves while the object does not. A consumer that treats this value as a dimension, or compares it against a value in world units, is reading a screen fraction as a physical quantity.

Converting to pixels needs the frame's own width, and **this message does not carry it** — [[proto/ser.SamTrackingFrameMeta]] (field #15) carries timestamps and a generation counter only, unlike [[proto/ser.DetectionFrameMeta]], which does carry source frame dimensions. The frame geometry has to come from elsewhere in the consumer's pipeline.

The `[-1, 1]` bound is what makes the number a frame fraction at all: a value outside it would name a point off-frame, which cannot be an edge of a box observed inside one.


### bbox_y1 (#4)

Top edge of the bounding box, in the same NDC frame as `bbox_x1` (#3).

**The vertical sense is declared DOWNWARD-POSITIVE here:** this message's comment reads "-1.0 (left/top) to 1.0 (right/bottom)", so -1.0 is the TOP of the frame, +1.0 the BOTTOM, and increasing y moves DOWN the screen. That matches [[proto/ser.JonGuiDataROI]] and [[proto/ser.ObjectDetection]].

**It is the opposite of the pointer/gesture and `cmd.*` NDC convention** documented in `docs/INTERFACE-CONTRACTS.md` §4 "NDC convention", which defines `+y` as UP with `1.0` mapping to row 0. Both conventions use the same name, the same `double` type and the same `[-1, 1]` range, so nothing on the wire distinguishes them. A consumer that renders this box with the pointer plane's transform, or that carries a y value between the two planes without flipping it, gets a box mirrored about the frame's horizontal centre line — and a box that happens to be vertically centred maps onto itself under that mirror, so the bug looks correct in exactly the case a developer is most likely to test.


### bbox_x2 (#5)

Right edge of the bounding box, in the same NDC frame as `bbox_x1` (#3).

The `[-1, 1]` bound is applied to this field INDEPENDENTLY: no constraint in this message relates `bbox_x2` to `bbox_x1`, so an inverted pair is schema-valid. A consumer computing a width as `bbox_x2 - bbox_x1` must either normalise the pair first or be prepared for a non-positive extent; the validation will not have caught it.


### bbox_y2 (#6)

Bottom edge of the bounding box, in the same NDC frame and with the same downward-positive vertical sense as `bbox_y1` (#4) — so for a box the right way up, `bbox_y2` is the NUMERICALLY GREATER value, which is the reverse of what a +y-up reading would predict.

As with `bbox_x2` (#5), the bound is independent and no declared constraint orders this field against `bbox_y1`.


### centroid_x (#7)

X coordinate, in the same NDC frame as `bbox_x1` (#3), of the K-Medoids cluster centre — which this message states is the point used to prompt the tracker's next iteration.

**A medoid is not a mean.** A mean is an averaged position that need not correspond to any member of the set it summarises; a medoid is an actual member, chosen to minimise total distance to the rest. That distinction is the whole reason a medoid is used for a prompt point: the averaged centre of a concave, annular or U-shaped region can land in the hole, outside the region entirely, whereas a medoid cannot leave the set it was drawn from. This message does not state which point set is clustered.

Because the value is fed back as the prompt for the next iteration, it is a CONTROL INPUT to the tracker and not merely a display quantity. An overlay drawing it is showing where the tracker will look next, which is not necessarily the object's geometric centre and is not necessarily the centre of the bounding box.

The `[-1, 1]` bound carries the same meaning as on the bbox fields: it constrains the point to lie on-frame.


### centroid_y (#8)

Y coordinate of the same K-Medoids prompt point described in `centroid_x` (#7), in the same NDC frame and with the same DOWNWARD-POSITIVE vertical sense as `bbox_y1` (#4) — -1.0 at the top of the frame, +1.0 at the bottom.


### confidence (#9)

Tracking confidence in `[0.0, 1.0]`. This message states its derivation: **mask area divided by bounding-box area.**

That makes it a GEOMETRIC FILL FRACTION — how much of its own box the segmentation actually occupies — and NOT a probability, NOT a class score, and NOT comparable with [[proto/ser.ObjectDetection]]'s `confidence`, which is a model score from a detector. The two fields share a name, a type and a range, and mean different things.

The consequence for reading it: a correctly and tightly tracked object that is THIN, DIAGONAL or ANNULAR fills little of its axis-aligned bounding box and therefore scores LOW while the track is perfectly good; a loose box drawn around a solid compact blob scores HIGH. Treat it as a measure of how well the box fits the mask, and gate a track's health on it only with that in mind.

The `[0.0, 1.0]` bound is intrinsic rather than arbitrary: the mask is defined as lying WITHIN the bounding box (see `mask_rle` (#11)), so the ratio cannot exceed 1, and an area cannot be negative.


### iou (#10)

The decoder's own predicted intersection-over-union for the mask it just emitted, in `[0.0, 1.0]`.

IoU is the standard overlap score for segmentation and detection: the area of the intersection of two regions divided by the area of their union. It is 1.0 when the two coincide exactly and 0.0 when they are disjoint. It is preferred to raw intersection area because a single number then penalises BOTH under-segmentation and over-segmentation — a prediction that simply grows to cover everything raises the intersection but raises the union faster, so its score falls.

**This is a PREDICTION, not a measurement.** The proto names it a decoder IoU prediction, so the value is the model's own estimate of the quality of the mask it produced — and this message carries no second, reference mask against which such an overlap could actually be computed. Read it as the model's self-assessment.

It is therefore a different kind of quantity from `confidence` (#9), which is a ratio computed from geometry the message actually carries. Both are `[0, 1]` floats and neither substitutes for the other. The bound is the natural range of a ratio of an intersection to a union that contains it.


### mask_rle (#11)

The segmentation mask itself, run-length encoded. It covers the BOUNDING BOX, not the frame.

**The declared encoding** is a flat sequence of runs, each three bytes: `[run_length: u16 little-endian][value: u8]`. Decode by expanding each run into `run_length` copies of `value` and reshaping the result to `mask_width` (#12) by `mask_height` (#13). Two things this message does NOT declare, and a consumer must obtain them from the producer rather than assume: the scan order in which the expanded samples fill the raster (a wrong assumption here transposes or flips the mask without any error), and which byte values represent set and clear (the mask is declared binary, but `1` versus `255` is not fixed here).

**Why run-length encoding at all.** A 256×256 binary mask is 65536 samples, and a segmentation mask is made of long same-valued spans — a scanline typically crosses the object's boundary only a few times. RLE spends three bytes per state CHANGE rather than one sample per sample, which is what allows a per-frame mask to ride inside a state payload at inference rate instead of needing its own transport.

**The 64 KiB cap is a real limit, not a formality.** At three bytes per run, 65536 bytes admits at most 21845 complete runs, while a pathological 256×256 mask alternating value at every pixel would need 65536 runs — three times the budget. A producer emitting a heavily fragmented mask must reduce its run count or emit nothing; a longer encoding is not schema-valid. The bound is what stops one noisy frame from inflating a state payload that every consumer parses on every tick.

No minimum length is declared, so an EMPTY `mask_rle` is schema-valid and should be expected — a tick with a non-OK `status` (#1), or a `state` (#2) with no measured mask, has no mask to send. A consumer must handle zero bytes as a normal case rather than as a truncation.


### mask_width (#12)

Width, in mask pixels, of the mask raster carried by `mask_rle` (#11). Typically 256, from the SAM decoder output.

**The raster covers the BOUNDING BOX, not the video frame**, so its pixels are NOT frame pixels and a mask column index is not a frame column index. Column `c` maps onto the box's NDC x span — `bbox_x1` (#3) to `bbox_x2` (#5) — and only that span. Reading this number as a width in frame pixels, or comparing it against a camera resolution, is the failure this field's definition exists to prevent.

A further consequence of the raster being box-shaped and the dimensions being independent of the box's aspect ratio: a square 256×256 mask over a tall narrow box is ANISOTROPICALLY resampled, with each mask cell spanning `(bbox_x2 - bbox_x1) / mask_width` in NDC x and `(bbox_y2 - bbox_y1) / mask_height` in NDC y — different amounts. A consumer that assumes square mask cells will distort the overlay.

The value is also what makes `mask_rle` decodable at all: width and height are what turn a flat run sequence back into a two-dimensional raster. The lower bound of 1 forbids a zero-width raster, which would make that reshape — and any area or fill ratio computed from it — degenerate. The upper bound of 2048 caps the raster a consumer can be asked to expand; this message does not state where that particular number comes from.


### mask_height (#13)

Height, in mask pixels, of the same mask raster — typically 256, and subject to the same reading as `mask_width` (#12): it spans the BOUNDING BOX's NDC y range, not the frame's, and its cells are not square unless the box's aspect ratio happens to match `mask_width` : `mask_height`.

Together with `mask_width` it fixes the total sample count the decoded run sequence must cover, which is what lets a consumer validate a decode rather than trust it: a run sequence that expands to any other total is malformed. `mask_pixels` (#14) reports how many of those samples are non-zero, which a consumer can likewise check against its own decode.

The `[1, 2048]` bound carries the same meaning as on `mask_width`: no degenerate zero dimension, and a ceiling on the raster a producer may declare.


### lost_frame_count (#17)

Number of CONSECUTIVE frames the tracker has spent in `SAM_TRACKING_STATE_LOST`. Its purpose is stated in the proto: auto-stop logic, stopping the track after 5 consecutive LOST frames.

It is therefore a DEBOUNCE counter. A single dropped or occluded frame must not tear down a track that is otherwise healthy, and this counter is the quantity that distinguishes a momentary miss from a genuine loss. Because it counts CONSECUTIVE frames, any non-LOST frame resets the meaning of the number — it is not a running total of how often the track has been lost.

**It is a count of FRAMES, not a duration.** Converting it to elapsed time requires a frame interval, which this message does not carry: the "~30fps" in the message's own comment is a nominal injection rate, not a measured per-frame interval. Successive `pts_ns` values from [[proto/ser.SamTrackingFrameMeta]] (field #15) are the measured quantity.

The `<= 255` bound caps what may be transmitted, holding the value inside one byte's range even though the field's declared type is a 32-bit unsigned integer. Given the stated auto-stop at 5, the counter has no ordinary reason to approach that ceiling; the message does not state what the producer does if the count would exceed it.


### latency_ns (#18)

Processing latency for this tracking tick, in nanoseconds.

**The `>= 0` constraint is vacuous as a runtime check.** `uint64` cannot represent a negative value, so every value the field is capable of holding already satisfies the bound — it removes nothing from the admissible set. What it does is DOCUMENT INTENT: this quantity is a non-negative duration, and the same declaration appears on the timestamps of [[proto/ser.SamTrackingFrameMeta]]. Read it as a statement about meaning, not as a check that can ever fire.

**It is a DURATION, not a timestamp.** Unlike `pts_ns` and `capture_time_ns` on the frame metadata, which are points on a clock, this number has no epoch. It must never be compared against those values, subtracted from them, or fed into a routine that expects a clock reading.

Nanoseconds is a UNIT, not a precision claim: the field can express a nanosecond, which says nothing about whether the underlying measurement resolves one.

**This message does not name the two events the interval spans** — frame arrival to result publication, inference only, or some other pair. A consumer must therefore not assume it is comparable with a latency measured elsewhere in the pipeline, and must not sum it with one.



