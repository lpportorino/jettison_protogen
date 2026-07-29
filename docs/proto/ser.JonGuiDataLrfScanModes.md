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
| 0 | JON_GUI_DATA_LRF_SCAN_MODE_UNSPECIFIED | The proto3 zero default, not a rate. `cmd.Lrf.SetScanMode` constrains the field `not_in: [0]`, so it can never be commanded; it appears only as the unset value of a message that was never populated. |
| 1 | JON_GUI_DATA_LRF_SCAN_MODE_1_HZ_CONTINUOUS | One range measurement per second — a 1000 ms interval between shots — while continuous scanning is running. The slowest defined rate. Note two things this enum does NOT encode: the ordinal is not the frequency (this is value 1, not 1 Hz by coincidence of numbering), and continuity is not a choice — every defined member is continuous, because scanning is turned on and off by `cmd.Lrf.ScanOn` / `cmd.Lrf.ScanOff` and a single shot is `cmd.Lrf.Measure`, so RATE is the only axis these values vary. |
| 2 | JON_GUI_DATA_LRF_SCAN_MODE_4_HZ_CONTINUOUS | Four range measurements per second, 250 ms apart. |
| 3 | JON_GUI_DATA_LRF_SCAN_MODE_10_HZ_CONTINUOUS | Ten range measurements per second, 100 ms apart. |
| 4 | JON_GUI_DATA_LRF_SCAN_MODE_20_HZ_CONTINUOUS | Twenty range measurements per second, 50 ms apart. |
| 5 | JON_GUI_DATA_LRF_SCAN_MODE_100_HZ_CONTINUOUS | One hundred range measurements per second, 10 ms apart. |
| 6 | JON_GUI_DATA_LRF_SCAN_MODE_200_HZ_CONTINUOUS | Two hundred range measurements per second, 5 ms apart — the fastest defined rate, two hundred times the shot density of the 1 Hz mode. |

