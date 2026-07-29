---
id: ser.SamTrackingKalmanState
proto: opaque/sam_tracking_common.proto
package: ser
type: message
---

# SamTrackingKalmanState

**Source:** `opaque/sam_tracking_common.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | predicted_x | double | >= -1, <= 1 |
| 2 | predicted_y | double | >= -1, <= 1 |
| 3 | velocity_x | double | - |
| 4 | velocity_y | double | - |




## Field Notes


### predicted_x (#1)

Horizontal component of the filter's predicted centroid position, expressed in **NDC (normalised device coordinates)** — a screen coordinate, not a distance, not an angle and not a pixel column.

The convention for this pipeline is defined in the sibling [[proto/ser.SamTrackingDay]] and [[proto/ser.SamTrackingHeat]] protos: **-1.0 is the left edge of the frame, +1.0 the right edge, and (0,0) the centre**. Because the frame's own width is what maps onto that fixed span, the same NDC value denotes the same place on screen at any sensor resolution — which is the reason the wire carries NDC rather than pixels at all. A consumer that needs pixels supplies the frame width itself: `px = (x + 1) / 2 * width`. A consumer that reads the raw number as metres or as a pixel index is wrong by whatever the current frame happens to be, and the error scales with resolution rather than announcing itself.

The predicted quantity is the same one `centroid_x` on [[proto/ser.SamTrackingDay]] / [[proto/ser.SamTrackingHeat]] reports as *measured* — those protos describe it as the K-Medoids cluster centre, and as the point used to prompt the next tracking iteration. **Neither proto states which point set is clustered**, so do not read the measured centroid as "the centre of the mask"; that attribution is not in the tree. Both values live in the same NDC frame, so the two are directly comparable on the same tick, and their difference is how far the filter's forecast sat from the observation for that frame.

What the [-1, 1] bound is for: it declares an off-frame prediction out of contract, so a value that arrives is a point inside the visible frame rather than something a consumer has to defend against. That bound is load-bearing precisely because a filter *extrapolates* — a target moving toward an edge produces forecasts that run past it — and this is where that case is caught. **The tree does not record how the producer handles it** (clamping to the edge versus suppressing the estimate), so a value sitting exactly at ±1.0 must not be assumed to be a genuine position; it may equally be the bound being met.

Read the whole message for what it is: it declares itself *"Kalman filter state for visualization and debugging"*, so these values are published so a consumer can draw or inspect the filter — not so it can re-derive the track. Where the prediction becomes load-bearing *inside* the tracker is recorded in the enum's own proto comment rather than on its page — [[proto/ser.SamTrackingState]] carries no description for any of its values, so the source is `sam_tracking_common.proto` itself, where `SAM_TRACKING_STATE_OCCLUDED` is commented *"Low confidence, using Kalman prediction"*. In that state the tracker is running on the prediction rather than on a measurement it is confident in. **What the mask and bounding box contain during OCCLUDED is not stated anywhere in the tree** — do not assume they hold either the prediction or a stale measurement; establish it against the producer before an overlay treats them as one or the other.


### predicted_y (#2)

Vertical component of the same predicted centroid, on the same [-1, 1] NDC span as `predicted_x`.

**The vertical axis points DOWN.** The sibling [[proto/ser.SamTrackingDay]] and [[proto/ser.SamTrackingHeat]] protos state the convention explicitly: **-1.0 is the TOP of the frame and +1.0 the BOTTOM**. It is worth stating twice because the opposite orientation is common elsewhere, and a consumer that assumes it mirrors every prediction about the frame's horizontal centre line — an error that is invisible on a centred target and grows steadily toward the top and bottom edges. Pixels follow the same way as for the horizontal axis: `py = (y + 1) / 2 * height`.

Everything under `predicted_x` about the bound, about comparability with the measured `centroid_y`, and about what this message is published for applies unchanged to this axis.



