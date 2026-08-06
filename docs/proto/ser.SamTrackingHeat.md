---
id: ser.SamTrackingHeat
proto: opaque/sam_tracking_heat.proto
package: ser
type: message
---

# SamTrackingHeat

**Source:** `opaque/sam_tracking_heat.proto`

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

Outcome of the single tracker iteration this message reports, for the HEAT camera channel. The declared values are `OK` (successful tracking iteration), `NOT_READY` (engine or IPC not initialized), `NOT_STARTED` (tracking not started, awaiting the start command), `IPC_TIMEOUT` (CUDA IPC read timed out), `INFER_FAILED` (TensorRT inference failed) and `LOST` (object tracking lost).

The constraint does two separate jobs. `not_in: [0]` excludes `SAM_TRACKING_STATUS_UNSPECIFIED`; because a proto3 enum field has no presence, a producer that never assigns this field serializes nothing and every decoder reconstructs it as 0, so excluding 0 is what makes the field effectively mandatory — a message assembled without it fails validation instead of arriving as a plausible-looking zero. `defined_only: true` rejects any numeric value outside the declared set: proto3 enums are open on the wire, so a value minted by a newer producer survives decoding as a bare integer, and this bound stops that integer at the validation boundary rather than letting it fall through a consumer's switch.

`status` and `state` (#2) are orthogonal axes and neither is derivable from the other: this field reports how the iteration went, `state` reports where the tracker sits in its lifecycle. Read both.


### state (#2)

Position of the tracker's state machine at this tick. `SamTrackingState` documents the transition graph as IDLE → STARTING → TRACKING ⇄ OCCLUDED → LOST, with IDLE meaning not tracking and awaiting start, STARTING meaning the initial prompt has been received and the tracker is warming up, TRACKING meaning normal tracking with valid masks, OCCLUDED meaning low confidence with the Kalman prediction in use, and LOST meaning tracking was lost after `max_occluded_frames` was exceeded.

The double arrow between TRACKING and OCCLUDED is the recoverable excursion — a target that passes behind cover is expected to come back — while LOST is the terminal one, and `lost_frame_count` (#17) counts consecutive frames spent there. Because the enum describes OCCLUDED as running on the Kalman prediction, geometry reported in that state is at least partly predicted rather than freshly segmented; `kalman` (#16) carries that predictor's own state.

The constraint shape is identical to `status` and carries the same two consequences: excluding 0 makes the field effectively mandatory under proto3's lack of enum presence, and `defined_only` closes the set against forward-compatible values a newer producer might emit.


### bbox_x1 (#3)

Left-hand x edge of the axis-aligned bounding box around the tracked object, in NDC (Normalized Device Coordinates). This is a screen-relative normalized quantity, not pixels, not metres and not an angle: -1.0 is the left edge of the source frame, +1.0 the right edge, and 0.0 the horizontal centre. The bound IS the frame — a value outside [-1, 1] would name a point off the image — so the constraint is what keeps the box addressable rather than merely keeping the number small.

Being normalized makes the value resolution-independent, which matters here more than usual: **this message carries no source frame dimensions at all.** Its `frame` field is a `SamTrackingFrameMeta`, which holds `pts_ns`, `capture_time_ns`, `generation` and `capture_monotonic_us` and no width or height (unlike `DetectionFrameMeta`, which does carry them). A consumer that wants pixels therefore supplies the dimensions itself, from the surface it is drawing on, using the mapping the convention implies: `px_x = (bbox_x1 + 1) / 2 * W`.

Each of the four box coordinates is bounded independently and nothing in the schema asserts an ordering between them, so a consumer that needs a normalized rectangle orders the corners itself rather than assuming `bbox_x1 <= bbox_x2`.


### bbox_y1 (#4)

Top y edge of the bounding box, in the same NDC frame — and the axis points DOWN. The field comment fixes -1.0 as top and +1.0 as bottom, so larger y is lower on the image. That matches `JonGuiDataROI` and `ObjectDetection`, both of which document the identical "-1.0 (left/top) to 1.0 (right/bottom)" convention.

It is the OPPOSITE of the POINTER convention, which `docs/INTERFACE-CONTRACTS.md` §4 states as `+y` UP with an explicit Y-flip in its pixel transforms.

**It is NOT the opposite of "the `cmd.*` convention", because there is no single one — and this paragraph asserted there was.** It previously named `StartTrackNDC`, `RotateToNDC` and `HaltWithNDC` together as one y-UP family sharing the pointer plane. That grouping is retired: the plane is a property of each DESTINATION command, `cmd.CV.StartTrackNDC`'s is UNRESOLVED (its own page says so), and the ROI command family is measurably y-DOWN — the same y-DOWN this message uses. A reader who trusted the old sentence would mirror an ROI rectangle while believing the two ends agreed.

So a y value taken from this message must never be written verbatim into a `cmd.*` NDC field on the strength of a family name; the destination's own plane has to be established first, and `ui.CmdSpec.ndc_y_sense` is where a producer states the answer it established.

That failure is worth spelling out because it does not announce itself. Copying y across unchanged mirrors the target about the horizontal centre line: the result is a well-formed in-range coordinate, it is exactly right whenever the target happens to sit on the centre line, and it is wrong by twice the target's offset everywhere else — a bug that looks like a calibration error rather than a sign error.


### bbox_x2 (#5)

Right-hand x edge of the bounding box, in the same NDC frame as `bbox_x1`, with the same bound protecting the same property.

Together with the other three coordinates it defines the DOMAIN of the mask: `mask_rle` (#11) encodes a binary mask *within* this box, so the box's extent — not the mask's pixel count — is what sets the mask's size on screen. Nothing in the schema forbids `bbox_x2` being less than or equal to `bbox_x1`, so a degenerate zero-width box is representable and any consumer dividing by the box width guards against it.


### bbox_y2 (#6)

Bottom y edge of the bounding box. Larger y is lower on the image, per the down-positive convention described at `bbox_y1` (#4), so `bbox_y2` is normally the numerically greater of the two y values — but the schema constrains each independently and does not assert that, so a consumer needing an ordered rectangle sorts the pair.

With `bbox_y1` it gives the box's vertical extent, which is the height the mask raster is mapped onto.


### centroid_x (#7)

x coordinate of the mask's K-medoids cluster centre, in the same NDC frame as the bounding box.

Two things are worth separating here. First, what a medoid is: K-medoids, the algorithm the field comment names, picks an ACTUAL sample as the cluster centre, where K-means computes an arithmetic mean that need not be a member of the set. For a mask that is L-shaped, ring-shaped, or split around an occluder, a centre of mass can land on background pixels; a medoid cannot land anywhere except on the mask.

Second, what the point is FOR: the field comment states this is the point used to prompt the next iteration. That makes it the tracker's feedback path — this frame's centroid re-seeds the next frame's segmentation. The [-1, 1] bound is therefore not decoration: it keeps the next prompt inside the image, and an error in this field propagates forward into the following frame rather than staying confined to this message.

Note also that the centroid's bound is independent of the box's: nothing in the schema requires the centroid to lie inside the reported bounding box.


### centroid_y (#8)

y coordinate of the K-medoids centre, down-positive exactly as described at `bbox_y1` (#4): -1.0 is the top of the frame, +1.0 the bottom.

The sign question bites hardest here, because this is the value most likely to be handed to an aim or track command — and there is no single answer to hand it to. `docs/INTERFACE-CONTRACTS.md` §4 does NOT group `StartTrackNDC` / `RotateToNDC` / `HaltWithNDC` under one orientation; the only one of the three it names is `cmd.CV.StartTrackNDC`, which it records as settled by neither plane. The rotary NDC pair is established `+y` UP; the ROI family is `-y` UP, i.e. the same y-DOWN sense this message uses; `StartTrackNDC` is UNRESOLVED. Establish the DESTINATION command's own plane before forwarding this value, and never assume the two normalized coordinate systems are interchangeable because both are `double` in [-1, 1].

The centroid is the mask's medoid, not the box's midpoint, so it is not required to equal `(bbox_y1 + bbox_y2) / 2` and will sit off centre for a non-convex or partly occluded target — which is the point of computing it.


### confidence (#9)

Despite the name, this is a geometric FILL RATIO and not a probability: the field comment defines it as `mask_area / bbox_area`. The [0, 1] bound follows from that definition rather than being imposed on it — the mask lies within its own bounding box, so the ratio cannot exceed 1, and 0 means an empty mask. A value the bound would reject could only come from an encoding fault or a differently-scaled score (a percentage, say), which is what the constraint is there to stop at the boundary.

The consumer trap is reading a low value as a bad track. An axis-aligned box drawn around a diagonal, thin, or L-shaped target contains a large fraction of background, so a geometrically perfect mask of such an object scores low, while a fat compact blob scores high. It describes the target's shape at least as much as the tracker's health, and is most informative as a change signal for one target over time rather than as a fixed cross-target quality bar.

`SamTrackingState` describes OCCLUDED as a low-confidence condition, but the schema does not state which quantity the tracker thresholds, nor at what value.


### iou (#10)

Intersection over Union: for two regions, the area they share divided by the area they jointly cover — 1.0 when they coincide exactly, 0.0 when they are disjoint. Because the intersection is always a subset of the union, the ratio is inherently in [0, 1], so the constraint rejects values that could only arise from a fault or a mis-scaled score rather than narrowing a genuinely wider range.

The field comment calls this a decoder IoU *prediction*, and that word carries the meaning: the mask decoder emits, alongside each mask, its own estimate of the IoU that mask would achieve. There is no ground-truth mask available at inference time to measure against, so this is the model's self-assessment of its output, not a measurement of it.

That makes it a different quantity from `confidence` (#9), which is computed arithmetically from the mask that was actually emitted. One is the model's opinion of its mask; the other is a property of it. They answer different questions and should not be substituted for one another.


### mask_rle (#11)

The segmentation mask itself, run-length encoded and defined WITHIN the bounding box rather than over the whole frame — which is what keeps it small enough to ride in the state stream at inference rate.

The field comment fixes the format as a sequence of `[run_length:u16, value:u8]` records, little-endian, so each record is exactly 3 bytes: a count of consecutive mask pixels and the value all of them take. Run-length encoding suits a segmentation mask because such a mask is mostly long uniform stretches, and RLE collapses each stretch to a fixed 3 bytes however long it is — up to the 65535-pixel ceiling the `u16` imposes, beyond which a run splits across records.

Little-endian is stated and is load-bearing, because protobuf does not interpret the interior of a `bytes` field: the two bytes of `run_length` are laid out low byte first, so a consumer that reads them big-endian turns a run of 5 (`05 00`) into a run of 1280 (`0x0500`) — an in-range, entirely plausible number that silently corrupts every subsequent offset in the mask.

The `max_len` of 65536 is described by the comment as preventing oversized payloads. What it bounds is not just this field: the encoded message is injected into `JonGUIState.opaque_payloads`, which is fanned out to every state consumer at the stated ~30 fps, so the cap bounds the state stream's bandwidth and each consumer's decode work. At 3 bytes per record it admits at most 21845 complete records.

Two things the schema does NOT fix, and both must come from the producer: the raster scan order of the runs, and whether the run lengths are required to sum to `mask_width` × `mask_height`. Decode against those dimensions and treat a shortfall or an overrun as a corrupt payload rather than padding it out. Note as well that the comment calls the mask binary while `value` is a full byte, so the encoding of "set" is not pinned here — compare against zero rather than against a particular set value.


### mask_width (#12)

Width, in mask pixels, of the raster that `mask_rle` (#11) decodes into. This is the mask's OWN resolution and not the video frame's: the comment notes it is typically 256×256, the SAM decoder's output size, so the mask is generally much coarser than the image and is mapped onto the bounding box rather than onto the frame's pixel grid. One mask column therefore spans `(bbox_x2 - bbox_x1) / mask_width` in NDC, a width that changes as the target's apparent size changes.

The lower bound of 1 does two jobs. It rejects a degenerate zero-width raster, which would leave the RLE undecodable and make any stride arithmetic a division by zero. And because a proto3 `uint32` has no presence and reconstructs as 0 when unset, it makes the field effectively mandatory — a message that omits it fails validation instead of arriving as a zero a consumer might read as "no mask".

The upper bound of 2048 caps the raster at 2048 columns; with `mask_height` under the same bound, a single payload can demand at most 4,194,304 mask pixels of decode work and memory from every consumer of the state stream.


### mask_height (#13)

Height, in mask pixels, of the same raster, under the same bounds and with the same consequences: 1 excludes the degenerate case and makes the field effectively mandatory given proto3's lack of presence on scalars, and 2048 bounds the work a single payload can impose.

`mask_width` and `mask_height` together are what turn the flat run sequence back into a two-dimensional mask, and neither is meaningful without the bounding box that says where that raster lands in the image.

There is an aspect-ratio trap in the pair. The raster is typically square (256×256 per the comment) while a bounding box generally is not, so mapping the mask onto the box as described is an anisotropic scale — the mask's pixels are not square in image space. A consumer that assumes they are will draw a distorted overlay that still lines up at the box's corners, which is exactly the kind of error that survives a casual look.


### lost_frame_count (#17)

Length of the CURRENT consecutive run of frames in the LOST state — a run length, not a cumulative total over the session. The comment ties it to the producer's auto-stop policy: tracking stops after 5 frames in LOST.

The `<= 255` bound keeps the value inside a single byte, far above the 5-frame policy the comment documents, so the cap is headroom rather than the operative threshold. A consumer must not read 255 as meaningful: the schema states no saturation behaviour, and the ceiling is a validation bound rather than a stop condition. Key any display or reaction on `state` (#2) and on the documented policy, not on this cap.

The schema does not state what the field holds while the tracker is not in LOST; 0 is the proto3 default and there is no presence bit to distinguish "not set" from a genuine zero.


### latency_ns (#18)

Processing latency for this tick, in nanoseconds. It is a DURATION, which distinguishes it from the instants carried in `frame` (#15) — `pts_ns`, `capture_time_ns` and `capture_monotonic_us` are timestamps and this is an interval.

Its `gte: 0` constraint excludes nothing at all: the field is a `uint64`, so every value the type can represent already satisfies it. Read it as a declaration that the quantity is a non-negative duration, kept consistent with the other time-valued fields in this family, and not as a filter that can reject anything. There is no upper bound either, so a first-run or stalled tick may report a very large value; a consumer must not narrow it into a smaller integer or a millisecond field without checking.

The schema does not state which stage starts the clock and which stops it. Since `ObjectDetectionsDay` and `ObjectDetectionsHeat` also carry a `latency_ns`, confirm that both are measured over the same span before comparing or aggregating them.

Latency is also not the same thing as period. A pipelined producer can report a latency longer than the ~33 ms frame interval implied by the ~30 fps injection rate while still emitting one message per frame, so this field on its own bounds neither the message rate nor the staleness of the geometry it accompanies.



