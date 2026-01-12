# VideoEntry (jon.archive.VideoEntry)

**Source:** `jon_sych_archive.proto`

## Description

Archive file structure metadata.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| id | string | 1 | Original video ID (video:session:uuid format) | - |
| archive_path | string | 2 | Path in archive (e.g., "videos/001_abc.mp4") | - |
| thumbnail_path | optional string | 3 | Thumbnail path in archive (optional, e.g., "thumbnails/001_abc.png") | - |
| global_frame_start | uint32 | 4 | Start frame in global timeline | - |
| meta | jon.video.VideoMeta | 5 | Embedded video metadata including sample table for frame-accurate seeking | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_sych_archive.proto` for complete context
