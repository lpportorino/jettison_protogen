---
id: ser.JonGuiDataAccumulatorStateIdx
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataAccumulatorStateIdx

**Source:** `jon_shared_data_types.proto`

## Description

Represents the charge state index of an internal battery (accumulator) with 11 discrete states ranging from empty to full, plus a charging state. Used in the battery indicator UI component with color-coded visual feedback (red=empty, orange=low, yellow=medium, green=good/full, blue=charging).

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_ACCUMULATOR_STATE_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_ACCUMULATOR_STATE_UNKNOWN | Unknown battery state |
| 2 | JON_GUI_DATA_ACCUMULATOR_STATE_EMPTY | Battery empty |
| 3 | JON_GUI_DATA_ACCUMULATOR_STATE_1 | Battery level 1 (lowest) |
| 4 | JON_GUI_DATA_ACCUMULATOR_STATE_2 | Battery level 2 |
| 5 | JON_GUI_DATA_ACCUMULATOR_STATE_3 | Battery level 3 |
| 6 | JON_GUI_DATA_ACCUMULATOR_STATE_4 | Battery level 4 |
| 7 | JON_GUI_DATA_ACCUMULATOR_STATE_5 | Battery level 5 |
| 8 | JON_GUI_DATA_ACCUMULATOR_STATE_6 | Battery level 6 |
| 9 | JON_GUI_DATA_ACCUMULATOR_STATE_FULL | Battery fully charged |
| 10 | JON_GUI_DATA_ACCUMULATOR_STATE_CHARGING | Battery charging |

