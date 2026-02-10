---
id: ser.DetectionConfig
proto: opaque/detection_common.proto
package: ser
type: message
---

# DetectionConfig

**Source:** `opaque/detection_common.proto`

## Description

Inference configuration snapshot attached to each detection result. Records the confidence and NMS thresholds that were active when the detector produced a given batch of detections. Embedded as the `config` field in both [[ser.ObjectDetectionsDay]] and [[ser.ObjectDetectionsHeat]].

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | confidence_threshold | float | >= 0, <= 1 |
| 2 | nms_iou_threshold | float | >= 0, <= 1 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :tabbed-config
- **Feedback:** :fire-and-forget








## Field Notes


### confidence_threshold (#1)

Minimum confidence score a detection must meet to be retained in the results. Detections with a confidence below this value are discarded before the batch is published. A higher threshold reduces false positives at the cost of missed detections; a lower threshold is more permissive.
- **Semantic Type:** normalized
- **Precision:** 2


### nms_iou_threshold (#2)

Intersection over Union (IoU) threshold used during Non-Maximum Suppression. When two bounding boxes for the same class overlap by more than this ratio, the lower-confidence box is suppressed. A lower value makes suppression more aggressive (fewer overlapping boxes); a higher value allows more overlapping detections to coexist.
- **Semantic Type:** normalized
- **Precision:** 2



