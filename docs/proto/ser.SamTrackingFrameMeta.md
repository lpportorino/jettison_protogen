---
id: ser.SamTrackingFrameMeta
proto: opaque/sam_tracking_common.proto
package: ser
type: message
---

# SamTrackingFrameMeta

**Source:** `opaque/sam_tracking_common.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | pts_ns | uint64 | >= 0 |
| 2 | capture_time_ns | uint64 | >= 0 |
| 3 | generation | uint32 | - |
| 4 | capture_monotonic_us | uint64 | >= 0 |




## Field Notes


### pts_ns (#1)

Presentation timestamp (PTS) of the video frame this tracking result was computed from, in nanoseconds. A PTS is a position on the video stream's own timeline, not a reading of any clock — it answers "which frame", not "when". It rides on every tracking tick so a consumer can align a mask against the exact frame it describes rather than against whatever frame happens to be on screen when the message arrives.

This field, `capture_time_ns` and `generation` mirror field-for-field the same trio on [[proto/ser.DetectionFrameMeta]] and [[proto/ser.CvChannelMeta]]; both of those pages document the quantity for their own pipeline, including that a PTS of 0 means the pipeline did not supply a valid one. This proto does not restate that, so confirm it against the producer before reading a 0 here as the start of the stream.

The `>= 0` constraint is declaratory rather than restrictive: the field is `uint64`, whose domain is already 0 upward, so no representable value can violate it. It records the sign convention and keeps the field uniform with the sibling timing fields above; it gives a consumer no guarantee the type did not already give.


### capture_time_ns (#2)

Timestamp taken when the frame entered the capture pipeline, in nanoseconds. Paired with `pts_ns` it separates two things that both read as "when": where the frame sits on the stream timeline (`pts_ns`) versus when it physically arrived (this field). The interval from this stamp to the moment a tracking result is acted on is the end-to-end latency of the tracking path.

This proto does not name the clock domain for this field — note that `capture_monotonic_us` below names its clock explicitly and this one does not. The identically named field on [[proto/ser.DetectionFrameMeta]] and [[proto/ser.CvChannelMeta]] is documented as `CLOCK_MONOTONIC`, read at the pipeline capture probe. Establish which clock produced a value before subtracting it from a wall-clock or epoch timestamp: a monotonic reading and a calendar reading differ by an arbitrary offset, so their difference is meaningless rather than merely imprecise.

`>= 0` is declaratory here for the same reason as `pts_ns` — `uint64` cannot represent a negative value.


### capture_monotonic_us (#4)

The correlation key. A `CLOCK_MONOTONIC` reading in microseconds, deliberately shaped to match [[proto/ser.CvMeta]]'s `capture_monotonic_us` (the field's own comment says so), so a tracking result can be joined against the CV metadata, camera state and rotary state published for the same instant. [[proto/ser.ObjectDetectionsDay]] and [[proto/ser.ObjectDetectionsHeat]] carry the identical field for the same reason, which is what makes detection and tracking results joinable to each other.

**The unit changes inside this one message.** `pts_ns` and `capture_time_ns` are nanoseconds; this field is microseconds. Mixing them is a silent factor-of-1000 error that yields plausible-looking timestamps, so convert explicitly at every comparison rather than relying on the field order.

`CLOCK_MONOTONIC` has an unspecified origin — in practice boot-relative — and carries no calendar meaning. A value here is comparable only against other `CLOCK_MONOTONIC` readings taken on the same machine since the same boot; it is not comparable to a UTC or epoch timestamp, and it is not comparable across a reboot. That limitation is exactly what makes it the right join key: unlike a wall clock, it cannot be stepped backwards by a time adjustment, so two subsystems stamping the same instant agree even while the system clock is being corrected.

`>= 0` is declaratory (`uint64`), as with the two nanosecond fields.



