# SychArchiveIndex (jon.archive.SychArchiveIndex)

**Source:** `jon_sych_archive.proto`

## Description

Archive file structure metadata.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| version | uint32 | 1 | Format version (currently 1) | >= 1, <= 255 |
| created_at | uint64 | 2 | Unix timestamp when archive was created | - |
| exported_from | string | 3 | Origin URL for "go online" link (e.g., "https://sych.local") | - |
| files | repeated ArchiveEntry | 4 | File index with byte offsets for direct seeking | - |
| timeline | TimelineIndex | 5 | Timeline data with embedded video metadata | - |
| osd | optional OSDReference | 6 | OSD reference (paths within archive) - optional | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_sych_archive.proto` for complete context
