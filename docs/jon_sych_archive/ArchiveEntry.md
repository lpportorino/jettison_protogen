# ArchiveEntry (jon.archive.ArchiveEntry)

**Source:** `jon_sych_archive.proto`

## Description

Archive file structure metadata.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| path | string | 1 | Path within tar (e.g., "videos/001_abc.mp4") | - |
| header_offset | uint64 | 2 | Byte offset of tar header in archive | - |
| data_offset | uint64 | 3 | Byte offset of file content (header_offset + 512) | - |
| size | uint64 | 4 | File size in bytes | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_sych_archive.proto` for complete context
