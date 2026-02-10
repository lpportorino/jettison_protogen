---
id: ser.JonGuiDataLrfScanModes
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataLrfScanModes

**Source:** `jon_shared_data_types.proto`

## Description

Specifies continuous scanning frequency modes for the laser rangefinder (LRF) device, allowing operators to configure how frequently distance measurements are acquired. Supports rates ranging from 1 Hz to 200 Hz for different precision and responsiveness requirements.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | JON_GUI_DATA_LRF_SCAN_MODE_UNSPECIFIED | Unspecified/default value |
| 1 | JON_GUI_DATA_LRF_SCAN_MODE_1_HZ_CONTINUOUS | 1 Hz continuous scanning |
| 2 | JON_GUI_DATA_LRF_SCAN_MODE_4_HZ_CONTINUOUS | 4 Hz continuous scanning |
| 3 | JON_GUI_DATA_LRF_SCAN_MODE_10_HZ_CONTINUOUS | 10 Hz continuous scanning |
| 4 | JON_GUI_DATA_LRF_SCAN_MODE_20_HZ_CONTINUOUS | 20 Hz continuous scanning |
| 5 | JON_GUI_DATA_LRF_SCAN_MODE_100_HZ_CONTINUOUS | 100 Hz continuous scanning |
| 6 | JON_GUI_DATA_LRF_SCAN_MODE_200_HZ_CONTINUOUS | 200 Hz continuous scanning |

