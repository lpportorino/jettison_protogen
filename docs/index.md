---
title: Proto Documentation Index
type: index
---

# Proto Documentation

**Statistics:** 345 messages, 68 enums, 1133 fields

## Messages by Package


### cmd

- [[proto/cmd.Frozen|Frozen]] — A diagnostic command message used for debug/test purposes to trigger or test the frozen state of the system. This parameterless command is allowed in readonly mode and is sent without buffering alongside ping messages for system testing.
- [[proto/cmd.Noop|Noop]] — A no-operation command used as a placeholder in the command protocol payload; allows clients to send a valid command message without triggering any action on the device or system.
- [[proto/cmd.Ping|Ping]] — A lightweight keepalive command that allows clients to update their session heartbeat timestamp, enabling the server to detect disconnected sessions and automatically halt ongoing operations like camera movements or scanning.
- [[proto/cmd.Root|Root]] — Top-level command message that routes client commands to various subsystems (day camera, thermal camera, GPS, compass, LRF, rotary platform, OSD, system, CV, LIRA, power, PMU, heater) with protocol versioning, session tracking, timestamps, and validation support.


### cmd.CV

- [[proto/cmd.CV.BridgeRestart|BridgeRestart]] — Restarts the CV Bridge Docker container.

Performs a stop followed by start of the CV Bridge container. The bridge_status will transition through STOPPING → STOPPED → STARTING → RUNNING. The restart_count will be incremented.

Use this command to recover from errors or apply configuration changes that require a full restart.
- [[proto/cmd.CV.BridgeStart|BridgeStart]] — Starts the CV Bridge Docker container.

The CV Bridge is an isolated Docker container running the CV Gateway application that:
- Consumes CUDA IPC frames from pipeline_day and pipeline_heat
- Computes autofocus sharpness metrics using CUDA kernels
- Controls camera lens focus via CAN bus
- Reports status back to fanout for state enrichment

If the container is already running, this command has no effect. The bridge_status field in JonGuiDataCV will transition from STOPPED to STARTING, then to RUNNING once initialized.
- [[proto/cmd.CV.BridgeStop|BridgeStop]] — Stops the CV Bridge Docker container.

Gracefully shuts down the CV Bridge container. The bridge_status field will transition to STOPPING, then to STOPPED once the container exits. The last_exit_reason will be set to NORMAL.

When the CV Bridge is stopped, fanout operates in bypass mode - state continues to flow but without CV enrichment (autofocus metrics will be stale/default).
- [[proto/cmd.CV.DumpStart|DumpStart]] — Initiates recording of computer vision frame data to disk for debugging and analysis purposes. Only available in factory mode (URL parameter ui=factory). The state is tracked via data.System.cvDumping boolean field.
- [[proto/cmd.CV.DumpStop|DumpStop]] — Stops the computer vision frame dumping process that was previously initiated with DumpStart, ceasing the export of CV data to disk. Sets the cvDumping state to false when processed.
- [[proto/cmd.CV.RecognitionModeDisable|RecognitionModeDisable]] — Disables the AI-powered computer vision recognition mode, stopping automatic object detection and classification in the video feed. Paired with [[proto/cmd.CV.RecognitionModeEnable]]; the two back a single toggle rather than two independent buttons.

