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
| 0 | JON_GUI_DATA_ACCUMULATOR_STATE_UNSPECIFIED | Proto3 zero default. The two carriers disagree on whether it may travel: `JonGuiDataSystem.accumulator_state` (#24) applies `defined_only` + `not_in:[0]` and refuses it, while `JonGuiDataPower.accumulator_state` (#9) is declared bare — so a consumer of the power fragment WILL see 0 from a producer that never set the field, and must not read it as a charge level. |
| 1 | JON_GUI_DATA_ACCUMULATOR_STATE_UNKNOWN | A REPORTED state: the producer is populating the field but cannot determine the charge level. Distinct from UNSPECIFIED, which is the never-set default — the two are separate members precisely so "I don't know" is distinguishable from "nobody said". |
| 2 | JON_GUI_DATA_ACCUMULATOR_STATE_EMPTY | The bottom of the declared charge ladder. |
| 3 | JON_GUI_DATA_ACCUMULATOR_STATE_1 | First of six intermediate steps. The ladder is declared in ascending order EMPTY → 1..6 → FULL, so a consumer may order these values; NO mapping from a step to a voltage or a state-of-charge percentage is defined anywhere in this repository, so a consumer must NOT render one as a percentage. |
| 4 | JON_GUI_DATA_ACCUMULATOR_STATE_2 | Second of six intermediate steps, declared above STATE_1 and below STATE_3. |
| 5 | JON_GUI_DATA_ACCUMULATOR_STATE_3 | Third of six intermediate steps, declared above STATE_2 and below STATE_4. |
| 6 | JON_GUI_DATA_ACCUMULATOR_STATE_4 | Fourth of six intermediate steps, declared above STATE_3 and below STATE_5. |
| 7 | JON_GUI_DATA_ACCUMULATOR_STATE_5 | Fifth of six intermediate steps, declared above STATE_4 and below STATE_6. |
| 8 | JON_GUI_DATA_ACCUMULATOR_STATE_6 | Sixth and last intermediate step, declared above STATE_5 and below FULL. |
| 9 | JON_GUI_DATA_ACCUMULATOR_STATE_FULL | The top of the declared charge ladder. |
| 10 | JON_GUI_DATA_ACCUMULATOR_STATE_CHARGING | The internal accumulator is charging. Because this is one enum field carrying one value, CHARGING is reported INSTEAD of a level — while it is in force this field says nothing about how full the accumulator is. The sibling `ext_bat_capacity` does not fill that gap — it is the EXTERNAL pack's percentage, not this accumulator's. |

