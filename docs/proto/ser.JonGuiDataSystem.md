---
id: ser.JonGuiDataSystem
proto: jon_shared_data_system.proto
package: ser
type: message
---

# JonGuiDataSystem

**Source:** `jon_shared_data_system.proto`

## Description

Captures comprehensive device telemetry including hardware metrics (CPU/GPU temperature and load), recording state with timestamped directories, storage status with warning indicators, operational modes (tracking, stabilization, recognition, geodesic, vampire, CV dumping), and battery status, enabling real-time monitoring of system health and operational state in the frontend UI.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | cpu_temperature | double | >= -273.15, <= 150 |
| 2 | gpu_temperature | double | >= -273.15, <= 150 |
| 3 | gpu_load | double | >= 0, <= 100 |
| 4 | cpu_load | double | >= 0, <= 100 |
| 5 | power_consumption | double | >= 0, <= 1000 |
| 6 | loc | [[proto/ser.JonGuiDataSystemLocalizations]] | defined enum value only, not in: 0 |
| 7 | cur_video_rec_dir_year | int32 | >= 0 |
| 8 | cur_video_rec_dir_month | int32 | >= 0 |
| 9 | cur_video_rec_dir_day | int32 | >= 0 |
| 10 | cur_video_rec_dir_hour | int32 | >= 0 |
| 11 | cur_video_rec_dir_minute | int32 | >= 0 |
| 12 | cur_video_rec_dir_second | int32 | >= 0 |
| 13 | rec_enabled | bool | - |
| 14 | important_rec_enabled | bool | - |
| 15 | low_disk_space | bool | - |
| 16 | no_disk_space | bool | - |
| 17 | disk_space | int32 | >= 0, <= 100 |
| 18 | tracking | bool | - |
| 19 | vampire_mode | bool | - |
| 20 | stabilization_mode | bool | - |
| 21 | geodesic_mode | bool | - |
| 22 | cv_dumping | bool | - |
| 23 | recognition_mode | bool | - |
| 24 | accumulator_state | [[proto/ser.JonGuiDataAccumulatorStateIdx]] | defined enum value only, not in: 0 |
| 25 | ext_bat_capacity | int32 | >= 0, <= 100 |
| 26 | ext_bat_status | [[proto/ser.JonGuiDataExtBatStatus]] | - |



## Interaction

- **Category:** :status
- **UI Pattern:** :tabbed-config
- **Feedback:** :fire-and-forget


### Purpose

System health, resource usage, and operational mode status



### Related Commands

- [[proto/cmd.System.DisableGeodesicMode]]
- [[proto/cmd.System.EnableGeodesicMode]]
- [[proto/cmd.System.SaveFactoryDefaults]]



### Implementation Notes

Comprehensive system status including CPU/GPU metrics, recording state, operational modes (tracking, vampire, stabilization, geodesic, CV dumping, recognition), and battery status.



## Field Notes


### cpu_temperature (#1)

Temperature in degrees Celsius


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1
- **Display Format:** `{value}°C`


### gpu_temperature (#2)

Temperature in degrees Celsius


#### Metadata

- **Semantic Type:** :temperature
- **Unit:** °C
- **Precision:** 1
- **Display Format:** `{value}°C`


### gpu_load (#3)

GPU utilization percentage


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value}%`


### cpu_load (#4)

CPU utilization percentage


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value}%`


### power_consumption (#5)

Power consumption in watts


#### Metadata

- **Semantic Type:** :power
- **Unit:** W
- **Precision:** 1
- **Display Format:** `{value}W`


### loc (#6)

See related enum for valid values


### cur_video_rec_dir_year (#7)

Recording directory year component


### cur_video_rec_dir_month (#8)

Recording directory month component


### cur_video_rec_dir_day (#9)

Recording directory day component


### cur_video_rec_dir_hour (#10)

Recording directory hour component


### cur_video_rec_dir_minute (#11)

Recording directory minute component


### cur_video_rec_dir_second (#12)

Recording directory second component


### disk_space (#17)

