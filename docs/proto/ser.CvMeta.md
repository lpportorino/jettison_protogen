---
id: ser.CvMeta
proto: opaque/cv_meta.proto
package: ser
type: message
---

# CvMeta

**Source:** `opaque/cv_meta.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | capture_monotonic_us | uint64 | >= 0 |
| 2 | updated_sources | uint32 | >= 0, <= 31 |
| 3 | camera_day | [[proto/ser.JonGuiDataCameraDay]] | - |
| 4 | camera_heat | [[proto/ser.JonGuiDataCameraHeat]] | - |
| 5 | rotary | [[proto/ser.JonGuiDataRotary]] | - |
| 6 | channel_day | [[proto/ser.CvChannelMeta]] | - |
| 7 | channel_heat | [[proto/ser.CvChannelMeta]] | - |




