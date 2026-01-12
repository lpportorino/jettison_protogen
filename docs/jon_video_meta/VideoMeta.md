# VideoMeta (jon.video.VideoMeta)

**Source:** `jon_video_meta.proto`

## Description

Video metadata or processing information.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| uuid | string | 1 | - | - |
| session_id | int32 | 2 | - | - |
| timestamp | uint64 | 3 | - | - |
| storage_path | string | 4 | - | - |
| source_type | string | 5 | - | - |
| frame_count | uint32 | 6 | Per-video MOOV data | - |
| duration_ms | uint32 | 7 | - | - |
| sample_table | SampleTable | 12 | Sample table for frame-accurate seeking and playback | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_video_meta.proto` for complete context
