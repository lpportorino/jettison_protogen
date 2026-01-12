# VideoRangeQuery (jon.video.VideoRangeQuery)

**Source:** `jon_video_meta.proto`

## Description

Video metadata or processing information.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| start_timestamp | uint64 | 1 | - | - |
| end_timestamp | uint64 | 2 | - | - |
| source_type | optional string | 3 | - | - |
| limit | optional uint32 | 4 | - | >= 1, <= 1000 |
| offset | optional uint32 | 5 | - | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_video_meta.proto` for complete context
