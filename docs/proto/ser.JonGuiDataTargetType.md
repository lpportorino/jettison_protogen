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
| 0 | JON_GUI_DATA_TARGET_TYPE_UNSPECIFIED | The proto3 zero default, and — unlike most enums in this schema — a LEGAL wire value here: `JonGuiDataTarget.capture_type` is constrained `defined_only` with no `not_in: [0]`, deliberately, because it marks a capture record written before the discriminator existed and whose kind is therefore simply unknown. |
| 1 | JON_GUI_DATA_TARGET_TYPE_TARGET | The capture event is a ranged TARGET: the LRF returned a valid range for it, so the distance and georeferenced coordinates in `JonGuiDataTarget` describe a real measurement. Published by manifold from its internal `has_range` flag. |
| 2 | JON_GUI_DATA_TARGET_TYPE_PHOTO | The capture event is a PHOTO — no valid range is attached. Two distinct paths land here: the operator issued a Photo command, or an LRF measure fired and missed. Both fall on the photo side of the media API and gallery split that media_meta_pub derives from this field. |

