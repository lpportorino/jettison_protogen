# TimelineIndex (jon.archive.TimelineIndex)

**Source:** `jon_sych_archive.proto`

## Description

Archive file structure metadata.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| total_frames | uint32 | 1 | Total frame count across all videos | - |
| total_duration_ms | uint32 | 2 | Total duration in milliseconds | - |
| videos | repeated VideoEntry | 3 | Video entries in playback order | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_sych_archive.proto` for complete context
