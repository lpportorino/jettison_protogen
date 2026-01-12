# SampleTable (jon.video.SampleTable)

**Source:** `jon_video_meta.proto`

## Description

Video metadata or processing information.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| sample_sizes | repeated uint32 | 1 | - | - |
| chunk_offsets | repeated uint64 | 2 | - | - |
| sample_times | repeated uint32 | 3 | - | - |
| sync_samples | repeated uint32 | 4 | - | - |
| sample_to_chunk | repeated SampleToChunk | 5 | - | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_video_meta.proto` for complete context
