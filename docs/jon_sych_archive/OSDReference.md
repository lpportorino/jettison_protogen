# OSDReference (jon.archive.OSDReference)

**Source:** `jon_sych_archive.proto`

## Description

Archive file structure metadata.

## Fields

| Field | Type | Number | Description | Constraints |
|-------|------|--------|-------------|-------------|
| package_path | string | 1 | Path to OSD package tar within archive (e.g., "osd/package.tar") | - |
| config_path | string | 2 | Path to OSD config JSON within archive (e.g., "osd/config.json") | - |
| package_name | string | 3 | Package metadata | - |
| package_version | string | 4 | - | - |
| package_variant | string | 5 | Package variant (only recording_day supported for offline playback) | - |

## Usage Context

Part of the Jettison protocol buffer schema.

## Related Messages

- See `jon_sych_archive.proto` for complete context
