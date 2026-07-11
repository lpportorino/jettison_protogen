---
id: ser.JonGuiDataTargetType
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataTargetType

**Source:** `jon_shared_data_types.proto`

## Description

Discriminates what a capture event (a `target_id` increment in `ser.JonGuiDataTarget`) is: a ranged TARGET or a PHOTO. Published by manifold from the internal `has_range` flag; consumed by media_meta_pub to set the media_items `kind`, which drives the photo/target split in the media API and gallery.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_TARGET_TYPE_UNSPECIFIED | - |
| 1 | JON_GUI_DATA_TARGET_TYPE_TARGET | - |
| 2 | JON_GUI_DATA_TARGET_TYPE_PHOTO | - |

