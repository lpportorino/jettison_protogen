# VideoMetaResponse (jon.video.VideoMetaResponse)

**Source:** `jon_video_meta.proto`

## Description

Video metadata or processing information.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| videos | repeated VideoMeta | 1 | - | - |
| errors | repeated VideoError | 2 | - | - |
| total_count | uint32 | 3 | - | - |
| width | uint32 | 10 | Shared encoding parameters (same for all videos in response) | - |
| height | uint32 | 11 | - | - |
| dsi | bytes | 12 | - | - |
| timescale | uint32 | 13 | - | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_video_meta.proto` for complete context
