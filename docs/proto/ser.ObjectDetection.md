---
id: ser.ObjectDetection
proto: opaque/detection_common.proto
package: ser
type: message
---

# ObjectDetection

**Source:** `opaque/detection_common.proto`

## Description

A single object detection bounding box result from the inference engine. Detector-agnostic: used by both day and heat camera channels within [[proto/ser.ObjectDetectionsDay]] and [[proto/ser.ObjectDetectionsHeat]]. The bounding box is expressed in Normalized Device Coordinates (NDC) where (-1, -1) is the top-left corner, (1, 1) is the bottom-right corner, and (0, 0) is the center of the frame. This coordinate system is consistent with [[proto/ser.JonGuiDataROI]]. Up to 256 detections may be reported per frame.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | x1 | float | >= -1, <= 1 |
| 2 | y1 | float | >= -1, <= 1 |
| 3 | x2 | float | >= -1, <= 1 |
| 4 | y2 | float | >= -1, <= 1 |
| 5 | confidence | float | >= 0, <= 1 |
| 6 | class_id | int32 | >= 0, <= 255 |



## Interaction

- **Category:** :status
- **UI Pattern:** :indicator
- **Feedback:** :fire-and-forget








## Field Notes


### x1 (#1)

Left edge of the bounding box in NDC. A value of -1.0 corresponds to the left edge of the frame.
- **Semantic Type:** coordinate-viewport
- **Precision:** 4


### y1 (#2)

Top edge of the bounding box in NDC. A value of -1.0 corresponds to the top edge of the frame.
- **Semantic Type:** coordinate-viewport
- **Precision:** 4


### x2 (#3)

Right edge of the bounding box in NDC. A value of 1.0 corresponds to the right edge of the frame. Must be greater than or equal to x1 for a valid detection.
- **Semantic Type:** coordinate-viewport
- **Precision:** 4


### y2 (#4)

Bottom edge of the bounding box in NDC. A value of 1.0 corresponds to the bottom edge of the frame. Must be greater than or equal to y1 for a valid detection.
- **Semantic Type:** coordinate-viewport
- **Precision:** 4


### confidence (#5)

Model confidence score for this detection, where 0.0 is no confidence and 1.0 is maximum confidence. Detections below the configured confidence threshold (see [[proto/ser.DetectionConfig]]) are filtered before reaching this message.
- **Semantic Type:** normalized
- **Precision:** 2


### class_id (#6)

Detector-specific object class identifier. The mapping from class ID to semantic label (e.g. person, vehicle) depends on the inference model loaded by the detection engine. Valid range is 0-255.
- **Semantic Type:** identifier