The readback is `recognition_mode` (#23) on [[proto/ser.JonGuiDataSystem]], which goes `false` once this command is applied. That is the only recognition flag in the schema — [[proto/ser.JonGuiDataCV]] carries none, so a consumer reflecting this toggle reads the SYSTEM state message and not the CV one.
- [[proto/cmd.CV.RecognitionModeEnable|RecognitionModeEnable]] — Enables AI-powered object recognition mode on the computer vision system, which activates detection and classification of objects in the video feed. UI displays a bracket icon with question mark and tooltip "Enable AI object recognition and tracking".
- [[proto/cmd.CV.Root|Root]] — Container message for computer vision commands that provides object tracking, autofocus control, and various CV processing modes (vampire, stabilization, recognition) through a mutually-exclusive oneof command dispatch pattern.
- [[proto/cmd.CV.SetAutoFocus|SetAutoFocus]] — Enables or disables computer vision-based automatic focus for either the day or thermal camera channel, routing the command through the CV pipeline for software-controlled focus management. Different from cmd.HeatCamera.SetAutoFocus which is a direct hardware command.
- [[proto/cmd.CV.StabilizationModeDisable|StabilizationModeDisable]] — Disables the computer vision image stabilization mode, allowing the camera to respond freely to manual movement instead of compensating for shake and vibration. Tooltip: "Disable Image Stabilization - allows manual camera movement".
- [[proto/cmd.CV.StabilizationModeEnable|StabilizationModeEnable]] — Enables computer vision-based image stabilization to reduce camera shake and vibration in the video feed. The system applies real-time stabilization algorithms to compensate for camera movement, providing steadier video output.
- [[proto/cmd.CV.StartTrackNDC|StartTrackNDC]] — Initiates object tracking at a specific point using normalized device coordinates (NDC), where the user clicks on a video feed to begin tracking an object at that location. Includes frame and state timestamps for synchronization between frontend and backend video processing pipelines.
- [[proto/cmd.CV.StartTrackTrinity|StartTrackTrinity]] — Begin tracking the Ring-Trinity golden fiducial board.

Unlike [[proto/cmd.CV.StartTrackNDC]] there is **no seed point**, and that is the board's whole
purpose: it is self-locating from its own geometry, so the operator does not have to put a cursor
on it. There is exactly one board in a run, so nothing needs disambiguating.

`expect_board` is optional. Unset means "track whatever Ring-Trinity board you find". When set, a
mismatch is reported as `TRINITY_TRACKING_STATUS_BOARD_MISMATCH` instead of yielding a pose
computed against different geometry — which would be wrong by a scale factor and look entirely
plausible.
- [[proto/cmd.CV.StopTrack|StopTrack]] — Stops active video tracking on both day and thermal cameras. When sent from the frontend, it is forwarded to both pipeline command channels to terminate automatic target following. The tracking button (with corner brackets icon) only appears when system.tracking is true.
- [[proto/cmd.CV.StopTrackTrinity|StopTrackTrinity]] — Stop tracking the Ring-Trinity board.

Symmetric with [[proto/cmd.CV.StopTrack]] for NDC tracking; takes no fields.
- [[proto/cmd.CV.VampireModeDisable|VampireModeDisable]] — Disables vampire mode (sun avoidance) in the computer vision system, allowing cameras to look directly at bright light sources like the sun without automatic avoidance behavior. Paired with [[proto/cmd.CV.VampireModeEnable]]; the two back a single toggle rather than two independent buttons.

The readback is `vampire_mode` (#19) on [[proto/ser.JonGuiDataSystem]], which goes `false` once this command is applied. That is the only vampire-mode flag in the schema — [[proto/ser.JonGuiDataCV]] carries none, so a consumer reflecting this toggle reads the SYSTEM state message and not the CV one.
- [[proto/cmd.CV.VampireModeEnable|VampireModeEnable]] — Enables vampire mode for the computer vision system, which causes the cameras to actively avoid looking at the sun to protect sensors and prevent image overexposure. When enabled, the system prevents cameras from pointing at bright light sources.


### cmd.Compass

- [[proto/cmd.Compass.CalibrateCencel|CalibrateCencel]] — Cancels an ongoing compass calibration process, returning the compass to normal operation mode. This command terminates a calibration session initiated by CalibrateStartLong or CalibrateStartShort, typically used when the user wants to abort calibration before completion.
- [[proto/cmd.Compass.CalibrateNext|CalibrateNext]] — Advances to the next step in an ongoing compass calibration sequence, signaling that the device has been positioned correctly for the current calibration stage. The backend automatically sends this command when using rotary platform positioning after the platform reaches the target position (within 5 degrees tolerance) and holds steady.
- [[proto/cmd.Compass.CalibrateStartLong|CalibrateStartLong]] — Initiates the long (comprehensive) compass calibration procedure, which guides the user through multiple stages of rotating the device to different orientations to correct for local magnetic field distortions. This is a multi-stage process (12-point) that compensates for hard-iron and soft-iron distortions.
- [[proto/cmd.Compass.CalibrateStartShort|CalibrateStartShort]] — Initiates a short (4-point) compass calibration procedure that requires the device to be positioned at 4 cardinal points instead of the full multi-point long calibration. The backend sends COMPASS_CALIBRATION_4POINT to the compass device for this faster but less precise calibration mode.
- [[proto/cmd.Compass.GetMeteo|GetMeteo]] — Requests meteorological sensor data (temperature, humidity, pressure) from the compass module's environmental sensors. This command is periodically requested by a system timer (every 600ms) rather than being triggered by user interaction, allowing continuous monitoring of environmental conditions.
- [[proto/cmd.Compass.Next|Next]] — **UNUSED/ORPHANED MESSAGE**: This message is defined in jon_shared_cmd_compass.proto but is NOT wired into the cmd.Compass.Root oneof structure. The actual command used to advance compass calibration stages is cmd.Compass.CalibrateNext. This message can likely be removed from the proto file as dead code.
- [[proto/cmd.Compass.Root|Root]] — Root command container that wraps all compass/magnetometer commands using a oneof pattern, enabling control of compass power state, calibration processes, and configuration settings. Commands are routed through the cmd_server to the compass module which communicates with the hardware via UART bridge.
- [[proto/cmd.Compass.SetMagneticDeclination|SetMagneticDeclination]] — Sets the magnetic declination correction value for the compass to convert magnetic north readings to true north. Magnetic declination is the angle between magnetic north (as read by the compass) and true north, which varies by geographic location and changes over time.
- [[proto/cmd.Compass.SetOffsetAngleAzimuth|SetOffsetAngleAzimuth]] — Sets the compass azimuth angle offset calibration value to correct for mounting or measurement errors in the horizontal axis. This allows manual adjustment of the compass azimuth reading by applying a fixed offset to compensate for mounting misalignment or sensor drift.
- [[proto/cmd.Compass.SetOffsetAngleElevation|SetOffsetAngleElevation]] — Sets the compass elevation angle offset calibration value to correct for mounting or measurement errors in the vertical axis. This allows manual adjustment of the compass elevation reading by applying a fixed offset to compensate for non-level mounting or local geomagnetic anomalies.
- [[proto/cmd.Compass.SetUseRotaryPosition|SetUseRotaryPosition]] — Configures whether to use the rotary platform's encoded position as the primary compass/orientation source instead of the physical compass sensor. When enabled, the system derives azimuth readings from the rotary platform's positional encoders rather than the magnetometer.
- [[proto/cmd.Compass.Start|Start]] — Initializes and powers on the compass/IMU sensor subsystem, transitioning it from stopped to started state and enabling azimuth, elevation, and bank angle readings. Sets device_status to STARTED in the manifold global state.
- [[proto/cmd.Compass.Stop|Stop]] — Stops the compass/IMU sensor subsystem and powers down the device, preventing heading and orientation readings until restarted. Stopping the compass also prevents calibration operations from being initiated.


### cmd.DayCamera

- [[proto/cmd.DayCamera.Focus|Focus]] — Composite command for controlling day camera focus operations, supporting direct value setting, continuous movement, halting, offset adjustment, reset to table value, and saving current position to the focus table. Uses a required oneof with six sub-commands.
- [[proto/cmd.DayCamera.FocusROI|FocusROI]] — Triggers auto-focus on a user-defined region of interest (ROI) in the day camera feed. The user draws a rectangle on the video display (or taps a point), and the camera adjusts focus to optimize sharpness within that region. Uses NDC coordinates (-1 to 1 range).
- [[proto/cmd.DayCamera.FocusStepMinus|FocusStepMinus]]
- [[proto/cmd.DayCamera.FocusStepPlus|FocusStepPlus]]
- [[proto/cmd.DayCamera.FxROI|FxROI]] — Specifies a region of interest for the day camera's AGC/exposure optimization and post-processing effects. The system converts NDC coordinates to pixels and configures the sensor's auto-exposure metering region (fixed 272x272 pixel ROI centered on selection).
- [[proto/cmd.DayCamera.GetMeteo|GetMeteo]] — Polling command that requests meteorological sensor data (temperature, humidity, pressure) from the day camera module. This is a parameterless fire-and-forget command that triggers an asynchronous response via state updates to JonGuiDataMeteo.
- [[proto/cmd.DayCamera.GetPos|GetPos]] — Requests the current zoom and focus position values from the day camera, triggering a state update with the latest position data. Useful for synchronizing UI state or debugging position discrepancies. Response updates focus_pos, zoom_pos, iris_pos in JonGuiDataCameraDay.
- [[proto/cmd.DayCamera.Halt|Halt]] — Immediately stops zoom or focus motor movement on the day camera. Used as a sub-command within Focus and Zoom composite commands to halt individual lens actuator movement. Part of the emergency stop control pattern for lens operations.
- [[proto/cmd.DayCamera.HaltAll|HaltAll]] — Emergency stop command that immediately halts all day camera actuator movements (both zoom and focus motors). This parameterless command provides a safety mechanism to stop any ongoing lens movement operations instantly.
- [[proto/cmd.DayCamera.Move|Move]] — Moves day camera lens (zoom or focus) to a target position at a specified speed. Both target_value and speed are normalized (0-1 range). Used within Focus and Zoom composite commands for smooth, controlled lens movement with variable speed control.
- [[proto/cmd.DayCamera.NextFxMode|NextFxMode]] — Cycles to the next FX (visual effects) mode for the day camera. FX modes include image enhancement filters like CLAHE, edge detection, and color adjustments. This parameterless command advances through the available modes list, wrapping around at the end.
- [[proto/cmd.DayCamera.NextZoomTablePos|NextZoomTablePos]] — Advances the day camera to the next predefined optical zoom position in the zoom table. The zoom table contains preset magnification levels (e.g., 1x, 2x, 4x, 10x) allowing quick jumps between commonly-used zoom levels without continuous adjustment.
- [[proto/cmd.DayCamera.Offset|Offset]] — Adjusts day camera focus or zoom position by a relative offset amount. The offset_value is normalized (-1 to 1 range) where negative values move toward minimum and positive toward maximum. Used within Focus and Zoom composite commands for incremental adjustments.
- [[proto/cmd.DayCamera.Photo|Photo]] — Triggers a photo capture from the day camera. This parameterless command captures a still image from the current video feed. The UI button shows pending state until capture completes, which is indicated by a change in the LRF target ID.
- [[proto/cmd.DayCamera.PrevFxMode|PrevFxMode]] — Cycles to the previous visual effect (FX) mode for the day camera. Counterpart to NextFxMode, this command navigates backward through the available FX modes list. Used in keyboard shortcuts and FX mode selector buttons for bidirectional mode navigation.
- [[proto/cmd.DayCamera.PrevZoomTablePos|PrevZoomTablePos]] — Decrements the day camera optical zoom to the previous position in the zoom table. Counterpart to NextZoomTablePos, this parameterless command steps backward through predefined zoom levels. Commonly triggered via hotkey commands and mouse wheel interactions for quick zoom-out operations.
- [[proto/cmd.DayCamera.RefreshFxMode|RefreshFxMode]] — Trigger command that requests the day camera to re-apply its current FX mode settings. This parameterless command is useful for refreshing visual effects after parameter changes or to ensure the current mode is properly active without cycling to a different mode.
- [[proto/cmd.DayCamera.ResetFocus|ResetFocus]] — Resets the day camera's focus to its default or home position. This parameterless trigger command provides a one-click way to return focus to a known baseline state, exposed as a Reset action button in the UI with pending-timeout feedback.
- [[proto/cmd.DayCamera.ResetZoom|ResetZoom]] — Resets the day camera's optical zoom to its default position (typically 1x or minimum zoom). This fire-and-forget command is triggered via an action button in the UI zoom control panel and requires the day camera to be started before execution.
- [[proto/cmd.DayCamera.Root|Root]] — Root container message for all day camera control commands using a required oneof pattern. Routes different camera operations (focus, zoom, iris, FX modes, ROI tracking, etc.) to their respective handlers. The frontend constructs and sends these command messages to the backend, where cmd_hooks_day_camera.c dispatches them to appropriate device handlers.
- [[proto/cmd.DayCamera.SaveToTable|SaveToTable]] — Saves the current day camera optical zoom position to a zoom lookup table for later recall. This parameterless command is triggered via a fire-and-forget action button in the UI, enabling users to quickly restore frequently-used zoom positions during camera operation.
- [[proto/cmd.DayCamera.SaveToTableFocus|SaveToTableFocus]] — Saves the current camera focus position to a lookup table for quick recall. This parameterless trigger command enables the camera to store and later retrieve predefined focus positions, commonly used via a save-focus action button in the day camera controls.
- [[proto/cmd.DayCamera.SetAutoGain|SetAutoGain]] — Enables or disables automatic gain control (AGC) for the day camera. When enabled, the camera automatically adjusts hardware gain to optimize brightness based on scene lighting. Exposed in the UI as a toggle control with fire-and-forget feedback.
- [[proto/cmd.DayCamera.SetAutoIris|SetAutoIris]] — Enables or disables automatic iris control for the day camera, allowing automatic aperture adjustment based on lighting conditions. When enabled, manual iris control via SetIris is disabled; when disabled, the operator can manually adjust the iris for precise exposure control.
- [[proto/cmd.DayCamera.SetClaheLevel|SetClaheLevel]] — Sets the CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level for the day camera to improve image contrast and visibility. Accepts a normalized value (0-1, displayed as 0-100%) with presets at 0%, 25%, 50%, 75%, and 100%, controlled through UI sliders with pending-timeout feedback.
- [[proto/cmd.DayCamera.SetDigitalZoomLevel|SetDigitalZoomLevel]] — Controls the digital zoom magnification level for the day camera. Accepts a value representing zoom magnification (1x or higher) and operates as a fire-and-forget command with slider UI. Can be synchronized with heat camera digital zoom via a UI sync toggle for coordinated dual-camera operation.
- [[proto/cmd.DayCamera.SetFxMode|SetFxMode]] — Sets the day camera's image processing FX mode to a specific preset (DAY_A for daytime, DAY_B for dusk, or DAY_C for fog conditions). Each mode applies predefined color and exposure settings optimized for different environmental lighting conditions to enhance video quality.
- [[proto/cmd.DayCamera.SetInfraRedFilter|SetInfraRedFilter]] — Enables or disables the infrared filter on the day camera to block IR light for better color reproduction in visible light conditions. This fire-and-forget toggle command switches the physical IR-cut filter state with a boolean flag.
- [[proto/cmd.DayCamera.SetIris|SetIris]] — Controls the camera iris (aperture) opening to adjust light intake and depth of field. The normalized value (0-1) represents aperture opening percentage, with UI presets at 0%, 3%, 5%, 7%, 10%, 15%, 20%, 30%, 50%, 75%, 100%, and Auto mode.
- [[proto/cmd.DayCamera.SetValue|SetValue]] — Generic value setter for day camera parameters, accepting a normalized value between 0.0 and 1.0. Used within Focus and Zoom composite commands for direct absolute positioning of camera actuators with slider-based UI patterns and fire-and-forget feedback.
- [[proto/cmd.DayCamera.SetZoomTableValue|SetZoomTableValue]] — Sets the day camera to a specific zoom table position by index value. This command allows direct selection of predefined optical zoom levels in the camera's zoom table, providing quick access to commonly-used magnification presets.
- [[proto/cmd.DayCamera.ShiftClaheLevel|ShiftClaheLevel]] — Incremental adjustment of the CLAHE (Contrast Limited Adaptive Histogram Equalization) level for the day camera. Applied as a relative shift value between -1 and 1, typically used with keyboard shortcuts that shift by ±0.01 increments, with the result clamped to the valid [0, 1] range.
- [[proto/cmd.DayCamera.Start|Start]] — Starts the day camera module, initiating video streaming and enabling camera controls. This parameterless lifecycle command powers on the camera hardware and begins video capture, typically triggered via a toggle button in the UI with pending-timeout feedback.
- [[proto/cmd.DayCamera.Stop|Stop]] — Stops day camera operation and releases associated hardware resources. This parameterless lifecycle command terminates the active day camera stream that was started with the Start command, returning the camera to an inactive state.
- [[proto/cmd.DayCamera.TrackROI|TrackROI]] — Initiates continuous video tracking on a specified rectangular region of interest (ROI) in the day camera feed. Uses normalized device coordinates (NDC, -1 to 1 range) with frame and system timestamps for synchronization with the camera stream.
- [[proto/cmd.DayCamera.Zoom|Zoom]] — Composite command for controlling day camera optical zoom through multiple methods: absolute value setting, continuous movement with speed control, halt, table-based positioning, offset adjustment, reset to default, and saving current position. Uses a required oneof with nine sub-commands for flexible zoom control.
- [[proto/cmd.DayCamera.ZoomROI|ZoomROI]] — Zooms the day camera to focus on a region of interest (ROI) marked by the user on the video display. Accepts normalized device coordinates (NDC, -1 to 1 range) with frame and state timestamps for accurate synchronization with the video stream.
- [[proto/cmd.DayCamera.ZoomStepMinus|ZoomStepMinus]]
- [[proto/cmd.DayCamera.ZoomStepPlus|ZoomStepPlus]]


### cmd.Gps

- [[proto/cmd.Gps.GetMeteo|GetMeteo]] — Requests meteorological and diagnostic data from the GPS module. This parameterless fire-and-forget command triggers the GPS system to return health metrics and environmental sensor readings via state updates.
- [[proto/cmd.Gps.Root|Root]] — Root command container for GPS module operations using a required oneof pattern. Dispatches between five command types: start, stop, set manual position, toggle manual position mode, and get meteorological data for GPS lifecycle and configuration management.
- [[proto/cmd.Gps.SetManualPosition|SetManualPosition]] — Sets a manual GPS position override with specified latitude, longitude, and altitude coordinates. This position is used when manual position mode is enabled via SetUseManualPosition, allowing the system to operate with a fixed location when GPS signal is unavailable or for testing purposes.
- [[proto/cmd.Gps.SetUseManualPosition|SetUseManualPosition]] — Toggles between GPS-based and manual position entry modes. When the flag is true, the system uses the manually configured position (set via SetManualPosition); when false, it uses live GPS coordinates from the receiver.
- [[proto/cmd.Gps.Start|Start]] — Starts the GPS module and begins receiving position data. This parameterless lifecycle command initializes the GPS hardware and triggers data collection, typically activated via a power toggle button in the UI.
- [[proto/cmd.Gps.Stop|Stop]] — Stops the GPS receiver hardware and ceases position data collection. This parameterless lifecycle command shuts down the GPS module, typically triggered via a power toggle button in the UI with fire-and-forget feedback.


### cmd.HeatCamera

- [[proto/cmd.HeatCamera.Calibrate|Calibrate]] — Triggers a Non-Uniformity Correction (NUC) calibration cycle on the thermal camera to improve image accuracy. This parameterless fire-and-forget command adjusts the sensor to compensate for pixel-to-pixel variations and is typically invoked via a UI calibration button.
- [[proto/cmd.HeatCamera.DisableDDE|DisableDDE]] — Disables Digital Detail Enhancement (DDE) on the thermal camera. This parameterless toggle command turns off the image processing that enhances edge detail and fine features, returning to standard thermal image output.
- [[proto/cmd.HeatCamera.EnableDDE|EnableDDE]] — Enables Digital Detail Enhancement (DDE) on the thermal camera to enhance image detail visibility. This parameterless command activates additional image processing that sharpens edges and improves fine feature visibility in the thermal image.
- [[proto/cmd.HeatCamera.FocusIn|FocusIn]] — Commands the thermal camera to continuously focus toward near distances while held. This parameterless trigger is used with button press/release or gamepad input as part of the focus control system alongside FocusOut and FocusStop commands.
- [[proto/cmd.HeatCamera.FocusOut|FocusOut]] — Commands the thermal camera to continuously move focus farther away (toward infinity) while held. This parameterless trigger uses a press-accelerating UI pattern for continuous focus adjustment until released or FocusStop is called.
- [[proto/cmd.HeatCamera.FocusROI|FocusROI]] — Focuses the thermal camera on a user-selected rectangular region of interest (ROI). The ROI is defined by normalized coordinates (x1,y1) to (x2,y2) in the -1 to 1 range, with frame and state timestamps for synchronization with the video stream.
- [[proto/cmd.HeatCamera.FocusStepMinus|FocusStepMinus]] — Decrements the thermal camera focus by one discrete step, moving the focus point farther away. This parameterless fire-and-forget command is used in the UI focus control panel for precise single-step focus adjustments.
- [[proto/cmd.HeatCamera.FocusStepPlus|FocusStepPlus]] — Increments the thermal camera focus by one discrete step, bringing the focus point closer to the camera. This parameterless fire-and-forget stepper command provides single-step manual focus adjustment in the UI focus control panel.
- [[proto/cmd.HeatCamera.FocusStop|FocusStop]] — Stops continuous thermal camera focus movement initiated by FocusIn or FocusOut commands. This parameterless fire-and-forget command halts ongoing focus motor operation when the user releases the focus control button.
- [[proto/cmd.HeatCamera.FxROI|FxROI]] — Specifies a rectangular region of interest for thermal camera AGC/exposure optimization and post-processing effects. The region is defined by corner coordinates in normalized device coordinates (NDC, -1 to 1 range) with frame and state timestamps for synchronization.
- [[proto/cmd.HeatCamera.GetMeteo|GetMeteo]] — Requests meteorological and diagnostic data from the thermal camera module. This parameterless fire-and-forget diagnostic command triggers the camera to return sensor readings and health metrics via state updates.
- [[proto/cmd.HeatCamera.Halt|Halt]] — Emergency stop command that halts all thermal camera motor movements including both zoom and focus operations. This parameterless command provides an immediate safety mechanism to stop any ongoing actuator movement.
- [[proto/cmd.HeatCamera.NextFxMode|NextFxMode]] — Cycles to the next FX enhancement mode on the thermal camera, advancing through available image enhancement filters in sequence. This parameterless command wraps around to the first mode after reaching the last one.
- [[proto/cmd.HeatCamera.NextZoomTablePos|NextZoomTablePos]] — Moves the thermal camera to the next preset zoom position in the zoom lookup table. This parameterless trigger command is nested within the Zoom submessage and advances through predefined optical zoom levels for quick magnification changes.
- [[proto/cmd.HeatCamera.Photo|Photo]] — Triggers the thermal camera to capture a still photo from the current video feed. This parameterless command shows pending state in the UI button until capture completes, which is confirmed when the LRF target ID changes.
- [[proto/cmd.HeatCamera.PrevFxMode|PrevFxMode]] — Cycles to the previous FX (effects) mode on the thermal camera, navigating backward through available thermal imaging enhancement filters. Paired with NextFxMode and SetFxMode for complete FX mode navigation control.
- [[proto/cmd.HeatCamera.PrevZoomTablePos|PrevZoomTablePos]] — Moves the thermal camera to the previous position in the zoom lookup table, stepping backward through saved zoom presets. This parameterless command complements NextZoomTablePos for bidirectional zoom preset navigation.
- [[proto/cmd.HeatCamera.RefreshFxMode|RefreshFxMode]] — Triggers a refresh/reapplication of the current visual effects (FX) mode on the thermal camera. This parameterless fire-and-forget command reinitializes the current FX processing without changing modes, useful after parameter changes or to ensure proper mode activation.
- [[proto/cmd.HeatCamera.ResetZoom|ResetZoom]] — Resets the thermal camera optical zoom position to its default minimum value. This parameterless actuator command returns the zoom to 1x or minimum magnification, triggered via an action button with pending-timeout feedback.
- [[proto/cmd.HeatCamera.Root|Root]] — Root message container for all thermal camera commands using a required oneof pattern with 38 command variants. Includes zoom, focus, AGC, filters, calibration, DDE, CLAHE, and region-of-interest operations. The frontend constructs individual commands and wraps them in this Root message for dispatch.
- [[proto/cmd.HeatCamera.SaveToTable|SaveToTable]] — Saves the current thermal camera zoom position to a lookup table for quick recall. This parameterless fire-and-forget trigger allows users to store frequently-used zoom positions for later retrieval via zoom table navigation commands.
- [[proto/cmd.HeatCamera.SetAGC|SetAGC]] — Configures the Automatic Gain Control (AGC) mode for the thermal camera to optimize image enhancement for different viewing conditions. Accepts an enumerated value (MODE_1, MODE_2, or MODE_3) that adjusts how the camera processes thermal intensity for display.
- [[proto/cmd.HeatCamera.SetAutoFocus|SetAutoFocus]] — Enables or disables automatic focus for the thermal camera. When enabled (value=true), the camera automatically adjusts focus based on the scene; when disabled, manual focus controls (FocusIn, FocusOut, step commands) become active.
- [[proto/cmd.HeatCamera.SetCalibMode|SetCalibMode]] — Sets the calibration mode for the thermal camera, controlling how the sensor performs Non-Uniformity Correction (NUC). This parameterless command configures calibration behavior as part of thermal imaging parameter management.
- [[proto/cmd.HeatCamera.SetClaheLevel|SetClaheLevel]] — Sets the CLAHE (Contrast Limited Adaptive Histogram Equalization) level for the thermal camera to control image contrast enhancement. Accepts a normalized value (0-1, displayed as 0-100%) with preset options and 5% increment/decrement capability via slider UI.
- [[proto/cmd.HeatCamera.SetDDELevel|SetDDELevel]] — Sets the Digital Detail Enhancement (DDE) level for thermal image processing, controlling edge enhancement intensity. Accepts an integer value from 0 to 100 and is typically controlled via a slider UI with fire-and-forget feedback.
- [[proto/cmd.HeatCamera.SetDigitalZoomLevel|SetDigitalZoomLevel]] — Sets the digital zoom magnification level for the thermal camera to a specified value (minimum 1x). Uses a slider UI interface and can be synchronized with day camera digital zoom via a UI sync toggle for coordinated dual-camera operation.
- [[proto/cmd.HeatCamera.SetFilters|SetFilters]] — Sets the color filter mode for thermal camera display by accepting a JonGuiDataVideoChannelHeatFilters enum value. Cycles through available filter modes (HOT_BLACK, HOT_WHITE, SEPIA) to provide different color palettes for thermal image visualization.
- [[proto/cmd.HeatCamera.SetFxMode|SetFxMode]] — Sets the FX (image enhancement) mode for the thermal camera to a specific mode value. Accepts a JonGuiDataFxModeHeat enum value that controls the thermal image enhancement effects applied to the video stream for optimized viewing in different conditions.
- [[proto/cmd.HeatCamera.SetValue|SetValue]] — Generic command that sets a normalized thermal camera value (0-1 range) for zoom or other parameters. Defined in the HeatCamera command protocol as part of the Zoom submessage for absolute position control.
- [[proto/cmd.HeatCamera.SetZoomTableValue|SetZoomTableValue]] — Sets the thermal camera optical zoom to a specific discrete table position (typically 0-4). Used for zoom table navigation in POI scanning, hotkey-based zoom control, and synchronized zoom operations with the day camera.
- [[proto/cmd.HeatCamera.ShiftClaheLevel|ShiftClaheLevel]] — Incremental adjustment command for thermal camera CLAHE (Contrast Limited Adaptive Histogram Equalization) enhancement level. Accepts a normalized shift value between -1.0 and 1.0 to adjust the contrast enhancement by relative increments via keyboard shortcuts or steppers.
- [[proto/cmd.HeatCamera.ShiftDDE|ShiftDDE]] — Incremental adjustment command for thermal camera DDE (Digital Detail Enhancement) level. Accepts positive or negative shift values between -100 and 100, typically used with keyboard shortcuts that shift the DDE level by ±15 increments.
- [[proto/cmd.HeatCamera.Start|Start]] — Initiates startup of the thermal camera sensor and begins capturing thermal imaging data. This parameterless lifecycle command enables the heat camera subsystem, typically triggered via a toggle button in the UI with pending-timeout feedback.
- [[proto/cmd.HeatCamera.Stop|Stop]] — Stops the thermal camera subsystem and deactivates thermal imaging capture. This parameterless lifecycle command shuts down the heat camera and its processing pipeline, returning to an inactive state.
- [[proto/cmd.HeatCamera.TrackROI|TrackROI]] — Defines a normalized coordinate region for object tracking on the thermal camera. Sent when a user draws a rectangle on the thermal video feed, specifying the region of interest (ROI) to be tracked along with frame and state timestamps for synchronization.
- [[proto/cmd.HeatCamera.Zoom|Zoom]] — Controls the thermal camera's optical zoom by setting discrete zoom table positions. Supports setting a specific zoom value, moving to the next zoom table position, or moving to the previous zoom table position.
- [[proto/cmd.HeatCamera.ZoomIn|ZoomIn]] — Initiates continuous zoom-in motion on the thermal camera. This parameterless command starts increasing magnification and requires a ZoomStop command to halt the operation, using a press-accelerating UI pattern for smooth zoom control.
- [[proto/cmd.HeatCamera.ZoomOut|ZoomOut]] — Instructs the thermal camera to decrease its zoom level, typically triggered by gamepad button presses or UI controls to zoom out and view a wider field of view from the thermal imaging sensor.
- [[proto/cmd.HeatCamera.ZoomROI|ZoomROI]] — Zooms the thermal camera to a user-selected rectangular region of interest using normalized device coordinates (NDC), with frame synchronization via timestamps.
- [[proto/cmd.HeatCamera.ZoomStop|ZoomStop]] — Stops the thermal camera zoom motion in progress, sent when the zoom button is released after a zoom in or out command. Used in press-release input patterns for analog zoom control.


### cmd.Heater

- [[proto/cmd.Heater.AutomaticControlChannelParams|AutomaticControlChannelParams]] — Per-channel parameters for the heater automatic (PID) control loop. This sub-message is used by `SetAutomaticControlParams` to configure one of the three heating channels (channel 0 = day camera glass, channel 1 = LRF glass, channel 2 = heat camera glass). Currently contains only the target temperature setpoint; PID tuning gains (kp, ki, kd) are loaded separately from Redis via config_editor.
- [[proto/cmd.Heater.DisableAutomaticControl|DisableAutomaticControl]] — Disables the PID-based automatic temperature regulation loop for all heater channels. When received, the heater module resets all PID controller states (clearing integral windup) and immediately sends zero power to the heating hardware, stopping all active heating. The `automatic_control_enabled` flag in the heater state is set to `false`. This is a parameterless command; the heater remains started and can still accept manual `SetHeating` commands after automatic control is disabled. The heater `Stop` command also implicitly disables automatic control.
- [[proto/cmd.Heater.EnableAutomaticControl|EnableAutomaticControl]] — Enables PID-based automatic temperature regulation for all heater channels. When enabled, the heater module runs a periodic control loop (default 500ms interval) that computes power output per channel using PID control, comparing current temperatures against targets set via `SetAutomaticControlParams`. Target temperatures and PID gains (kp, ki, kd) are loaded from persistent configuration; this command only activates the control loop. The `automatic_control_enabled` flag in `ser.JonGuiDataHeater` reflects the current state. Use `DisableAutomaticControl` to stop automatic regulation, which also resets PID accumulators and sends zero power to hardware.
- [[proto/cmd.Heater.GetStatus|GetStatus]] — Requests the heater subsystem to report its current status including bus voltage, current, power consumption, and temperature status for all three heating zones.
- [[proto/cmd.Heater.Root|Root]] — Root command container for the heater subsystem. Contains all heater-related commands as a required oneof.
- [[proto/cmd.Heater.SetAutomaticControlParams|SetAutomaticControlParams]] — Configures target temperatures for the PID-based automatic heating control system across all three heater channels. Each channel parameter contains a `target_temperature` (0--60 C) that the PID controller will regulate toward. Only channels present in the message are updated; omitted channels retain their previous targets. On receipt the heater module resets PID integral and derivative accumulators for all channels to prevent windup when targets change, and persists the new targets to manifold state storage so they survive restarts.
- [[proto/cmd.Heater.SetHeating|SetHeating]] — Sets target temperatures and acceptable error margins for each of the three independent heating zones. The heater controller will attempt to maintain each zone at its target temperature within the specified error threshold.
- [[proto/cmd.Heater.Start|Start]] — Starts the heater subsystem, enabling temperature monitoring and heating control for all zones.
- [[proto/cmd.Heater.Stop|Stop]] — Stops the heater subsystem, disabling all heating zones and temperature control.


### cmd.Lira

- [[proto/cmd.Lira.JonGuiDataLiraTarget|JonGuiDataLiraTarget]] — A data structure containing geographic coordinates (latitude, longitude, altitude), angular positioning (azimuth, elevation), distance, and a UUID identifier for LIRA target information sent via the Refine_target command.
- [[proto/cmd.Lira.Refine_target|Refine_target]] — Updates target tracking coordinates by accepting a refined target location with GPS coordinates (latitude, longitude, altitude), azimuth/elevation angles, distance, and UUID to store in the system's last_target tracking state.
- [[proto/cmd.Lira.Root|Root]] — Root command container for LIRA target designation subsystem. Routes target refinement commands containing geospatial coordinates, directional information, and distance measurements for target tracking operations.


### cmd.Lrf

- [[proto/cmd.Lrf.DisableFogMode|DisableFogMode]] — Disables fog mode for LRF distance measurement, causing the laser rangefinder to revert to standard high-visibility measurement when taking distance readings.
- [[proto/cmd.Lrf.EnableFogMode|EnableFogMode]] — Enables fog mode on the LRF (Laser Range Finder) device for improved range finding performance in foggy or adverse weather conditions using low visible wavelength measurement.
- [[proto/cmd.Lrf.GetMeteo|GetMeteo]] — Requests current meteorological data (temperature, humidity, pressure) from the laser rangefinder device. This command is periodically sent by the system to retrieve environmental sensor readings used for ranging corrections and environmental monitoring.
- [[proto/cmd.Lrf.Measure|Measure]] — Initiates a single laser rangefinder measurement operation, optionally applying fog mode correction if enabled. Sends the appropriate UART bridge command to start a measured distance acquisition.
- [[proto/cmd.Lrf.NewSession|NewSession]] — Increments the LRF session counter to create a new targeting session. Each session groups related targeting operations together, and the command atomically increments a persistent session ID counter displayed in the UI.
- [[proto/cmd.Lrf.RefineOff|RefineOff]] — Disables LRF refinement mode, setting the refining state to false to stop precision refinement operations on the laser rangefinder system.
- [[proto/cmd.Lrf.RefineOn|RefineOn]] — Enables LRF refine mode to allow for precise targeting adjustments. When activated, the refine mode flag is set to true on the device, enabling fine-grained control for accurate target designation.
- [[proto/cmd.Lrf.Root|Root]] — Root command message for Laser Range Finder (LRF) operations that routes to various LRF subcommands using a oneof field. Commands include starting/stopping the LRF, measuring distances, controlling scanning and refinement modes, managing target designators, enabling fog mode, and requesting meteorological data.
- [[proto/cmd.Lrf.ScanOff|ScanOff]] — Disables continuous LRF scanning mode by sending a stop command to the laser rangefinder device and clearing the scanning and measuring state flags.
- [[proto/cmd.Lrf.ScanOn|ScanOn]] — Initiates continuous laser rangefinder (LRF) scanning mode, allowing the device to perform repeated distance measurements in a scan pattern until the scan is stopped.
- [[proto/cmd.Lrf.SetScanMode|SetScanMode]] — Configures the scanning frequency mode of the Laser Rangefinder device, allowing selection between predefined continuous scan rates ranging from 1 Hz to 200 Hz.
- [[proto/cmd.Lrf.Start|Start]] — Initializes and powers on the Laser Range Finder (LRF) device hardware by sending a startup command to the LRF UART control interface.
- [[proto/cmd.Lrf.Stop|Stop]] — Stops the LRF (Laser Rangefinder) device by setting its operational state to inactive, transitioning the device from active to stopped operation.
- [[proto/cmd.Lrf.TargetDesignatorOff|TargetDesignatorOff]] — Disables the laser target designator pointer on the LRF device. Triggered when the gamepad pointer button is released or manually via UI commands.
- [[proto/cmd.Lrf.TargetDesignatorOnModeA|TargetDesignatorOnModeA]] — Enables the laser pointer (target designator) in Mode A, allowing the LRF system to project a laser beam on a target for ranging and designation purposes.
- [[proto/cmd.Lrf.TargetDesignatorOnModeB|TargetDesignatorOnModeB]] — Enables the laser pointer on the LRF device in mode B, sending a hardware command to activate pointer mode 2 and updating the system state accordingly.


### cmd.Lrf_calib

- [[proto/cmd.Lrf_calib.Offsets|Offsets]] — A union message that contains one of four LRF calibration offset operations (set, save, reset, or shift) for adjusting laser rangefinder crosshair alignment on either the day or thermal imaging camera.
- [[proto/cmd.Lrf_calib.ResetOffsets|ResetOffsets]] — Resets laser rangefinder calibration offsets for either the day or thermal camera channel to their saved defaults, restoring the crosshair alignment to the previously stored calibration values.
- [[proto/cmd.Lrf_calib.Root|Root]] — Calibrates laser rangefinder (LRF) crosshair alignment offsets for both day and thermal cameras, supporting operations to set, shift, save, or reset X/Y pixel offsets across different zoom levels.
- [[proto/cmd.Lrf_calib.SaveOffsets|SaveOffsets]] — Persists the current LRF (Laser Rangefinder) camera alignment offsets for either day or thermal cameras to persistent storage by syncing the offset table to Redis.
- [[proto/cmd.Lrf_calib.SetOffsets|SetOffsets]] — Sets the X and Y laser rangefinder calibration offsets for either day or thermal camera channels, updating the crosshair alignment at the current zoom level.
- [[proto/cmd.Lrf_calib.ShiftOffsetsBy|ShiftOffsetsBy]] — Incrementally adjusts the laser rangefinder (LRF) calibration offsets by the specified x and y delta values for either day or thermal camera modes, shifting the crosshair alignment relative to the current calibration state.


### cmd.OSD

- [[proto/cmd.OSD.DisableDayOSD|DisableDayOSD]] — Sends a command to disable the day-mode on-screen display (OSD) on the device, toggling off the day camera telemetry overlay by sending an empty DisableDayOSD message to the command server.
- [[proto/cmd.OSD.DisableHeatOSD|DisableHeatOSD]] — Disables the thermal (heat) overlay display on the device's on-screen display (OSD), toggling off the heat map visualization that shows thermal imaging data.
- [[proto/cmd.OSD.EnableDayOSD|EnableDayOSD]] — Enables the day mode on-screen display (OSD) on the device, activating day vision overlay information in the visual feed.
- [[proto/cmd.OSD.EnableHeatOSD|EnableHeatOSD]] — Enables the thermal (heat) On-Screen Display (OSD) overlay on the video stream, allowing the user to toggle thermal imaging visualization on or off.
- [[proto/cmd.OSD.Root|Root]] — Routes OSD (On-Screen Display) commands to control thermal camera display modes and elements, including switching between default/LRF measurement/LRF result screens and enabling/disabling thermal and visible imagery overlays.
- [[proto/cmd.OSD.ShowDefaultScreen|ShowDefaultScreen]] — Instructs the device to display the default OSD home screen, typically triggered by gamepad exit button or keyboard hotkey.
- [[proto/cmd.OSD.ShowLRFMeasureScreen|ShowLRFMeasureScreen]] — Instructs the device to display the LRF measurement screen on the OSD, typically triggered when the user presses the measure button on the gamepad to initiate laser rangefinder measurement operations.
- [[proto/cmd.OSD.ShowLRFResultScreen|ShowLRFResultScreen]] — Commands the device to display the laser rangefinder (LRF) measurement results on the on-screen display (OSD), switching from the default view to show distance measurement data and targeting overlay.
- [[proto/cmd.OSD.ShowLRFResultSimplifiedScreen|ShowLRFResultSimplifiedScreen]] — Displays a simplified laser rangefinder result screen for continuous scanning mode, triggered after a long press of the measure button to show results in a compact overlay format during active LRF scanning.


### cmd.PMU

- [[proto/cmd.PMU.BootHeater|BootHeater]] — Powers on the PMU's onboard heater. Used for cold-weather operation to maintain safe operating temperatures.
- [[proto/cmd.PMU.ChargeDisable|ChargeDisable]] — Disables battery charging. Prevents the battery pack from charging even when external power is connected.
- [[proto/cmd.PMU.ChargeEnable|ChargeEnable]] — Enables battery charging. Allows the battery pack to charge from the external power source.
- [[proto/cmd.PMU.GetDataU1|GetDataU1]] — Requests sensor data from Unit 1. Retrieves readings from the U1 sensor module.
- [[proto/cmd.PMU.GetHeaterPowerState|GetHeaterPowerState]] — Requests the current power state of the PMU's heater. Returns whether the heater is powered on or off.
- [[proto/cmd.PMU.GetMeteo|GetMeteo]] — Requests environmental/meteorological data from the PMU. Returns temperature, humidity, and pressure readings.
- [[proto/cmd.PMU.PowerOff|PowerOff]] — Initiates a complete system power off. This will shut down the entire system including the compute module.
- [[proto/cmd.PMU.Root|Root]] — Root command container for the Power Management Unit. Contains all PMU-related commands as a required oneof including lifecycle control, charging, heater, and sensor queries.
- [[proto/cmd.PMU.Start|Start]] — Starts PMU monitoring and control. Enables power monitoring, current sensing, and temperature reporting.
- [[proto/cmd.PMU.Stop|Stop]] — Stops PMU monitoring and control. Disables power monitoring while keeping hardware powered.
- [[proto/cmd.PMU.TurnOff|TurnOff]] — Powers off the PMU hardware. This disables the physical power management circuitry.
- [[proto/cmd.PMU.TurnOn|TurnOn]] — Powers on the PMU hardware. This enables the physical power management circuitry.


### cmd.Power

- [[proto/cmd.Power.Root|Root]] — Routes power management commands to control individual power channels (0-7) or all channels simultaneously, supporting operations like setting power state per channel, powering all channels on/off, and configuring overcurrent alert thresholds in milliamps.
- [[proto/cmd.Power.SetAlertThreshold|SetAlertThreshold]] — Sets the overcurrent alert threshold for a power channel, specifying a maximum current limit (in milliamps) that triggers an alert when exceeded on channels 0-7.
- [[proto/cmd.Power.SetAll|SetAll]] — Sets the power state for all 8 system power channels (0-7) simultaneously; when powering off, the ORIN NUC channel (channel 5) is safely skipped to prevent remote shutdown of the main compute unit.
- [[proto/cmd.Power.SetChannel|SetChannel]] — Sends a command to control the power state of a single device channel (0-7) such as GPS, compass, cameras, thermal core, or heater. The message specifies the channel number and whether to power it on or off.


### cmd.RotaryPlatform

- [[proto/cmd.RotaryPlatform.Axis|Axis]] — Sends pan (azimuth) and/or tilt (elevation) axis control commands to the rotary platform, supporting multiple movement modes including absolute position, continuous rotation, and relative movement with optional speed control.
- [[proto/cmd.RotaryPlatform.Azimuth|Azimuth]] — Container command for controlling azimuth (horizontal) axis movement of a rotary platform, supporting absolute positioning, continuous rotation, relative adjustments, and halt operations.
- [[proto/cmd.RotaryPlatform.Elevation|Elevation]] — Container message for elevation (tilt) axis control commands on a rotary platform, supporting operations such as setting position, rotating to a target, continuous rotation, relative movement, and halt commands.
- [[proto/cmd.RotaryPlatform.GetMeteo|GetMeteo]] — Requests meteorological data from the rotary platform sensors with no parameters; the response is expected to be received via state message.
- [[proto/cmd.RotaryPlatform.Halt|Halt]] — Stops all rotary platform movement immediately by halting both azimuth and elevation axes simultaneously.
- [[proto/cmd.RotaryPlatform.HaltAzimuth|HaltAzimuth]] — Stops azimuth (horizontal rotation) movement of the rotary platform on the device, typically used to halt rotational motion along the yaw axis independently from elevation control.
- [[proto/cmd.RotaryPlatform.HaltElevation|HaltElevation]] — Stops the rotary platform's elevation (vertical) movement by halting the elevation axis. This command immediately ceases elevation rotation while potentially allowing other axes like azimuth to continue moving.
- [[proto/cmd.RotaryPlatform.HaltWithNDC|HaltWithNDC]] — Halts all rotary platform motion and records the final normalized device coordinates (NDC x, y) where the pan gesture ended, along with video frame and system monotonic timestamps for precise position tracking.
- [[proto/cmd.RotaryPlatform.PoiLookAt|PoiLookAt]] — Slews the rotary platform to the point of interest stored in slot `index` and applies that slot's day and heat zoom-table positions. The command is consumed by eutropia's drive host, which runs the sandboxed POI program: it verifies arrival by encoder readback (compensated frame, the same frame the slot was saved in), applies a per-leg deadline, and refuses to start while the platform is not started, is parked, or any drive exclusion (compass calibration, geodesic mode, active tracking or zoom-to-ROI) is in effect. The slot contents are read from poi_api_server (`GET /poi/{index}`); an empty slot is a fault, not a no-op.
- [[proto/cmd.RotaryPlatform.PoiSaveCurrent|PoiSaveCurrent]] — Stores the current pointing into point-of-interest slot `index`: the compensated azimuth and elevation the operator sees, plus both cameras' current zoom-table positions. Consumed by eutropia's drive host, which snapshots the state it publishes and POSTs the slot to poi_api_server (`POST /poi/{index}`), then refreshes the drive programs' POI table. No motion results from this command.
- [[proto/cmd.RotaryPlatform.Root|Root]] — Root message for rotary platform commands that routes various control operations via a oneof field, including motion control (start, stop, halt), axis manipulation (azimuth, elevation), coordinate-based targeting (GPS, NDC), and scan operations.
- [[proto/cmd.RotaryPlatform.RotateAzimuth|RotateAzimuth]] — Continuously rotates the azimuth axis at a specified speed in a specified direction (clockwise or counter-clockwise). This command initiates ongoing rotation until halted by a separate halt command.
- [[proto/cmd.RotaryPlatform.RotateAzimuthRelative|RotateAzimuthRelative]] — Rotates the rotary platform's azimuth (horizontal orientation) by a relative offset from its current position at a specified speed and direction. The offset value ranges from -180 to 180 degrees with clockwise or counter-clockwise movement.
- [[proto/cmd.RotaryPlatform.RotateAzimuthRelativeSet|RotateAzimuthRelativeSet]] — Sets the rotary platform's azimuth angle to a value relative to its current position, specified as an offset with a clockwise or counter-clockwise direction.
- [[proto/cmd.RotaryPlatform.RotateAzimuthTo|RotateAzimuthTo]] — Commands the rotary platform to rotate its azimuth axis to a specified target angle at a given speed and direction, allowing controlled positioning to a target heading.
- [[proto/cmd.RotaryPlatform.RotateElevation|RotateElevation]] — Commands continuous rotation of the elevation axis at a specified speed and direction. Used for smooth, ongoing elevation changes without a target position.
- [[proto/cmd.RotaryPlatform.RotateElevationRelative|RotateElevationRelative]] — Rotates the rotary platform's elevation axis by a relative amount from its current position at a specified speed and direction. The value parameter specifies the relative elevation angle change (-90 to 90 degrees).
- [[proto/cmd.RotaryPlatform.RotateElevationRelativeSet|RotateElevationRelativeSet]] — Sets the rotary platform's elevation angle to a value relative to its current position, specified as an offset with a clockwise or counter-clockwise direction.
- [[proto/cmd.RotaryPlatform.RotateElevationTo|RotateElevationTo]] — Commands the rotary platform to rotate its elevation axis to an absolute target angle (between -90 and 90 degrees) at a specified speed.
- [[proto/cmd.RotaryPlatform.RotateToGPS|RotateToGPS]] — Commands the rotary platform to rotate and point toward a specified GPS coordinate location, determined by latitude, longitude, and altitude values provided from user interaction with an interactive globe map.
- [[proto/cmd.RotaryPlatform.RotateToNDC|RotateToNDC]] — Commands the rotary platform to rotate and point toward a specific normalized device coordinate (NDC) location in a video frame, with synchronized timestamps from both the video frame and system state.
- [[proto/cmd.RotaryPlatform.ScanNext|ScanNext]] — Instructs the rotary platform to advance to the next scan node in a predefined scan path sequence. Complements the ScanPrev command for forward navigation through scan waypoints.
- [[proto/cmd.RotaryPlatform.ScanPause|ScanPause]] — Pauses an active rotary platform scan operation while keeping the scan state intact, allowing it to be resumed later with ScanUnpause.
- [[proto/cmd.RotaryPlatform.ScanPrev|ScanPrev]] — Commands the rotary platform to move to the previous node in the active scan pattern sequence.
- [[proto/cmd.RotaryPlatform.ScanRefreshNodeList|ScanRefreshNodeList]] — Triggers the server to refresh and resynchronize the current scanning pattern node list. The server updates its internal state and sends back signal notifications with the updated scan node information.
- [[proto/cmd.RotaryPlatform.ScanSelectNode|ScanSelectNode]] — Commands the rotary platform to select a specific scan waypoint by its index. When triggered from the UI (typically after adding or deleting nodes), it updates the backend state to reflect which waypoint is currently selected, enabling synchronization between the UI node list and server-side scan management.
- [[proto/cmd.RotaryPlatform.ScanStart|ScanStart]] — Begins automated execution of a pre-defined scan pattern on the rotary platform, sequentially positioning the platform at each configured scan node with specified zoom, azimuth, elevation, and dwell time parameters.
- [[proto/cmd.RotaryPlatform.ScanStop|ScanStop]] — Completely stops and terminates the scan pattern execution on the rotary platform, ending the scanning mode entirely. Unlike ScanPause which temporarily suspends the scan (allowing resume with ScanUnpause), ScanStop fully terminates the scan sequence.
- [[proto/cmd.RotaryPlatform.ScanUnpause|ScanUnpause]] — Resumes a paused scan pattern on the rotary platform after it has been temporarily suspended with ScanPause. This command is a state-transition operation that complements ScanPause, allowing the scan to continue from where it was interrupted without needing to restart.
- [[proto/cmd.RotaryPlatform.SetAzimuthValue|SetAzimuthValue]] — Sets the rotary platform to an absolute azimuth angle (0-360 degrees) with configurable rotation direction (clockwise or counter-clockwise). This immediate positioning command is used by UI slider controls to move the platform to a specific compass bearing.
- [[proto/cmd.RotaryPlatform.SetElevationValue|SetElevationValue]] — Instructs the rotary platform to move to an absolute elevation angle specified as a single value (-90 to 90 degrees). This command is triggered from the frontend's position input overlay when a user enters an elevation angle, immediately moving the platform to that exact elevation.
- [[proto/cmd.RotaryPlatform.SetMode|SetMode]] — Switches the rotary platform between different operating modes: initialization, speed control, position control, stabilization, targeting, or video tracking. The command changes how the platform processes subsequent movement commands.
- [[proto/cmd.RotaryPlatform.SetOriginGPS|SetOriginGPS]] — Establishes the GPS reference/origin point for the rotary platform by specifying latitude, longitude, and altitude coordinates. This origin point is used as the baseline reference for subsequent GPS-based operations like RotateToGPS.
- [[proto/cmd.RotaryPlatform.SetPlatformAzimuth|SetPlatformAzimuth]] — Calibrates the platform's absolute azimuth reference point during compass calibration, aligning the rotary system's mechanical coordinate frame with magnetic north. Unlike SetAzimuthValue which moves to a position, this sets the platform-level azimuth offset used to establish orientation reference.
- [[proto/cmd.RotaryPlatform.SetPlatformBank|SetPlatformBank]] — Sets the rotary platform's roll/bank angle to a specific value between -180 and 180 degrees. This command adjusts the platform's rotation around its longitudinal axis, allowing it to tilt left or right independently of azimuth and elevation adjustments.
- [[proto/cmd.RotaryPlatform.SetPlatformElevation|SetPlatformElevation]] — Calibrates the rotary platform's elevation reference baseline by setting an absolute calibration value. Unlike SetElevationValue which moves the elevation axis during normal operation, this command establishes the platform's elevation offset that persists as the reference baseline.
- [[proto/cmd.RotaryPlatform.Start|Start]] — Initializes the rotary platform subsystem by triggering a PING command to test the connection and discover the hardware address. Once the PING ACK is received, the system begins querying the platform's current state and transitions from initialization mode to operational readiness.
- [[proto/cmd.RotaryPlatform.Stop|Stop]] — Stops rotary platform motion and disables motor control, shutting down the rotary subsystem entirely. Unlike Halt which immediately freezes motion while keeping the system active and responsive, Stop is a lifecycle command that fully disables the rotary platform.
- [[proto/cmd.RotaryPlatform.Unpark|Unpark]]
- [[proto/cmd.RotaryPlatform.setUseRotaryAsCompass|setUseRotaryAsCompass]] — Toggles whether the rotary platform's position readings are used as the primary compass heading source. When enabled, the platform's orientation is stored in the `use_platform_positioning` state flag and used by the rotary subsystem to determine heading.


### cmd.System

- [[proto/cmd.System.DisableGeodesicMode|DisableGeodesicMode]] — Disables triangulation-based positioning by stopping the rotary alignment timer and returning the system to standard coordinate display mode. When geodesic mode is enabled, the system performs continuous rotary calibration to triangulate position; this command stops that alignment process.
- [[proto/cmd.System.DisableManualTime|DisableManualTime]] — Disables manual time mode and returns the device to automatically using GPS time instead of a manually-set timestamp. When triggered via the Manual Time Control UI toggle, it sets `use_manual_time` to false, allowing the device to synchronize with GPS time.
- [[proto/cmd.System.EnableGeodesicMode|EnableGeodesicMode]] — Enables geodesic/geographic coordinate mode, switching the system from local coordinate positioning to geographic coordinate positioning based on triangulation. This allows the system to track and display object positions using geographic coordinates rather than local reference frames.
- [[proto/cmd.System.EnableManualTime|EnableManualTime]] — Switches the device from GPS-based time synchronization to manual time mode, allowing users to manually set and adjust the system time using step commands for individual time units (year, month, day, hour, minute, second).
- [[proto/cmd.System.EnterTransport|EnterTransport]] — Initiates transport/shipping mode by commanding all system components (rotary platform, day camera zoom, thermal camera zoom) to move to safe parking positions (azimuth 0°, elevation 0°, zoom levels to zero) so the device can be safely transported or shipped.
- [[proto/cmd.System.MarkRecImportant|MarkRecImportant]] — Marks the currently active recording as important by toggling an `importantRecEnabled` flag on the device state. This indicates to the system that the current video/recording session should be flagged for preservation or special handling.
- [[proto/cmd.System.PowerOff|PowerOff]] — Triggers a controlled system shutdown sequence by creating a power-off flag that initiates the shutdown process. The frontend displays a confirmation dialog and monitors server disconnect to verify the system has powered down completely.
- [[proto/cmd.System.Reboot|Reboot]] — Restarts the system after gracefully shutting down services, allowing users to reconnect after the system comes back online. Unlike PowerOff which completely shuts down requiring manual restart, Reboot executes `/sbin/reboot` and the system automatically restarts.
- [[proto/cmd.System.ResetConfigs|ResetConfigs]] — Resets all device configurations to their default values. The command prompts for user confirmation to prevent accidental resets, and causes the system to reload with factory default settings after a server restart.
- [[proto/cmd.System.Root|Root]] — Union message that dispatches system-level commands through a required oneof field, allowing clients to send exactly one of 26 different system operation types (reboot, power-off, time adjustment, recording control, configuration management) in a type-safe, mutually-exclusive manner.
- [[proto/cmd.System.SaveFactoryDefaults|SaveFactoryDefaults]] — Persists the device's current system configuration as the factory default settings. These saved values become the new baseline that the device will revert to if a factory reset is performed.
- [[proto/cmd.System.SetLocalization|SetLocalization]] — Sets the system interface language/locale to one of the supported localizations: English (EN), Ukrainian (UA), Arabic (AR), or Czech (CS). This allows the UI to display text and localized content in the user's preferred language.
- [[proto/cmd.System.SetTimeAndZone|SetTimeAndZone]] — Atomically sets both the device's system timestamp and timezone in a single operation. Contains a 64-bit Unix timestamp (in nanoseconds) and a 32-bit timezone ID, primarily used when syncing the device's time with the browser's current time and locale.
- [[proto/cmd.System.SetTimeZone|SetTimeZone]] — Sets the device's timezone using a numeric zone ID (0-594) that maps to standard IANA timezone names (e.g., America/New_York, UTC). The timezone ID is used to update all subsequent time display calculations.
- [[proto/cmd.System.StartALl|StartALl]] — Triggers startup of all active system subsystems including cameras, sensors, and platform components. Sent from the UI via a "Start All Systems" button and represents the opposite action of the StopALl command.
- [[proto/cmd.System.StartRec|StartRec]] — Initiates video recording on the device, triggering continuous video capture from the camera streams. The recording state is tracked by the `rec_enabled` flag in the system state and works in conjunction with StopRec to control the recording lifecycle.
- [[proto/cmd.System.StepDay|StepDay]] — Increments or decrements the day value of the manually-set system time by a signed integer offset. Positive values advance to future days, negative values go to previous days. The UI provides buttons for -5, -1, +1, and +5 day adjustments.
- [[proto/cmd.System.StepHour|StepHour]] — Adjusts the manual system time by incrementing or decrementing the hour value using a positive or negative offset. The UI provides arrow buttons for -5, -1, +1, and +5 hour adjustments when manual time mode is enabled.
- [[proto/cmd.System.StepMinute|StepMinute]] — Increments or decrements the device's manual time minute value by the specified offset. Positive values increment and negative values decrement the minute. The UI provides buttons for -5, -1, +1, and +5 minute adjustments.
- [[proto/cmd.System.StepMonth|StepMonth]] — Adjusts the manually set time by incrementing or decrementing the month value by a specified offset. The UI provides arrow buttons to step the month forward or backward by 1 or 5 units at a time when manual time mode is enabled.
- [[proto/cmd.System.StepSecond|StepSecond]] — Increments or decrements the second value of the manual time by a specified offset amount. Positive integers increment seconds and negative integers decrement seconds, allowing fine-grained time adjustment via -5, -1, +1, +5 buttons.
- [[proto/cmd.System.StepTimeZone|StepTimeZone]] — Steps through available timezone options by a specified positive or negative index offset. The UI provides navigation buttons that cycle through the timezone list with offsets of -10, -1, +1, and +10 to select different timezones.
- [[proto/cmd.System.StepYear|StepYear]] — Increments or decrements the system year value by a specified offset when in manual time mode. The UI provides buttons to adjust the year by -5, -1, +1, or +5 years through the Manual Time Control component.
- [[proto/cmd.System.StopALl|StopALl]] — Shuts down all active system subsystems including cameras, sensors, and platform components. Triggered from the UI via a "Stop All Systems" button, this command represents the counterpart to StartALl for cleanly halting all system operations.
- [[proto/cmd.System.StopRec|StopRec]] — Instructs the device to immediately stop video recording. When received by the recording subsystem, this command ceases capture of thermal and day camera video data and finalizes the current recording file.
- [[proto/cmd.System.UnmarkRecImportant|UnmarkRecImportant]] — Removes the important flag from the current recording, allowing it to be treated as a normal recording. This command is invoked through the UI when users toggle off the "Mark as Important" button, working in tandem with MarkRecImportant to manage recording importance state.
- [[proto/cmd.System.WipeUserData|WipeUserData]] — Permanently deletes all user data from the device, including all photos, videos, recordings, and custom settings. Requires explicit user confirmation through a modal dialog before execution due to the irreversible nature of the operation.


### ser

- [[proto/ser.CvChannelMeta|CvChannelMeta]] — Per-channel CUDA IPC metadata carrying frame timing, a multi-level sharpness pyramid, and sensor gain. Populated from `/jon_cuda_ipc_day` and `/jon_cuda_ipc_heat` shared memory segments by the cv-gateway native reader (bezoar). Each video channel (day visible-light camera and heat thermal camera) gets its own `CvChannelMeta` instance embedded in [[ser.CvMeta]]. The sharpness pyramid is computed on-GPU by the Sharpy libraries (Variance of Laplacian for day, Morphological Gradient for heat) and is used for autofocus algorithms. The 85-float pyramid (1 + 4 + 16 + 64) enables coarse-to-fine focus search across the frame.
- [[proto/ser.CvMeta|CvMeta]] — Aggregated CV metadata payload combining all shared-memory sources at 60fps. Populated by the cv-gateway native library (`libbezoar_cv_meta.so`), which runs 5 background threads that block on futex to read from SHM segments (`/jon_shm_rotary`, `/jon_shm_cam_day`, `/jon_shm_cam_heat`, `/jon_cuda_ipc_day`, `/jon_cuda_ipc_heat`). The aggregated proto is encoded via nanopb on the critical read path (~10us latency) and published to the DataBus by CvMetaModule. The StateEnricherModule then injects it into `JonGUIState.opaque_payloads` (UUID `019c3e33-d52d-7552-b36b-6fdcaa5d59b8`) and patches top-level state fields with sharpness scores and sensor gain from the embedded channel metadata.
- [[proto/ser.DetectionConfig|DetectionConfig]] — Inference configuration snapshot attached to each detection result. Records the confidence and NMS thresholds that were active when the detector produced a given batch of detections. Embedded as the `config` field in both [[ser.ObjectDetectionsDay]] and [[ser.ObjectDetectionsHeat]].
- [[proto/ser.DetectionFrameMeta|DetectionFrameMeta]] — Frame metadata for temporal correlation between detection results and the video pipeline. Carried as a sub-message within `ObjectDetectionsDay` and `ObjectDetectionsHeat`, providing the timestamps, generation counter, and dimensions of the source frame that was analyzed by the inference engine. These fields originate from the CUDA IPC shared memory control structure (`CudaIpcControl`), where the pipeline producer writes them during each frame push under a seqlock. The bezoar native library reads these values via its `CudaIpcReader`, caches them in the detection batch, and encodes them into the nanopb output. Consumers use this metadata to correlate detection bounding boxes with the correct video frame and to verify that detection results match the expected frame dimensions for coordinate mapping.
- [[proto/ser.JonGUIState|JonGUIState]] — Root protocol buffer message that aggregates telemetry and state from multiple subsystems including system status, meteorological data, laser rangefinder, time, GPS, compass with calibration, rotary encoder, dual thermal and optical cameras, recording metadata, spatiotemporal data, power management, PMU, and heater. Synchronized using monotonic timestamps for both day and thermal imaging pipelines, published periodically to the frontend.
- [[proto/ser.JonGuiDataActualSpaceTime|JonGuiDataActualSpaceTime]] — Encapsulates real-time spatial position and temporal information of the system, containing three-dimensional attitude angles (azimuth, elevation, bank), geographic coordinates (latitude, longitude, altitude), and a timestamp. Displayed across multiple UI widgets including the azimuth compass, altitude scale, and time widget.
- [[proto/ser.JonGuiDataCV|JonGuiDataCV]] — CV Gateway state enrichment message: the CV subsystem's per-tick state as it appears on the STATE plane, for both day and heat camera channels.

This message is populated by the CV Gateway and embedded in `JonGUIState` before being written to shared memory. It provides real-time visibility into:
- Autofocus sweep progress and state
- Current and best sharpness measurements, and the sharpness metrics carrying their temporal derivatives
- The regions of interest each camera operation is using — focus, track, zoom, fx — per channel
- CV bridge container status, exit reason, uptime and restart count
- Camera 3D pose and velocity, per channel
- Tracked objects, each carrying a UUID for joining against external data
- Whether the Ring-Trinity board tracker is running

The ROI coordinates use Normalized Device Coordinates (NDC) ranging from -1 to 1, where (0,0) is the center of the frame.

**This message is the STATE plane, and it is not the whole CV surface.** The richer CV output — object detections ([[proto/ser.ObjectDetectionsDay]], [[proto/ser.ObjectDetectionsHeat]]), SAM tracking, the aggregated [[proto/ser.CvMeta]], and the Ring-Trinity metric pose [[proto/ser.TrinityTracking]] — does not travel here. It rides `JonGUIState.opaque_payloads` as [[proto/ser.JonOpaquePayload]] entries and is decoded only by the consumers that handle each payload type, the OSD overlay path among them. A consumer of this message does not parse those payloads.

That split is why a fact an opaque payload already carries can also appear here, in the reduced form a state consumer can act on — `trinity_tracking_active` (#90) is exactly that shape. The two are different contracts with different consumers and different evolution boundaries; neither is a copy of the other, and neither suppresses the other.
- [[proto/ser.JonGuiDataCameraDay|JonGuiDataCameraDay]] — Captures the complete operational state of the day camera, including normalized control positions (focus, zoom, iris), automatic control modes (auto-focus, auto-iris, auto-gain), field of view angles, and image processing parameters like CLAHE level and FX mode presets.
- [[proto/ser.JonGuiDataCameraHeat|JonGuiDataCameraHeat]] — Represents the complete operational and configuration state of the thermal/infrared camera system, including optical parameters (zoom position, field-of-view, focus mode), image processing settings (AGC mode, filter selection, CLAHE enhancement, DDE dynamics enhancement), and operational status.
- [[proto/ser.JonGuiDataCompass|JonGuiDataCompass]] — Represents the real-time orientation and calibration state of a compass sensor, containing directional measurements (azimuth, elevation, bank angles), calibration offsets, magnetic declination, and status flags for whether the compass is running and calibrating.
- [[proto/ser.JonGuiDataCompassCalibration|JonGuiDataCompassCalibration]] — Represents the current state and progress of a compass calibration process, tracking the current step (stage), total steps required (final_stage), target orientation angles the user should point toward, and the overall calibration status.
- [[proto/ser.JonGuiDataDrive|JonGuiDataDrive]] — Status of the sandboxed drive programs (scan, point-of-interest look-at, transport park) hosted by eutropia's drive host. Exactly one program may own the rotary platform at a time; this message reports which one, its lifecycle state and phase, and the program-agnostic progress counters. Scan-specific readbacks (`is_scanning`, `scan_target`, `current_scan_node`) remain on `JonGuiDataRotary` and are written by the same host. Published on every state tick from the owning program's status block; all zeros when no program is loaded.
- [[proto/ser.JonGuiDataGps|JonGuiDataGps]] — Represents the complete GPS positioning state of the system, including both automatic GPS fix coordinates and manually-entered fallback coordinates, along with the current fix quality type (none, 1D, 2D, 3D, or manual mode) and operational status.
- [[proto/ser.JonGuiDataHeater|JonGuiDataHeater]] — Heater subsystem status. Reports overall bus power consumption (voltage, current, power) and per-channel status for up to 3 heating channels (e.g., camera housing, lens, enclosure).
- [[proto/ser.JonGuiDataHeaterChannelStatus|JonGuiDataHeaterChannelStatus]] — Status of an individual heater channel. Reports current temperature (°C), applied and target voltages for PWM control, and enabled state.
- [[proto/ser.JonGuiDataLrf|JonGuiDataLrf]] — Encapsulates the operational state of a Laser Range Finder (LRF) device, tracking scanning/measuring modes, measurement progress, laser pointer modes, fog mode, refinement status, and targeting data including precise georeferenced measurements with target/observer coordinates and distances.
- [[proto/ser.JonGuiDataMeteo|JonGuiDataMeteo]] — Represents environmental sensor data containing atmospheric measurements: temperature (in degrees Celsius), humidity (as a percentage), and pressure (in Pascal units). Used for ballistics calculations and system monitoring across multiple subsystems.
- [[proto/ser.JonGuiDataPMU|JonGuiDataPMU]] — Power Management Unit status. Reports battery/power system state including temperature, voltage, current sensor (INA) readings, heater state, charging status, and environmental data.
- [[proto/ser.JonGuiDataPower|JonGuiDataPower]] — Represents real-time power distribution state across all 8 system channels (GPS, Compass, LRF, Day Camera, Thermal Camera, ORIN NUC, Thermal Core, and Heater), with each channel tracking voltage, current, power consumption, on/off state, and fault alarm status.
- [[proto/ser.JonGuiDataPowerModule|JonGuiDataPowerModule]] — Represents the real-time power state and telemetry for a single power distribution channel, tracking voltage, current, power consumption, on/off state, and alarm status. Used to monitor individual hardware subsystems for power management and diagnostics.
- [[proto/ser.JonGuiDataQuaternion|JonGuiDataQuaternion]] — Unit quaternion representing 3D orientation (w + xi + yj + zk). Should be normalized (w² + x² + y² + z² = 1). Used for tracked object orientation in the world coordinate frame.
- [[proto/ser.JonGuiDataROI|JonGuiDataROI]] — Region of Interest (ROI) for CV tracking. Defines a rectangular area in normalized coordinates where -1,-1 is top-left and 1,1 is bottom-right of the frame. Used to specify the initial tracking target or search area for computer vision algorithms.
- [[proto/ser.JonGuiDataRecOsd|JonGuiDataRecOsd]] — Represents the recording on-screen display (OSD) configuration state, tracking whether thermal and day camera overlays are enabled, along with their respective crosshair offset positions for proper alignment on recorded frames.
- [[proto/ser.JonGuiDataRotary|JonGuiDataRotary]] — Represents the real-time operational state of a rotary platform, tracking current position (azimuth, elevation, platform angles), motion characteristics (speeds and movement flags), scanning mode and progression, and auxiliary features (sun position data and compass integration mode).
- [[proto/ser.JonGuiDataSharpness|JonGuiDataSharpness]] — Image sharpness metric for autofocus. Contains the normalized sharpness value (0-1) along with first and second derivatives for tracking focus trend. Used by CV algorithms to determine optimal focus position by maximizing sharpness.
- [[proto/ser.JonGuiDataStabCorrection|JonGuiDataStabCorrection]] — One video channel's display-stabilisation correction, in that channel's delivered-FX-raster pixels (day 1920x1080, heat 900x720). The value is what the pixel applier ADDS to image position — a scene feature at raw pixel p renders at p + C — so display-space consumers add it to scene-locked overlay positions and subtract it from operator input (taps, drags) before that input becomes a command. The anchor is the LRF crosshair (the digital-zoom centre), not the raster centre. Published by the eutropia stabilisation smoother on JonGuiDataCV.stab_correction_day/_heat; it reflects the smoother's output, not a receipt that pixels were actually warped (with the FX bypass engaged the display shows raw pixels while this value still carries the smoother's correction).
- [[proto/ser.JonGuiDataSystem|JonGuiDataSystem]] — Captures comprehensive device telemetry including hardware metrics (CPU/GPU temperature and load), recording state with timestamped directories, storage status with warning indicators, operational modes (tracking, stabilization, recognition, geodesic, vampire, CV dumping), and battery status, enabling real-time monitoring of system health and operational state in the frontend UI.
- [[proto/ser.JonGuiDataTarget|JonGuiDataTarget]] — Encodes a single laser rangefinder (LRF) measurement with the geographic coordinates of the detected target and the observer's position, orientation, and GPS fix quality, along with computed 2D and 3D distances and visual properties for UI display. Serves as the core data structure for target tracking in the GUI, enabling real-time visualization of LRF measurements on maps with color-coded targets.
- [[proto/ser.JonGuiDataTime|JonGuiDataTime]] — Manages the device's current time state with support for both system and manually-set timestamps, allowing time zone context via zone_id while a boolean flag determines whether to use the manual override or system timestamp. Used throughout the frontend and backend to synchronize time-based operations across the device state distribution system.
- [[proto/ser.JonGuiDataTrackedObject|JonGuiDataTrackedObject]] — A tracked object in the CV tracking system. Contains a unique UUID for object identity across frames, the object's 3D transform (position, orientation, velocities), the 2D bounding box in the current frame, and the tracking state (initializing, tracking, lost, etc.).
- [[proto/ser.JonGuiDataTransform3D|JonGuiDataTransform3D]] — Complete 3D transform including position, orientation, and motion state. Represents a tracked object's pose and velocity in the world coordinate frame. Position is in meters, orientation is a unit quaternion, velocities are in m/s and rad/s respectively.
- [[proto/ser.JonGuiDataVector3|JonGuiDataVector3]] — 3D vector with x, y, z components. Used for positions (in meters) and velocities (in m/s) in the tracking system's coordinate frame.
- [[proto/ser.JonOpaquePayload|JonOpaquePayload]] — Extensibility container that carries subsystem-specific binary payloads identified by UUIDv7 type markers and semantic versioning, allowing handlers to match payload types and verify version compatibility without the transport layer interpreting the binary data. Appears in both state and command messages as a repeated field to support multiple concurrent subsystem extensions.
- [[proto/ser.JonOpaquePayloadVersion|JonOpaquePayloadVersion]] — Structured version triplet (major, minor, build) that enables version-aware handling of opaque subsystem-specific payloads, supporting both build numbers and millisecond-precision timestamps for the build field. Allows handlers to perform version compatibility checks through simple numeric comparisons without string parsing.
- [[proto/ser.ObjectDetection|ObjectDetection]] — A single object detection bounding box result from the inference engine. Detector-agnostic: used by both day and heat camera channels within [[proto/ser.ObjectDetectionsDay]] and [[proto/ser.ObjectDetectionsHeat]]. The bounding box is expressed in Normalized Device Coordinates (NDC) where (-1, -1) is the top-left corner, (1, 1) is the bottom-right corner, and (0, 0) is the center of the frame. This coordinate system is consistent with [[proto/ser.JonGuiDataROI]]. Up to 256 detections may be reported per frame.
- [[proto/ser.ObjectDetectionsDay|ObjectDetectionsDay]] — Object detection results for the day (visible-light) camera channel. Produced by the YOLO/TensorRT detector process running inference on day camera frames at approximately 30 fps. The detector sends results via IPC to the bezoar native library, which caches them in a seqlock-protected store and encodes to nanopb on demand. The encoded payload is injected by cv-gateway into `JonGUIState.opaque_payloads` as a `JonOpaquePayload` identified by UUID `019c40f6-825c-7f4c-8284-ddad4375ed9b`. Each message carries the full set of detected objects for a single frame along with inference metadata for latency monitoring and frame correlation.
- [[proto/ser.ObjectDetectionsHeat|ObjectDetectionsHeat]] — Object detection results for the thermal (heat/IR) camera channel. Produced by the CUDA/TensorRT detector process running in a separate GCC+nvcc-compiled binary, written into a seqlock cache via `bezoar_object_detect_write_heat()`, and read on the JVM hot path via the `bezoar_object_detect_read_heat()` critical downcall (target latency <100us). The native library encodes detection batches to nanopb into a static 12KB buffer. The StateEnricherModule injects the serialized payload into `JonGUIState.opaque_payloads` at inference rate (~30fps). Each detection uses NDC coordinates (-1.0 to 1.0), matching the JonGuiDataROI coordinate system. The day and heat channels use independent seqlock caches and separate DataBus topics for failure isolation.

UUID: `019c40f6-825d-7e0e-9893-87c7b167a751`
- [[proto/ser.OsdClientMetadata|OsdClientMetadata]] — Client-side canvas and rendering metadata for resolution-aware OSD overlay compositing. Injected by the frontend into `JonGUIState.opaque_payloads` so that the server-side OSD renderer (WASM or native) can correctly map its fixed-resolution framebuffer onto the client's variable-size display canvas. Carries the physical canvas dimensions, device pixel ratio, the NDC bounding box of the video proxy quad, the computed scale factor between OSD buffer pixels and physical display pixels, and the current UI theme parameters (OKLCH color space and sharp/smooth mode).
- [[proto/ser.RgbColor|RgbColor]] — Represents an RGB color value with red, green, and blue components each constrained to 0-255, used in the UI to specify and display target marker colors for laser rangefinder measurements and on-screen display (OSD) configuration.
- [[proto/ser.SamTrackingDay|SamTrackingDay]]
- [[proto/ser.SamTrackingFrameMeta|SamTrackingFrameMeta]]
- [[proto/ser.SamTrackingHeat|SamTrackingHeat]]
- [[proto/ser.SamTrackingKalmanState|SamTrackingKalmanState]]
- [[proto/ser.ScanNode|ScanNode]] — Represents a single waypoint within a rotary platform scanning pattern, containing positional data (azimuth and elevation angles), camera zoom table positions for both day and thermal cameras, and transition parameters (linger time at the waypoint and speed to the next node). Used across frontend scanning pattern editors, backend scan APIs, and embedded device controllers to define and execute multi-point scanning sequences.
- [[proto/ser.TrinityAltPose|TrinityAltPose]] — The pose the disambiguator did **not** choose.

Present when the near-affine two-fold ambiguity admitted a second solution that reprojection error
could not separate from the chosen one. It is carried so a consumer can see the fork and apply its
own prior — a scale prior, a temporal track, or an external range — rather than inheriting a
selection it cannot audit.
- [[proto/ser.TrinityBoardVersion|TrinityBoardVersion]] — Identifies **which physical board** a pose refers to.

This is data, not a schema version. `JonOpaquePayload.version` already carries the payload's
wire-format version, and the two move independently: a schema change does not reprint the board,
and a new board revision does not change this message's shape. A single conflated "version" would
make a reprint indistinguishable from a wire bump.

`geometry_sha256` hashes the board's geometry manifest, which is the one home for every board
dimension. Pinning that hash pins the geometry a pose was computed against exactly, so a reprint
from an edited manifest is visibly a different board rather than a silent scale error.
- [[proto/ser.TrinityTracking|TrinityTracking]] — Full-precision pose of the Ring-Trinity golden fiducial board, injected into
`JonGUIState.opaque_payloads` by the trinity tracker at track rate.

Unlike the SAM tracking payloads — which carry NDC in `[-1, 1]` because a bounding box is a
screen artifact — this carries a **metric** pose in metres plus a unit quaternion. The board is
the ground-truth judge other measurements are scored against, so quantising it to screen
coordinates would destroy the precision it exists to provide.

**Precision is anisotropic, and the encoding is not the limit.** A `double` resolves to ~1.8e-12
mm at 10 m — about twelve orders of magnitude finer than a millimetre. The optics bind instead.
Lateral error is `sigma_px * range / focal_px`: at 0.4 px centre localisation that is ~1.1 mm at
10 m on day (focal_px 3517) and ~3.0 mm on heat (focal_px 1320). Range from apparent size obeys
`dZ/Z = dS/S` and is far worse — ~27 mm at 10 m on day, degrading with the square of range.
Millimetre-class is therefore reachable laterally at short range and **not** reachable in range
from board extent alone, which is why `sigma_range_m` is separate from `sigma_position_m` and why
`range_source` exists.

**The two-fold ambiguity is surfaced rather than hidden.** A planar target under near-affine
projection admits two poses that reprojection error cannot separate, so a payload emitting only
the chosen one is silently wrong about half the time at range. `alternate` carries the rejected
solution and `ambiguity_resolved` says whether the fork was closed.

**The activity question is answered on the other plane.** This payload rides
`JonGUIState.opaque_payloads` and is decoded only by consumers that handle its type — the OSD
overlay, which renders the pose. A consumer that only needs to know whether the tracker is RUNNING
reads `trinity_tracking_active` (#90) on [[proto/ser.JonGuiDataCV]] instead, which sits on the
STATE plane and needs no payload decode. That flag is a single bit: it collapses `LOCKED`,
`SEARCHING`, `DEGRADED` and `BOARD_MISMATCH` to `true`, so `status` here stays the authoritative and
richer value and nothing about it is superseded. The two are different contracts for different
consumers, not two copies of one fact.


### ui

- [[proto/ui.ArcProps|ArcProps]]
- [[proto/ui.BarProps|BarProps]]
- [[proto/ui.ButtonMatrixProps|ButtonMatrixProps]]
- [[proto/ui.ButtonProps|ButtonProps]]
- [[proto/ui.ChartProps|ChartProps]]
- [[proto/ui.ChartSeries|ChartSeries]]
- [[proto/ui.CheckboxProps|CheckboxProps]]
- [[proto/ui.CmdSpec|CmdSpec]]
- [[proto/ui.Color|Color]]
- [[proto/ui.ColorBinding|ColorBinding]]
- [[proto/ui.CursorRequest|CursorRequest]]
- [[proto/ui.DropdownProps|DropdownProps]]
- [[proto/ui.EventBinding|EventBinding]]
- [[proto/ui.FieldPatch|FieldPatch]]
- [[proto/ui.GestureSpec|GestureSpec]]
- [[proto/ui.HostProxyProps|HostProxyProps]]
- [[proto/ui.HostToWasm|HostToWasm]]
- [[proto/ui.HoverState|HoverState]]
- [[proto/ui.ImageProps|ImageProps]]
- [[proto/ui.LabelProps|LabelProps]]
- [[proto/ui.Layout|Layout]]
- [[proto/ui.LedProps|LedProps]]
- [[proto/ui.Lifecycle|Lifecycle]]
- [[proto/ui.LineProps|LineProps]]
- [[proto/ui.ObjProps|ObjProps]]
- [[proto/ui.Point|Point]]
- [[proto/ui.PointerEvent|PointerEvent]]
- [[proto/ui.RollerProps|RollerProps]]
- [[proto/ui.ScaleProps|ScaleProps]]
- [[proto/ui.ScaleSection|ScaleSection]]
- [[proto/ui.Screen|Screen]]
- [[proto/ui.ScreenPatch|ScreenPatch]]
- [[proto/ui.ShadowBundle|ShadowBundle]]
- [[proto/ui.SliderProps|SliderProps]]
- [[proto/ui.SpinboxProps|SpinboxProps]]
- [[proto/ui.SpinnerProps|SpinnerProps]]
- [[proto/ui.StateUpdate|StateUpdate]]
- [[proto/ui.StyleGroup|StyleGroup]]
- [[proto/ui.StyleProperty|StyleProperty]]
- [[proto/ui.StyleVariant|StyleVariant]]
- [[proto/ui.SubjectDeclaration|SubjectDeclaration]]
- [[proto/ui.SubjectValue|SubjectValue]]
- [[proto/ui.SwitchProps|SwitchProps]]
- [[proto/ui.TableProps|TableProps]]
- [[proto/ui.TabviewProps|TabviewProps]]
- [[proto/ui.TargetBox|TargetBox]]
- [[proto/ui.TargetOverlayProps|TargetOverlayProps]]
- [[proto/ui.TextareaProps|TextareaProps]]
- [[proto/ui.TreePatchOp|TreePatchOp]]
- [[proto/ui.VisibilityBinding|VisibilityBinding]]
- [[proto/ui.WasmToHost|WasmToHost]]
- [[proto/ui.WidgetNode|WidgetNode]]



## Enums

- [[proto/ui.Align|Align]]
- [[proto/ui.ArcMode|ArcMode]]
- [[proto/ser.JonGuiDataCV.AutofocusState|AutofocusState]]
- [[proto/ui.BarMode|BarMode]]
- [[proto/ui.BaseDir|BaseDir]]
- [[proto/ui.BlendMode|BlendMode]]
- [[proto/ui.BorderSide|BorderSide]]
- [[proto/ui.ChartAxis|ChartAxis]]
- [[proto/ui.ChartType|ChartType]]
- [[proto/ui.CompareOp|CompareOp]]
- [[proto/ui.CursorType|CursorType]]
- [[proto/ser.JonGuiDataCV.CvBridgeExitReason|CvBridgeExitReason]]
- [[proto/ser.JonGuiDataCV.CvBridgeStatus|CvBridgeStatus]]
- [[proto/ser.DetectionStatus|DetectionStatus]] — Detector-agnostic inference status codes reported by the object detection pipeline. Indicates whether the most recent inference cycle completed successfully or identifies the specific failure mode. Used as the `status` field in [[proto/ser.ObjectDetectionsDay]] and [[proto/ser.ObjectDetectionsHeat]] to communicate pipeline health. A status other than OK means the `detections` array should be considered empty or stale.
- [[proto/ui.Dir|Dir]]
- [[proto/ui.EventTrigger|EventTrigger]]
- [[proto/ui.FlexAlign|FlexAlign]]
- [[proto/ui.FlexFlow|FlexFlow]]
- [[proto/ui.GestureDeltaSign|GestureDeltaSign]]
- [[proto/ui.GestureKind|GestureKind]]
- [[proto/ui.GradDir|GradDir]]
- [[proto/ui.GridAlign|GridAlign]]
- [[proto/ui.InputSchemaVersion|InputSchemaVersion]]
- [[proto/ser.JonGuiDataAccumulatorStateIdx|JonGuiDataAccumulatorStateIdx]] — Represents the charge state index of an internal battery (accumulator) with 11 discrete states ranging from empty to full, plus a charging state. Used in the battery indicator UI component with color-coded visual feedback (red=empty, orange=low, yellow=medium, green=good/full, blue=charging).
- [[proto/ser.JonGuiDataClientApp|JonGuiDataClientApp]] — Identifies the type of client application connecting to the system, enabling the server to differentiate between different UI implementations. Defines four application types: BROWSER_UI (web interface), BROWSER_MAP (map view), DESKTOP_NATIVE (desktop app), and MOBILE_NATIVE (mobile app).
- [[proto/ser.JonGuiDataClientType|JonGuiDataClientType]] — Categorizes different types of clients connecting to the system based on their connection method: internal computer vision systems (INTERNAL_CV), local network access (LOCAL_NETWORK), certificate-protected secured connections (CERTIFICATE_PROTECTED), and LIRA device interfaces (LIRA).
- [[proto/ser.JonGuiDataCompassCalibrateStatus|JonGuiDataCompassCalibrateStatus]] — Represents the current state of the compass calibration process with five distinct statuses: not calibrating (idle), calibrating short, calibrating long (multi-stage extended calibration), finished (successful completion), and error (calibration failure).
- [[proto/ser.JonGuiDataCompassUnits|JonGuiDataCompassUnits]] — Specifies the angular unit system for displaying compass bearing measurements. Supports four standard angle measurement units: degrees (0-360), mils (0-6400 military/tactical), gradians (0-400), and milliradians (0-2000).
- [[proto/ser.JonGuiDataDriveProgram|JonGuiDataDriveProgram]] — Identifies which sandboxed drive program owns the rotary platform: none, the scan-pattern walker, the point-of-interest look-at, the transport-park sequencer, or the compass calibration servo.
- [[proto/ser.JonGuiDataDriveState|JonGuiDataDriveState]] — Lifecycle of the owning drive program. IDLE: loaded, not running. ARMED: start requested, waiting for the platform to be still and any halt re-assert window to elapse. RUNNING: driving. PAUSED: scan paused by the operator. DONE: finished (park forwarded, POI arrived). FAULT: aborted with an `error_code` on JonGuiDataDrive; a HALT was emitted.
- [[proto/ser.JonGuiDataExtBatStatus|JonGuiDataExtBatStatus]] — Represents the operational state of an external battery pack, indicating whether the battery is actively charging, discharging, or performing cell balancing. Displayed in the UI with color-coded indicators and pulsing animations for charging/balancing states.
- [[proto/ser.JonGuiDataFxModeDay|JonGuiDataFxModeDay]] — Represents selectable image processing presets for the day camera that optimize video quality for different environmental conditions. Includes DEFAULT plus six named presets (A-F) corresponding to scenarios like Daytime, Dusk, and Fog with distinct filter and processing algorithms.
- [[proto/ser.JonGuiDataFxModeHeat|JonGuiDataFxModeHeat]] — Defines available special effects (FX) modes for the thermal camera that control how the thermal image is processed and displayed. Includes DEFAULT plus six modes (A-F) representing different hardware-level or DSP-level image processing algorithms.
- [[proto/ser.JonGuiDataGpsFixType|JonGuiDataGpsFixType]] — Represents the quality and type of GPS positional fix available: No Fix (no satellite lock), 1D Fix (time only), 2D Fix (lat/lon without altitude), 3D Fix (full position with altitude), and Manual Fix (user-provided coordinates). Displayed in UI as "N/A", "TIME", "2D", "3D", and "MAN".
- [[proto/ser.JonGuiDataGpsUnits|JonGuiDataGpsUnits]] — Specifies the coordinate format used for displaying GPS coordinates in the UI: DECIMAL_DEGREES (e.g., 40.7128), DEGREES_MINUTES_SECONDS (e.g., 40° 42' 46.08" N), or DEGREES_DECIMAL_MINUTES (e.g., 40° 42.768' N).
- [[proto/ser.JonGuiDataLrfScanModes|JonGuiDataLrfScanModes]] — Specifies continuous scanning frequency modes for the laser rangefinder (LRF) device, allowing operators to configure how frequently distance measurements are acquired. Supports rates ranging from 1 Hz to 200 Hz for different precision and responsiveness requirements.
- [[proto/ser.JonGuiDataRecOsdScreen|JonGuiDataRecOsdScreen]] — Specifies which OSD (On-Screen Display) overlay screen to display during recording: MAIN (default interface), LRF_MEASURE (laser rangefinder measurement input), LRF_RESULT (full rangefinder results), or LRF_RESULT_SIMPLIFIED (condensed results).
- [[proto/ser.JonGuiDataRotaryDirection|JonGuiDataRotaryDirection]] — Specifies the rotation direction of a rotary device, supporting clockwise and counter-clockwise movements. Used in gimbal and pan-tilt motor commands and telemetry to indicate the direction of rotation.
- [[proto/ser.JonGuiDataRotaryMode|JonGuiDataRotaryMode]] — Represents the operational modes of a rotary gimbal platform: initialization (system setup), speed (direct velocity control), position (absolute pointing), stabilization (steady tracking), targeting (guided engagement), and video tracker (automated object tracking using computer vision).
- [[proto/ser.JonGuiDataStateSource|JonGuiDataStateSource]] — Indicates the origin of GUI state data in the system: DAY_PIPELINE (day imaging pipeline), HEAT_PIPELINE (thermal imaging pipeline), or SYSTEM (centralized system components). Used to track which subsystem originated a state update.
- [[proto/ser.JonGuiDataSystemLocalizations|JonGuiDataSystemLocalizations]] — Specifies the UI language setting for the system, supporting four languages: English (EN), Ukrainian (UA), Arabic (AR), and Czech (CS). Users can switch the interface language via the Language control palette, which updates both the UI and device state.
- [[proto/ser.JonGuiDataTargetType|JonGuiDataTargetType]] — Discriminates what a capture event (a `target_id` increment in `ser.JonGuiDataTarget`) is: a ranged TARGET or a PHOTO. Published by manifold from the internal `has_range` flag; consumed by media_meta_pub to set the media_items `kind`, which drives the photo/target split in the media API and gallery.
- [[proto/ser.JonGuiDataTimeFormats|JonGuiDataTimeFormats]] — Defines time display format options for the GUI system: H_M_S displays time as Hours:Minutes:Seconds, while Y_m_D_H_M_S displays full date and time as Year-Month-Day Hours:Minutes:Seconds. Used to configure how timestamps are rendered in the UI.
- [[proto/ser.JonGuiDataVideoChannel|JonGuiDataVideoChannel]] — Specifies the active video source with two primary channels: thermal imaging (HEAT) and visible light (DAY). Used throughout command messages and UI components to route camera control operations and render channel-specific overlays to their respective video pipelines.
- [[proto/ser.JonGuiDataVideoChannelHeatAGCModes|JonGuiDataVideoChannelHeatAGCModes]] — Defines three Automatic Gain Control (AGC) modes for thermal camera operation: Mode 1 (mixed AGC), Mode 2 (auto AGC 1), and Mode 3 (auto AGC 2) that adjust image brightness and contrast for optimal thermal imaging visibility. Used throughout the system to configure thermal camera settings via the HeatCamera.SetAGC command.
- [[proto/ser.JonGuiDataVideoChannelHeatFilters|JonGuiDataVideoChannelHeatFilters]] — Specifies thermal camera display color schemes with four filter modes: Hot White (hottest objects rendered in white), Hot Black (hottest objects rendered in black), Sepia (warm tone colorization), and Sepia Inverse (inverted warm tone colorization). Applied via HeatCamera.SetFilters to control how thermal image data is visualized in real-time.
- [[proto/ser.JonGuiDatatLrfLaserPointerModes|JonGuiDatatLrfLaserPointerModes]] — Controls the laser rangefinder's target designator pointer, supporting three operational states: disabled (OFF), and two active modes (ON_1 and ON_2) for different targeting scenarios. The pointer_mode field in JonGuiDataLrf tracks the current state of the LRF laser designator output.
- [[proto/ui.LabelLongMode|LabelLongMode]]
- [[proto/ui.NdcYSense|NdcYSense]]
- [[proto/ui.PatchEncoding|PatchEncoding]]
- [[proto/ui.PatchKind|PatchKind]]
- [[proto/ui.PatchOpKind|PatchOpKind]]
- [[proto/ui.PointerKind|PointerKind]]
- [[proto/ui.PointerPhase|PointerPhase]]
- [[proto/ui.ProxyMode|ProxyMode]]
- [[proto/ui.RollerMode|RollerMode]]
- [[proto/ser.SamTrackingState|SamTrackingState]]
- [[proto/ser.SamTrackingStatus|SamTrackingStatus]]
- [[proto/ui.ScaleMode|ScaleMode]]
- [[proto/ui.StylePropertyType|StylePropertyType]]
- [[proto/ui.SubjectType|SubjectType]]
- [[proto/ui.TextAlign|TextAlign]]
- [[proto/ui.TextDecor|TextDecor]]
- [[proto/ui.ThemeMode|ThemeMode]]
- [[proto/ser.JonGuiDataTrackedObject.TrackingState|TrackingState]]
- [[proto/ser.TrinityRangeSource|TrinityRangeSource]] — How `TrinityTracking.position_z_m` was obtained.

Not cosmetic. Monocular range from the board's apparent size degrades with the square of range
(~27 mm at 10 m on day, ~676 mm at 50 m), while a direct LRF distance is roughly range-independent
and is the millimetre-class option. The two differ by more than an order of magnitude at 50 m and
look identical on the wire, so a consumer must be able to tell which it received.
- [[proto/ser.TrinityTrackingStatus|TrinityTrackingStatus]] — Tracking state for this tick.

`DEGRADED` is the load-bearing member: the board was found but is too small or too oblique for a
pose, so position may be approximate while orientation is **not** valid. A small planar target
loses orientation observability long before it loses position, and a single "tracking" state would
force the consumer to trust both or neither.

`BOARD_MISMATCH` fires when `StartTrackTrinity.expect_board` was set and a different board was
detected, instead of silently producing a pose against different geometry.

**ABSENCE AND `IDLE` ARE DIFFERENT FACTS.** The payload is published whenever the tracker PROCESS is up, including when it is not tracking — that is what `IDLE` is for. `IDLE` present means the tracker is up and deliberately not tracking; the payload being ABSENT means the producer is down, has not published yet, or the payload was dropped. A consumer that reads "no payload" as "not tracking" reports a crashed tracker as a stopped one — one reading for two states that need opposite responses.

**A consumer that only needs "is tracking on" never has to reach this enum.**
`trinity_tracking_active` (#90) on [[proto/ser.JonGuiDataCV]] answers that on the STATE plane, with
no opaque payload to decode: `LOCKED`, `SEARCHING`, `DEGRADED` and `BOARD_MISMATCH` are all `true`
there and `IDLE` is `false`. It exists for the toggle affordance and is not a substitute — this
enum remains the authoritative value for anything that must distinguish a lock from a search, a
degraded solve, or a board mismatch.
- [[proto/ui.WidgetType|WidgetType]]

