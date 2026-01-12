# Protobuf Message Index

This document enumerates all protobuf messages and enums defined in the jettison_protogen repository, organized by proto file.

## Table of Contents

- [Client Logs](#client-logs)
- [Command Messages](#command-messages)
  - [Compass Commands](#compass-commands)
  - [Computer Vision Commands](#computer-vision-commands)
  - [Day Camera Commands](#day-camera-commands)
  - [Day Camera Glass Heater Commands](#day-camera-glass-heater-commands)
  - [GPS Commands](#gps-commands)
  - [Heat Camera Commands](#heat-camera-commands)
  - [LIRA Commands](#lira-commands)
  - [LRF Align Commands](#lrf-align-commands)
  - [LRF Commands](#lrf-commands)
  - [OSD Commands](#osd-commands)
  - [Power Commands](#power-commands)
  - [Rotary Platform Commands](#rotary-platform-commands)
  - [System Commands](#system-commands)
  - [Command Root](#command-root)
- [Data Messages](#data-messages)
  - [Actual Space Time Data](#actual-space-time-data)
  - [Camera Day Data](#camera-day-data)
  - [Camera Heat Data](#camera-heat-data)
  - [Compass Calibration Data](#compass-calibration-data)
  - [Compass Data](#compass-data)
  - [Day Camera Glass Heater Data](#day-camera-glass-heater-data)
  - [GPS Data](#gps-data)
  - [LRF Data](#lrf-data)
  - [Power Data](#power-data)
  - [Recording OSD Data](#recording-osd-data)
  - [Rotary Data](#rotary-data)
  - [System Data](#system-data)
  - [Time Data](#time-data)
  - [Data Root](#data-root)
- [Type Definitions](#type-definitions)
- [Archive](#archive)
- [Video Metadata](#video-metadata)
- [Test Messages](#test-messages)

---

## Client Logs

**File:** `proto/jon_client_logs.proto`

**Package:** `jon.logs`

### Messages

- **ClientLogEntry** - Client log entry representing a single log message from the frontend
- **ClientLogBatch** - Batch of client log entries sent over WebSocket

---

## Command Messages

### Compass Commands

**File:** `proto/jon_shared_cmd_compass.proto`

**Package:** `cmd.Compass`

#### Messages

- **Root** - Compass command root message
- **Start** - Start compass
- **Stop** - Stop compass
- **Next** - Next command
- **CalibrateStartLong** - Start long calibration
- **CalibrateStartShort** - Start short calibration
- **CalibrateNext** - Next calibration step
- **CalibrateCencel** - Cancel calibration
- **GetMeteo** - Get meteorological data
- **SetMagneticDeclination** - Set magnetic declination value
- **SetOffsetAngleAzimuth** - Set offset angle for azimuth
- **SetOffsetAngleElevation** - Set offset angle for elevation
- **SetUseRotaryPosition** - Set whether to use rotary position

### Computer Vision Commands

**File:** `proto/jon_shared_cmd_cv.proto`

**Package:** `cmd.CV`

#### Messages

- **Root** - Computer vision command root message
- **VampireModeEnable** - Enable vampire mode
- **DumpStart** - Start dump
- **DumpStop** - Stop dump
- **VampireModeDisable** - Disable vampire mode
- **StabilizationModeEnable** - Enable stabilization mode
- **StabilizationModeDisable** - Disable stabilization mode
- **RecognitionModeEnable** - Enable recognition mode
- **RecognitionModeDisable** - Disable recognition mode
- **SetAutoFocus** - Set auto focus for a channel
- **StartTrackNDC** - Start tracking at NDC coordinates
- **StopTrack** - Stop tracking

### Day Camera Commands

**File:** `proto/jon_shared_cmd_day_camera.proto`

**Package:** `cmd.DayCamera`

#### Messages

- **Root** - Day camera command root message
- **SetValue** - Set a normalized value (0.0-1.0)
- **Move** - Move to target value at specified speed
- **Offset** - Apply an offset value
- **SetClaheLevel** - Set CLAHE enhancement level
- **ShiftClaheLevel** - Shift CLAHE level by delta
- **GetPos** - Get current position
- **NextFxMode** - Switch to next FX mode
- **PrevFxMode** - Switch to previous FX mode
- **RefreshFxMode** - Refresh current FX mode
- **HaltAll** - Halt all camera movements
- **SetFxMode** - Set specific FX mode
- **SetDigitalZoomLevel** - Set digital zoom level
- **Focus** - Focus control commands
- **Zoom** - Zoom control commands
- **NextZoomTablePos** - Next zoom table position
- **PrevZoomTablePos** - Previous zoom table position
- **SetIris** - Set iris value
- **SetInfraRedFilter** - Set infrared filter state
- **SetAutoIris** - Enable/disable auto iris
- **SetAutoGain** - Enable/disable auto gain
- **SetZoomTableValue** - Set zoom table value
- **Stop** - Stop camera
- **Start** - Start camera
- **Photo** - Take photo
- **Halt** - Halt movement
- **GetMeteo** - Get meteorological data
- **ResetZoom** - Reset zoom to default
- **ResetFocus** - Reset focus to default
- **SaveToTable** - Save current zoom to table
- **SaveToTableFocus** - Save current focus to table
- **FocusROI** - Focus on region of interest
- **TrackROI** - Track region of interest
- **ZoomROI** - Zoom to region of interest
- **FxROI** - Apply FX to region of interest

### Day Camera Glass Heater Commands

**File:** `proto/jon_shared_cmd_day_cam_glass_heater.proto`

**Package:** `cmd.DayCamGlassHeater`

#### Messages

- **Root** - Day camera glass heater command root message
- **Start** - Start heater control
- **Stop** - Stop heater control
- **TurnOn** - Turn heater on
- **TurnOff** - Turn heater off
- **GetMeteo** - Get meteorological data

### GPS Commands

**File:** `proto/jon_shared_cmd_gps.proto`

**Package:** `cmd.Gps`

#### Messages

- **Root** - GPS command root message
- **Start** - Start GPS
- **Stop** - Stop GPS
- **GetMeteo** - Get meteorological data
- **SetUseManualPosition** - Enable/disable manual position
- **SetManualPosition** - Set manual GPS position

### Heat Camera Commands

**File:** `proto/jon_shared_cmd_heat_camera.proto`

**Package:** `cmd.HeatCamera`

#### Messages

- **Root** - Heat camera command root message
- **SetFxMode** - Set FX mode
- **SetClaheLevel** - Set CLAHE enhancement level
- **ShiftClaheLevel** - Shift CLAHE level by delta
- **NextFxMode** - Switch to next FX mode
- **PrevFxMode** - Switch to previous FX mode
- **RefreshFxMode** - Refresh current FX mode
- **EnableDDE** - Enable digital detail enhancement
- **DisableDDE** - Disable digital detail enhancement
- **SetValue** - Set normalized value
- **SetDDELevel** - Set DDE level
- **SetDigitalZoomLevel** - Set digital zoom level
- **ShiftDDE** - Shift DDE level by offset
- **ZoomIn** - Zoom in
- **ZoomOut** - Zoom out
- **ZoomStop** - Stop zoom
- **FocusIn** - Focus in
- **FocusOut** - Focus out
- **FocusStop** - Stop focus
- **FocusStepPlus** - Focus step plus
- **FocusStepMinus** - Focus step minus
- **Calibrate** - Calibrate thermal camera
- **Zoom** - Zoom control commands
- **NextZoomTablePos** - Next zoom table position
- **PrevZoomTablePos** - Previous zoom table position
- **SetCalibMode** - Set calibration mode
- **SetZoomTableValue** - Set zoom table value
- **SetAGC** - Set AGC mode
- **SetFilters** - Set thermal filters
- **Start** - Start camera
- **Stop** - Stop camera
- **Halt** - Halt movement
- **Photo** - Take photo
- **GetMeteo** - Get meteorological data
- **SetAutoFocus** - Enable/disable auto focus
- **ResetZoom** - Reset zoom to default
- **SaveToTable** - Save current zoom to table
- **FocusROI** - Focus on region of interest
- **TrackROI** - Track region of interest
- **ZoomROI** - Zoom to region of interest
- **FxROI** - Apply FX to region of interest

### LIRA Commands

**File:** `proto/jon_shared_cmd_lira.proto`

**Package:** `cmd.Lira`

#### Messages

- **Root** - LIRA command root message
- **Refine_target** - Refine target position
- **JonGuiDataLiraTarget** - LIRA target data with coordinates and UUID

### LRF Align Commands

**File:** `proto/jon_shared_cmd_lrf_align.proto`

**Package:** `cmd.Lrf_calib`

#### Messages

- **Root** - LRF alignment command root message
- **Offsets** - Offset adjustment commands
- **SetOffsets** - Set crosshair offsets
- **ShiftOffsetsBy** - Shift offsets by delta
- **ResetOffsets** - Reset offsets to zero
- **SaveOffsets** - Save offsets to persistent storage

### LRF Commands

**File:** `proto/jon_shared_cmd_lrf.proto`

**Package:** `cmd.Lrf`

#### Messages

- **Root** - LRF command root message
- **GetMeteo** - Get meteorological data
- **Start** - Start LRF
- **Stop** - Stop LRF
- **Measure** - Trigger single measurement
- **ScanOn** - Enable scan mode
- **ScanOff** - Disable scan mode
- **RefineOff** - Disable refinement
- **RefineOn** - Enable refinement
- **TargetDesignatorOff** - Turn off target designator
- **TargetDesignatorOnModeA** - Turn on target designator mode A
- **TargetDesignatorOnModeB** - Turn on target designator mode B
- **EnableFogMode** - Enable fog mode
- **DisableFogMode** - Disable fog mode
- **SetScanMode** - Set scan mode
- **NewSession** - Start new measurement session

### OSD Commands

**File:** `proto/jon_shared_cmd_osd.proto`

**Package:** `cmd.OSD`

#### Messages

- **Root** - OSD command root message
- **ShowDefaultScreen** - Show default screen
- **ShowLRFMeasureScreen** - Show LRF measure screen
- **ShowLRFResultScreen** - Show LRF result screen
- **ShowLRFResultSimplifiedScreen** - Show simplified LRF result screen
- **EnableHeatOSD** - Enable heat camera OSD
- **DisableHeatOSD** - Disable heat camera OSD
- **EnableDayOSD** - Enable day camera OSD
- **DisableDayOSD** - Disable day camera OSD

### Power Commands

**File:** `proto/jon_shared_cmd_power.proto`

**Package:** `cmd.Power`

#### Messages

- **Root** - Power module control commands root
- **SetChannel** - Set power state for a single channel
- **SetAll** - Set power state for all channels
- **SetAlertThreshold** - Set overcurrent alert threshold for a channel

### Rotary Platform Commands

**File:** `proto/jon_shared_cmd_rotary.proto`

**Package:** `cmd.RotaryPlatform`

#### Messages

- **Root** - Rotary platform command root message
- **Axis** - Combined azimuth and elevation control
- **SetMode** - Set rotary mode
- **SetAzimuthValue** - Set azimuth to specific value
- **RotateAzimuthTo** - Rotate azimuth to target value
- **RotateAzimuth** - Continuous azimuth rotation
- **RotateElevation** - Continuous elevation rotation
- **SetElevationValue** - Set elevation to specific value
- **RotateElevationTo** - Rotate elevation to target value
- **RotateElevationRelative** - Relative elevation rotation
- **RotateElevationRelativeSet** - Set relative elevation
- **RotateAzimuthRelative** - Relative azimuth rotation
- **RotateAzimuthRelativeSet** - Set relative azimuth
- **SetPlatformAzimuth** - Set platform azimuth offset
- **SetPlatformElevation** - Set platform elevation offset
- **SetPlatformBank** - Set platform bank angle
- **GetMeteo** - Get meteorological data
- **Azimuth** - Azimuth control commands
- **Start** - Start rotary platform
- **Stop** - Stop rotary platform
- **Halt** - Halt all movement
- **ScanStart** - Start scan pattern
- **ScanStop** - Stop scan pattern
- **ScanPause** - Pause scan pattern
- **ScanUnpause** - Resume scan pattern
- **HaltAzimuth** - Halt azimuth movement
- **HaltElevation** - Halt elevation movement
- **ScanPrev** - Previous scan node
- **ScanNext** - Next scan node
- **ScanRefreshNodeList** - Refresh scan node list
- **ScanSelectNode** - Select scan node by index
- **ScanDeleteNode** - Delete scan node
- **ScanUpdateNode** - Update scan node parameters
- **ScanAddNode** - Add new scan node
- **Elevation** - Elevation control commands
- **setUseRotaryAsCompass** - Use rotary position as compass
- **RotateToGPS** - Rotate to GPS coordinates
- **SetOriginGPS** - Set GPS origin for relative positioning
- **RotateToNDC** - Rotate to normalized device coordinates
- **HaltWithNDC** - Halt with NDC coordinates

### System Commands

**File:** `proto/jon_shared_cmd_system.proto`

**Package:** `cmd.System`

#### Messages

- **Root** - System command root message
- **StartALl** - Start all subsystems
- **StopALl** - Stop all subsystems
- **Reboot** - Reboot system
- **PowerOff** - Power off system
- **ResetConfigs** - Reset all configurations
- **SaveFactoryDefaults** - Save current settings as factory defaults
- **WipeUserData** - Wipe all user data
- **StartRec** - Start recording
- **StopRec** - Stop recording
- **MarkRecImportant** - Mark recording as important
- **UnmarkRecImportant** - Unmark recording as important
- **EnterTransport** - Enter transport mode
- **EnableGeodesicMode** - Enable geodesic mode
- **DisableGeodesicMode** - Disable geodesic mode
- **SetLocalization** - Set system localization
- **StepYear** - Step year by offset
- **StepMonth** - Step month by offset
- **StepDay** - Step day by offset
- **StepHour** - Step hour by offset
- **StepMinute** - Step minute by offset
- **StepSecond** - Step second by offset
- **EnableManualTime** - Enable manual time setting
- **DisableManualTime** - Disable manual time setting
- **SetTimeZone** - Set timezone by ID
- **StepTimeZone** - Step timezone by offset
- **SetTimeAndZone** - Set both time and timezone

### Command Root

**File:** `proto/jon_shared_cmd.proto`

**Package:** `cmd`

#### Messages

- **Root** - Top-level command message containing all subsystem commands
- **Ping** - Ping command
- **Noop** - No operation command
- **Frozen** - Frozen state command

---

## Data Messages

### Actual Space Time Data

**File:** `proto/jon_shared_data_actual_space_time.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataActualSpaceTime** - Actual platform position and orientation in space

### Camera Day Data

**File:** `proto/jon_shared_data_camera_day.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataCameraDay** - Day camera state including zoom, focus, iris, filters, and FX mode

### Camera Heat Data

**File:** `proto/jon_shared_data_camera_heat.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataCameraHeat** - Heat camera state including zoom, AGC, filters, DDE, and FX mode

### Compass Calibration Data

**File:** `proto/jon_shared_data_compass_calibration.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataCompassCalibration** - Compass calibration progress and target orientation

### Compass Data

**File:** `proto/jon_shared_data_compass.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataCompass** - Compass orientation, offsets, and calibration state

### Day Camera Glass Heater Data

**File:** `proto/jon_shared_data_day_cam_glass_heater.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataDayCamGlassHeater** - Day camera glass heater temperature and status

### GPS Data

**File:** `proto/jon_shared_data_gps.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataGps** - GPS coordinates, altitude, fix type, and manual position

### LRF Data

**File:** `proto/jon_shared_data_lrf.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataLrf** - Laser rangefinder state including scanning, measuring, and targeting modes
- **JonGuiDataTarget** - Target measurement data with observer and target coordinates
- **RgbColor** - RGB color specification

### Power Data

**File:** `proto/jon_shared_data_power.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataPowerModule** - Power state for a single channel (voltage, current, power, alarms)
- **JonGuiDataPower** - Power state for all 8 channels (S0-S7)

### Recording OSD Data

**File:** `proto/jon_shared_data_rec_osd.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataRecOsd** - Recording OSD screen state and crosshair offsets

### Rotary Data

**File:** `proto/jon_shared_data_rotary.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataRotary** - Rotary platform state including azimuth, elevation, mode, and scan info
- **ScanNode** - Scan pattern node with position and timing parameters

### System Data

**File:** `proto/jon_shared_data_system.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataSystem** - System state including temperatures, load, recording status, and CV modes

### Time Data

**File:** `proto/jon_shared_data_time.proto`

**Package:** `ser`

#### Messages

- **JonGuiDataTime** - System time, manual time, timezone, and time source mode

### Data Root

**File:** `proto/jon_shared_data.proto`

**Package:** `ser`

#### Messages

- **JonGUIState** - Root state message containing all subsystem states with frame timing

---

## Type Definitions

**File:** `proto/jon_shared_data_types.proto`

**Package:** `ser`

### Enums

- **JonGuiDataVideoChannelHeatFilters** - Thermal camera filter modes (hot white, hot black, sepia, etc.)
- **JonGuiDataVideoChannelHeatAGCModes** - Thermal camera AGC modes
- **JonGuiDataGpsUnits** - GPS coordinate display units
- **JonGuiDataGpsFixType** - GPS fix quality types
- **JonGuiDataCompassUnits** - Compass angle display units
- **JonGuiDataAccumulatorStateIdx** - Battery charge level states
- **JonGuiDataTimeFormats** - Time display format options
- **JonGuiDataRotaryDirection** - Rotary rotation direction
- **JonGuiDataLrfScanModes** - LRF continuous scan frequencies
- **JonGuiDatatLrfLaserPointerModes** - Laser pointer modes
- **JonGuiDataCompassCalibrateStatus** - Compass calibration status
- **JonGuiDataRotaryMode** - Rotary platform control modes
- **JonGuiDataVideoChannel** - Video channel identifier (day/heat)
- **JonGuiDataRecOsdScreen** - Recording OSD screen types
- **JonGuiDataFxModeDay** - Day camera FX modes
- **JonGuiDataFxModeHeat** - Heat camera FX modes
- **JonGuiDataSystemLocalizations** - System language options
- **JonGuiDataClientType** - Client connection type
- **JonGuiDataClientApp** - Client application type
- **JonGuiDataExtBatStatus** - External battery status
- **JonGuiDataStateSource** - State message source pipeline

### Messages

- **JonGuiDataMeteo** - Meteorological data (temperature, humidity, pressure)
- **JonOpaquePayloadVersion** - Version information for opaque payloads
- **JonOpaquePayload** - Opaque extension payload for subsystem-specific data

---

## Archive

**File:** `proto/jon_sych_archive.proto`

**Package:** `jon.archive`

### Messages

- **SychArchiveIndex** - Archive index (last entry in .sych_video tar file)
- **ArchiveEntry** - File entry in archive with byte offsets for seeking
- **TimelineIndex** - Timeline containing video entries with metadata
- **VideoEntry** - Video entry with embedded metadata and archive paths
- **OSDReference** - Reference to OSD package and config within archive

---

## Video Metadata

**File:** `proto/jon_video_meta.proto`

**Package:** `jon.video`

### Messages

- **VideoMetaRequest** - Request message for video metadata retrieval
- **VideoIdList** - List of video UUIDs for explicit selection
- **VideoRangeQuery** - Time-based range query for selecting videos
- **VideoMetaResponse** - Response containing video metadata and errors
- **VideoMeta** - Metadata for a single video including MOOV data
- **SampleTable** - MP4 sample table data extracted from MOOV
- **SampleToChunk** - Sample-to-chunk box entry
- **VideoError** - Error information for failed video processing

### Enums

- **VideoErrorType** - Types of errors during video metadata extraction

---

## Test Messages

**File:** `proto/test/person.proto`

**Package:** `test.person`

### Messages

- **Person** - Test message with email, age, and ID fields

---

## Summary Statistics

- **Total Proto Files:** 32
- **Total Messages:** 234+
- **Total Enums:** 21
- **Packages:** 18

## File Organization

```
proto/
├── jon_client_logs.proto                       # Client logging
├── jon_shared_cmd_*.proto                      # Command messages (13 files)
├── jon_shared_data_*.proto                     # State data messages (11 files)
├── jon_sych_archive.proto                      # Archive format
├── jon_video_meta.proto                        # Video metadata
└── test/
    └── person.proto                            # Test messages
```

## Related Documentation

- See `README.md` for build instructions and code generation details
- See `CLAUDE.md` for development workflow and architecture overview
- Generated code is in `output/` directory organized by language (Go, TypeScript, Python)