Percentage value (0-100)


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value}%`


### tracking (#18)

Whether CV **point tracking** is active — the operator-seeded target follow. It is raised by [[proto/cmd.CV.StartTrackNDC]], which names a point in normalized device coordinates on one video channel, and cleared by [[proto/cmd.CV.StopTrack]], which terminates following on both the day and thermal pipelines at once.

It is the affordance flag for that control: the tracking button is shown only while this is `true`.

**It does NOT cover the Ring-Trinity board tracker.** That one has an activity flag of its own — `trinity_tracking_active` (#90) on [[proto/ser.JonGuiDataCV]], driven by [[proto/cmd.CV.StartTrackTrinity]] / [[proto/cmd.CV.StopTrackTrinity]] — and nothing in the schema binds the two fields in either direction. Reading this one as "is anything being tracked" is therefore wrong in both directions.

**A schema-level caveat shared by every bool in this block (#18–#23):** these are plain proto3 `bool`s with no presence, so `false` and "never populated" are the same bytes. A consumer cannot distinguish a mode that is off from a producer that did not report it, and no constraint here makes that distinguishable.


#### Metadata

- **Semantic Type:** :raw


### vampire_mode (#19)

Whether vampire mode is engaged, driven by [[proto/cmd.CV.VampireModeEnable]] / [[proto/cmd.CV.VampireModeDisable]]. Those pages document the behaviour as the cameras actively avoiding the sun — declining to point at a bright source so the sensors are not overexposed or damaged.

The mode is a protective constraint on where the cameras may look, so it can make a commanded slew refuse or deviate. A consumer that issues pointing commands should read this flag before attributing an unexecuted move to a fault.

See `tracking` (#18) for the presence caveat that applies to this field too.


#### Metadata

- **Semantic Type:** :raw


### stabilization_mode (#20)

Whether CV-based image stabilization is engaged, driven by [[proto/cmd.CV.StabilizationModeEnable]] / [[proto/cmd.CV.StabilizationModeDisable]]. Enabled, the system applies real-time compensation for camera shake and vibration so the video feed is steadier; disabled, the image responds freely to manual movement.

It therefore changes what the video plane MEANS for anything measured off it: a stabilized feed has had motion removed between the sensor and the frame, so a consumer correlating image-space geometry against platform motion needs to know which regime produced the frame.

See `tracking` (#18) for the presence caveat that applies to this field too.


#### Metadata

- **Semantic Type:** :raw


### geodesic_mode (#21)

Whether geodesic (geographic) coordinate mode is engaged, driven by [[proto/cmd.System.EnableGeodesicMode]] / [[proto/cmd.System.DisableGeodesicMode]]. Enabled, the system positions and reports object locations in geographic coordinates derived by triangulation rather than in a local reference frame.

**It is the one mode in this block commanded from `cmd.System` rather than `cmd.CV`.** Every other flag here (#18, #19, #20, #22, #23) is driven by a computer-vision command pair; this one is a system-level positioning mode that happens to be reported alongside them. [[proto/cmd.CV.Root]]'s oneof carries no arm for it, so a toggle routed to the CV package cannot be encoded at all rather than being accepted and ignored.

Because it changes the frame positions are expressed in, it is not a display preference — a consumer must not cache a position across a transition of this flag and assume the numbers still mean the same thing.

See `tracking` (#18) for the presence caveat that applies to this field too.


#### Metadata

- **Semantic Type:** :raw


### cv_dumping (#22)

Whether computer-vision frame data is being recorded to disk for debugging and analysis, driven by [[proto/cmd.CV.DumpStart]] / [[proto/cmd.CV.DumpStop]].

This is a diagnostic capture rather than an operational mode: it writes CV frames for later inspection and does not change what the pipeline computes. It is consequently the one flag here whose cost is storage — a consumer surfacing it should treat a long-running `true` as worth reporting, since nothing in this message bounds how much has been written (`disk_space` (#17) and `low_disk_space` (#15) are the fields that show the effect).

See `tracking` (#18) for the presence caveat that applies to this field too.


#### Metadata

- **Semantic Type:** :raw


### recognition_mode (#23)

Whether AI object recognition is engaged — automatic detection and classification of objects in the video feed — driven by [[proto/cmd.CV.RecognitionModeEnable]] / [[proto/cmd.CV.RecognitionModeDisable]].

**This is the ONLY recognition flag in the schema.** [[proto/ser.JonGuiDataCV]] carries none, despite recognition being a CV-subsystem behaviour, so the readback for that command pair lives here on the system state message and nowhere else. A consumer reflecting the toggle reads this field.

Recognition being on is not the same as its output being present: the detections themselves do not travel on this message. They ride `JonGUIState.opaque_payloads` as [[proto/ser.ObjectDetectionsDay]] / [[proto/ser.ObjectDetectionsHeat]] entries, decoded only by the consumers that handle those payload types. This flag says the mode is engaged; it does not say anything was detected.

See `tracking` (#18) for the presence caveat that applies to this field too.


#### Metadata

- **Semantic Type:** :raw


### accumulator_state (#24)

See related enum for valid values


### ext_bat_capacity (#25)

Percentage value (0-100)


#### Metadata

- **Semantic Type:** :percentage
- **Unit:** %
- **Precision:** 0
- **Display Format:** `{value}%`



