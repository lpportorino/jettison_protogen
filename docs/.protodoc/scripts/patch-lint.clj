#!/usr/bin/env bb
;; patch-lint.clj — Fix all lint issues in proto-db.edn
;;
;; Usage: bb docs/.protodoc/scripts/patch-lint.clj [db-path]
;;
;; Fixes:
;;   - 25 invalid references (replace/remove)
;;   - 22 semantic type mismatches
;;   - 39 interaction-incomplete (add missing :ui-pattern/:feedback)
;;   - ~319 constrained fields without descriptions (template-based)
;;   - ~254 field metadata without descriptions (same templates)
;;   - 22 enums with undocumented values

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.pprint :as pp])

(def db-path (or (first *command-line-args*)
                 "docs/.protodoc/proto-db.edn"))

(println "Loading database from:" db-path)
(def db (edn/read-string (slurp db-path)))
(println "Loaded" (count (:messages db)) "messages," (count (:enums db)) "enums")

;; ============================================================================
;; Phase 1a: Fix invalid references
;; ============================================================================

(def reference-fixes
  {"cmd.DayCamera.SetAutoFocus"              "cmd.CV.SetAutoFocus"
   "ser.JonGuiDataLira"                      :remove
   "ser.JonGuiDataOsd"                       "ser.JonGuiDataRecOsd"
   "ser.JonGuiDataOSD"                       "ser.JonGuiDataRecOsd"
   "cmd.RotaryPlatform.Azimuth.RotateTo"     "cmd.RotaryPlatform.RotateAzimuthTo"
   "cmd.RotaryPlatform.Azimuth.Rotate"       "cmd.RotaryPlatform.RotateAzimuth"
   "cmd.RotaryPlatform.Azimuth.Halt"         "cmd.RotaryPlatform.HaltAzimuth"
   "ser.JonGuiDataRotaryPlatform"            "ser.JonGuiDataRotary"
   "cmd.RotaryPlatform.Elevation.SetValue"   "cmd.RotaryPlatform.SetElevationValue"
   "cmd.RotaryPlatform.Elevation.Halt"       "cmd.RotaryPlatform.HaltElevation"
   "ser.JonGuiDataRotaryScan"                :remove
   "cmd.System.SyncBrowserTimeAndZone"       :remove
   "ser.JonGuiDataSystemTime"                "ser.JonGuiDataTime"
   "ser.JonGuiDataSystemRecording"           "ser.JonGuiDataRecOsd"
   "cmd.HeatCamera.SetAgc"                   "cmd.HeatCamera.SetAGC"
   "cmd.HeatCamera.SetFilter"                "cmd.HeatCamera.SetFilters"})

(defn fix-ref [ref]
  (if-let [replacement (get reference-fixes ref)]
    (when (not= replacement :remove) replacement)
    ref))

(defn fix-refs [refs]
  (when refs
    (vec (keep fix-ref refs))))

(defn fix-interaction-refs [interaction]
  (if interaction
    (cond-> interaction
      (:related-state interaction)
      (update :related-state fix-refs)
      (:related-commands interaction)
      (update :related-commands fix-refs))
    interaction))

;; ============================================================================
;; Phase 1b: Fix semantic type mismatches
;; ============================================================================

;; Map of [msg-id field-name] -> new semantic type or :remove
(def semantic-type-fixes
  {["cmd.CV.SetAutoFocus" "value"]                    :toggle-state
   ["cmd.Compass.SetUseRotaryPosition" "flag"]        :toggle-state
   ["cmd.DayCamera.SetAutoGain" "value"]              :toggle-state
   ["cmd.DayCamera.SetInfraRedFilter" "value"]        :toggle-state
   ["cmd.Gps.SetUseManualPosition" "flag"]            :toggle-state
   ["cmd.Power.SetChannel" "power_on"]                :toggle-state
   ["ser.JonGuiDataCameraDay" "auto_iris"]            :toggle-state
   ["ser.JonGuiDataGps" "is_started"]                 :toggle-state
   ["cmd.HeatCamera.SetDDELevel" "value"]             :percentage
   ["cmd.Power.SetAlertThreshold" "threshold_ma"]     :count
   ["ser.JonGuiDataCompass" "bank"]                   :angle
   ["ser.JonGuiDataGps" "manual_altitude"]            :distance
   ["ser.JonGuiDataGps" "manual_latitude"]            :coordinate-geo
   ["ser.JonGuiDataGps" "timestamp"]                  :timestamp
   ["ser.JonGuiDataTime" "zone_id"]                   :identifier
   ["ser.ScanNode" "elevation"]                       :angle
   ["ser.ScanNode" "linger"]                          :duration
   ;; message-type fields — remove :interaction entirely
   ["cmd.HeatCamera.Zoom" "set_zoom_table_value"]     :remove
   ["cmd.Lira.Refine_target" "target"]                :remove
   ["ser.JonGuiDataPower" "s0"]                       :remove
   ["ser.JonGuiDataPower" "s1"]                       :remove
   ["ser.JonGuiDataPower" "s2"]                       :remove
   ["ser.JonGuiDataPower" "s3"]                       :remove
   ["ser.JonGuiDataPower" "s4"]                       :remove})

(defn fix-field-semantic-type [msg-id field]
  (let [field-name (:name field)
        fix (get semantic-type-fixes [msg-id field-name])]
    (cond
      (nil? fix) field
      (= fix :remove) (dissoc field :interaction)
      :else (assoc-in field [:interaction :semantic-type] fix))))

;; ============================================================================
;; Phase 2a: Fix interaction-incomplete
;; ============================================================================

;; Messages that need :ui-pattern and/or :feedback added
(def interaction-completion
  ;; cmd.*.Root dispatch messages -> :state-machine-menu :fire-and-forget
  {"cmd.Root"                       {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.CV.Root"                    {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.Compass.Root"               {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.DayCamera.Root"             {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.Gps.Root"                   {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.Heater.Root"                {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.Lrf.Root"                   {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.Lrf_calib.Root"             {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.PMU.Root"                   {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.Power.Root"                 {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   "cmd.RotaryPlatform.Root"        {:ui-pattern :state-machine-menu :feedback :fire-and-forget}
   ;; Non-Root cmd messages
   "cmd.Lira.JonGuiDataLiraTarget"  {:ui-pattern :indicator          :feedback :fire-and-forget}
   "cmd.Lrf_calib.Offsets"          {:ui-pattern :indicator          :feedback :fire-and-forget}
   ;; ser.* state/sensor messages -> :indicator :fire-and-forget
   "ser.CvChannelMeta"              {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.CvMeta"                     {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataCameraDay"        {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataCompass"          {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataCompassCalibration" {:ui-pattern :indicator        :feedback :fire-and-forget}
   "ser.JonGuiDataGps"              {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataHeaterChannelStatus" {:ui-pattern :indicator       :feedback :fire-and-forget}
   "ser.JonGuiDataMeteo"            {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataPMU"              {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataPower"            {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataPowerModule"      {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataRecOsd"           {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataRotary"           {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonOpaquePayloadVersion"    {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.ObjectDetectionsDay"        {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.ObjectDetectionsHeat"       {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.ScanNode"                   {:ui-pattern :indicator          :feedback :fire-and-forget}
   ;; Composite state messages -> :indicator :fire-and-forget
   "ser.JonGUIState"                {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonGuiDataActualSpaceTime"  {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.DetectionFrameMeta"         {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.ObjectDetection"            {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.OsdClientMetadata"          {:ui-pattern :indicator          :feedback :fire-and-forget}
   "ser.JonOpaquePayload"           {:ui-pattern :indicator          :feedback :fire-and-forget}
   ;; Config messages -> :tabbed-config :fire-and-forget
   "ser.DetectionConfig"            {:ui-pattern :tabbed-config      :feedback :fire-and-forget}
   "ser.JonGuiDataSystem"           {:ui-pattern :tabbed-config      :feedback :fire-and-forget}
   "ser.JonGuiDataTime"             {:ui-pattern :tabbed-config      :feedback :fire-and-forget}})

(defn fix-interaction-completeness [msg-id interaction]
  (if-let [completion (get interaction-completion msg-id)]
    (merge completion interaction) ;; interaction wins for existing keys, completion fills gaps
    interaction))

;; ============================================================================
;; Phase 2b: Generate field descriptions from patterns
;; ============================================================================

(defn roi-coord? [field-name constraints]
  (and field-name
       (contains? #{"x1" "y1" "x2" "y2" "x" "y"} field-name)
       (= (:gte constraints) -1) (= (:lte constraints) 1)))

(defn ndc-field? [field-name constraints]
  (and field-name
       (contains? #{"x" "y"} field-name)
       (= (:gte constraints) -1) (= (:lte constraints) 1)))

(defn speed-field? [field-name constraints]
  (and field-name (= field-name "speed")
       (or (= (:gte constraints) 0) (= (:gt constraints) 0))
       (= (:lte constraints) 1)))

(defn azimuth-field? [field-name constraints]
  (and field-name
       (str/includes? (str/lower-case field-name) "azimuth")
       (= (:gte constraints) 0)
       (or (= (:lt constraints) 360) (= (:lte constraints) 360))))

(defn elevation-field? [field-name constraints]
  (and field-name
       (str/includes? (str/lower-case field-name) "elevation")
       (= (:gte constraints) -90)
       (= (:lte constraints) 90)))

(defn bank-field? [field-name constraints]
  (and field-name
       (str/includes? (str/lower-case field-name) "bank")
       (= (:gte constraints) -180)
       (or (= (:lt constraints) 180) (= (:lte constraints) 180))))

(defn latitude-field? [field-name _constraints]
  (and field-name (str/includes? (str/lower-case field-name) "lat")))

(defn longitude-field? [field-name _constraints]
  (and field-name (str/includes? (str/lower-case field-name) "lon")))

(defn altitude-field? [field-name constraints]
  (and field-name
       (str/includes? (str/lower-case field-name) "alt")
       (= (:gte constraints) -430)))

(defn timestamp-field? [field-name constraints]
  (and field-name
       (str/includes? (str/lower-case field-name) "timestamp")
       (or (= (:gte constraints) 0) (nil? constraints))))

(defn normalized-field? [_field-name constraints field-type]
  (and (contains? #{:double :float} field-type)
       (= (:gte constraints) 0) (= (:lte constraints) 1)))

(defn percentage-field? [_field-name constraints field-type]
  (and (contains? #{:int32 :uint32} field-type)
       (= (:gte constraints) 0) (= (:lte constraints) 100)))

(defn direction-field? [field-name constraints]
  (and (= field-name "direction")
       (:defined-only constraints)))

(defn mode-field? [field-name constraints]
  (and (= field-name "mode")
       (:defined-only constraints)))

(defn channel-field? [field-name constraints]
  (and (= field-name "channel")
       (or (:defined-only constraints) (:lte constraints))))

(defn defined-only-enum? [_field-name constraints field-type]
  (and (= field-type :enum)
       (:defined-only constraints)))

(defn required-msg-field? [_field-name constraints field-type]
  (and (= field-type :message)
       (:required constraints)))

(defn temperature-field? [_field-name constraints]
  (and (number? (:gte constraints))
       (<= (:gte constraints) -273)
       (number? (:lte constraints))
       (>= (:lte constraints) 100)))

(defn voltage-field? [field-name _constraints]
  (and field-name
       (or (str/includes? (str/lower-case field-name) "voltage")
           (str/ends-with? field-name "_V"))))

(defn current-field? [field-name _constraints]
  (and field-name
       (or (str/includes? (str/lower-case field-name) "current")
           (str/ends-with? field-name "_A"))))

(defn power-field? [field-name _constraints]
  (and field-name
       (or (str/includes? (str/lower-case field-name) "power")
           (str/ends-with? field-name "_W"))))

(defn zoom-table-field? [field-name constraints]
  (and field-name
       (or (str/includes? (str/lower-case field-name) "zoom")
           (str/includes? (str/lower-case field-name) "index"))
       (or (= (:gte constraints) 0) (= (:gte constraints) 1))))

(defn generate-field-description [field-name constraints field-type type-ref]
  (when-not field-name (println "  WARN: field with nil name, skipping"))
  (when (nil? field-name) nil) ;; guard
  (let [field-name (or field-name "")
        roi-edge ({"x1" "Left" "y1" "Top" "x2" "Right" "y2" "Bottom"} field-name)]
    (cond
      ;; Most specific first
      (and roi-edge (roi-coord? field-name constraints))
      (str roi-edge " edge in NDC (-1.0 to 1.0)")

      (and (ndc-field? field-name constraints)
           (not roi-edge))
      (str (str/upper-case field-name) " coordinate in NDC (-1.0 to 1.0)")

      (latitude-field? field-name constraints)
      "Latitude in decimal degrees"

      (longitude-field? field-name constraints)
      "Longitude in decimal degrees"

      (altitude-field? field-name constraints)
      "Altitude in meters above sea level"

      (azimuth-field? field-name constraints)
      "Azimuth angle in degrees (0=North, clockwise)"

      (elevation-field? field-name constraints)
      "Elevation angle in degrees"

      (bank-field? field-name constraints)
      "Bank/roll angle in degrees"

      (speed-field? field-name constraints)
      "Movement speed (0.0=stopped, 1.0=maximum)"

      (direction-field? field-name constraints)
      "Rotation direction"

      (mode-field? field-name constraints)
      "Operating mode"

      (channel-field? field-name constraints)
      (if (:defined-only constraints)
        "Video channel selector"
        "Power channel index")

      (timestamp-field? field-name constraints)
      "Monotonic timestamp in microseconds"

      (temperature-field? field-name constraints)
      "Temperature in degrees Celsius"

      (voltage-field? field-name constraints)
      "Bus voltage in volts"

      (current-field? field-name constraints)
      "Current draw in amperes"

      (power-field? field-name constraints)
      "Power consumption in watts"

      (percentage-field? field-name constraints field-type)
      "Percentage value (0-100)"

      (normalized-field? field-name constraints field-type)
      "Normalized value (0.0 to 1.0)"

      (required-msg-field? field-name constraints field-type)
      (if type-ref
        (str "Required \u2014 see [[proto/" type-ref "]]")
        "Required sub-message")

      (defined-only-enum? field-name constraints field-type)
      "See related enum for valid values"

      ;; Specific remaining patterns by name
      (= field-name "offset")
      "Step offset value"

      (str/starts-with? field-name "uuid_part")
      "UUID component (combined parts form full UUID)"

      (str/starts-with? field-name "frame_time")
      "Frame timestamp for synchronization"

      (str/starts-with? field-name "state_time")
      "State snapshot timestamp for synchronization"

      (= field-name "client_time_ms")
      "Client-side timestamp in milliseconds"

      (= field-name "session_id")
      "Session identifier"

      (= field-name "protocol_version")
      "Protocol version number"

      (and (= field-name "value")
           (:gte constraints) (:lte constraints)
           (= (:gte constraints) -1) (= (:lte constraints) 1))
      "Signed offset value (-1.0 to 1.0)"

      (and (= field-name "value")
           (:gte constraints) (= (:gte constraints) 0)
           (or (= (:lte constraints) 1) (nil? (:lte constraints))))
      "Normalized value (0.0 to 1.0)"

      (and (= field-name "value")
           (:lt constraints) (:gte constraints)
           (= (:gte constraints) -180))
      "Angle value in degrees"

      (and (= field-name "value")
           (:lte constraints) (:gte constraints))
      (str "Value (" (:gte constraints) " to " (:lte constraints) ")")

      (and (= field-name "value")
           (:gte constraints) (nil? (:lte constraints)))
      "Numeric value"

      (and (= field-name "target_value")
           (:lte constraints) (:gte constraints))
      "Target position value"

      (str/includes? field-name "crosshair_offset")
      "Crosshair position offset"

      (str/includes? field-name "osd_enabled")
      (str (if (str/starts-with? field-name "day") "Day" "Heat") " camera OSD enabled state")

      (= field-name "screen")
      "OSD screen selection"

      (str/includes? field-name "zoom_table_pos_max")
      "Maximum zoom table position"

      (str/includes? field-name "zoom_table_pos")
      "Current zoom table position"

      (str/includes? field-name "zoom_pos")
      "Current optical zoom position (0.0 to 1.0)"

      (str/includes? field-name "digital_zoom_level")
      "Digital zoom multiplier"

      (str/includes? field-name "clahe_level")
      "CLAHE contrast enhancement level (0.0 to 1.0)"

      (str/includes? field-name "iris_pos")
      "Iris aperture position (0.0=closed, 1.0=open)"

      (str/includes? field-name "focus_pos")
      "Focus motor position (0.0 to 1.0)"

      (str/starts-with? field-name "sensor_gain")
      "Sensor gain level (0.0 to 1.0)"

      (str/includes? field-name "fov_degrees")
      (str (if (str/starts-with? field-name "horizontal") "Horizontal" "Vertical")
           " field of view in degrees")

      (str/includes? field-name "fx_mode")
      "Image processing effects mode"

      (str/includes? field-name "exposure")
      "Exposure level (0.0 to 1.0)"

      (str/includes? field-name "dde_level")
      "DDE (Dynamic Detail Enhancement) level"

      (str/includes? field-name "agc_mode")
      "AGC (Automatic Gain Control) mode"

      (str/includes? field-name "filter")
      "Thermal image color filter"

      ;; Auto-focus/auto-iris/infrared state booleans
      (= field-name "auto_focus")
      "Auto-focus enabled state"

      (= field-name "infrared_filter")
      "Infrared filter enabled state"

      (= field-name "auto_gain")
      "Auto-gain enabled state"

      ;; Heater fields
      (str/starts-with? field-name "target_temp_channel_")
      "Target temperature setpoint in degrees Celsius"

      ;; GPS fields
      (= field-name "fix_type")
      "GPS fix type"

      (= field-name "is_started")
      "GPS receiver started state"

      ;; Compass fields
      (str/starts-with? field-name "magneticDeclination")
      "Magnetic declination correction in degrees"

      (str/starts-with? field-name "offsetAzimuth")
      "Azimuth offset correction in degrees"

      (str/starts-with? field-name "offsetElevation")
      "Elevation offset correction in degrees"

      ;; Compass calibration fields
      (str/starts-with? field-name "target_azimuth")
      "Target azimuth for calibration in degrees"

      (str/starts-with? field-name "target_bank")
      "Target bank for calibration in degrees"

      (str/starts-with? field-name "target_elevation")
      "Target elevation for calibration in degrees"

      (= field-name "stage")
      "Current calibration stage"

      (= field-name "final_stage")
      "Total calibration stages"

      (= field-name "status")
      "Calibration status"

      ;; System fields
      (= field-name "cpu_load")
      "CPU utilization percentage"

      (= field-name "gpu_load")
      "GPU utilization percentage"

      (= field-name "cpu_temperature")
      "CPU temperature in degrees Celsius"

      (= field-name "gpu_temperature")
      "GPU temperature in degrees Celsius"

      (= field-name "disk_space")
      "Available disk space percentage"

      (= field-name "ext_bat_capacity")
      "External battery capacity percentage"

      (= field-name "power_consumption")
      "Total power consumption in watts"

      (str/starts-with? field-name "cur_video_rec_dir_")
      (let [part (subs field-name (count "cur_video_rec_dir_"))]
        (str "Recording directory " part " component"))

      (= field-name "accumulator_state")
      "Battery/accumulator state"

      (= field-name "loc")
      "UI localization/language setting"

      ;; Power channel fields
      (= field-name "power_on")
      "Channel power state"

      (= field-name "threshold_ma")
      "Alert threshold in milliamps"

      (= field-name "has_alarm")
      "Alarm triggered state"

      (= field-name "is_on")
      "Channel powered on state"

      ;; Rotary platform fields
      (str/includes? field-name "init_status")
      "Initialization status code"

      (str/includes? field-name "_speed")
      "Current speed (-1.0 to 1.0)"

      (str/includes? field-name "platform_azimuth")
      "Platform mounting azimuth in degrees"

      (str/includes? field-name "platform_bank")
      "Platform mounting bank angle in degrees"

      (str/includes? field-name "platform_elevation")
      "Platform mounting elevation in degrees"

      (str/starts-with? field-name "scan_target")
      (if (str/ends-with? field-name "_max")
        "Maximum scan node count"
        "Current scan target node index")

      (str/starts-with? field-name "sun_")
      (str "Sun " (subs field-name 4) " in degrees")

      ;; LRF fields
      (= field-name "measure_id")
      "Rangefinder measurement sequence ID"

      (= field-name "pointer_mode")
      "Laser pointer operating mode"

      ;; LRF calibration offset fields
      (and (contains? #{"x" "y"} field-name)
           (or (and (:lte constraints) (>= (:lte constraints) 1000))
               (and (:gte constraints) (<= (:gte constraints) -1000))))
      (str (str/upper-case field-name) " pixel offset for LRF reticle alignment")

      ;; Lira target fields
      (= field-name "distance")
      "Distance to target in meters"

      ;; Detection/tracking fields
      (= field-name "bounding_box")
      "Required \u2014 detection bounding box"

      (= field-name "transform")
      "Required \u2014 object 3D transform"

      (str/starts-with? field-name "uuid")
      "Object UUID"

      (= field-name "state")
      "Tracking state"

      ;; Quaternion/vector fields
      (and (contains? #{"w" "x" "y" "z"} field-name)
           (:required constraints))
      (str "Required " (str/upper-case field-name) " component")

      ;; Opaque payload fields
      (= field-name "payload")
      "Serialized payload bytes"

      (= field-name "type_uuid")
      "Type identifier UUID"

      (= field-name "version")
      "Required \u2014 payload version info"

      ;; Sharpness
      (str/starts-with? field-name "sharpness")
      "Focus sharpness metric"

      (str/starts-with? field-name "best_sharpness")
      "Best sharpness found during autofocus sweep"

      (str/starts-with? field-name "best_focus_pos")
      "Focus position with best sharpness (0.0 to 1.0)"

      (str/starts-with? field-name "sweep_progress")
      "Autofocus sweep progress percentage (0-100)"

      (str/starts-with? field-name "autofocus_state")
      "Autofocus algorithm state"

      (str/starts-with? field-name "bridge_status")
      "CV bridge connection status"

      (str/starts-with? field-name "bridge_uptime")
      "CV bridge uptime in milliseconds"

      (str/starts-with? field-name "last_exit_reason")
      "CV bridge last exit/restart reason"

      (str/starts-with? field-name "restart_count")
      "CV bridge restart count"

      (str/starts-with? field-name "roi_")
      (let [edge ({"roi_x1" "Left" "roi_y1" "Top" "roi_x2" "Right" "roi_y2" "Bottom"} field-name)]
        (str (or edge "ROI") " edge in NDC (-1.0 to 1.0)"))

      ;; Color fields
      (contains? #{"red" "green" "blue"} field-name)
      (str (str/capitalize field-name) " color channel (0-255)")

      ;; Scan node fields
      (str/includes? field-name "DayZoomTableValue")
      "Day camera zoom table index for this scan node"

      (str/includes? field-name "HeatZoomTableValue")
      "Heat camera zoom table index for this scan node"

      (= field-name "linger")
      "Dwell time at this scan node in seconds"

      (= field-name "index")
      "Zero-based node index"

      ;; Distance fields
      (str/starts-with? field-name "distance_")
      "Calculated distance to target in meters"

      (str/starts-with? field-name "observer_")
      (let [suffix (subs field-name (count "observer_"))]
        (cond
          (str/includes? suffix "azimuth") "Observer azimuth in degrees"
          (str/includes? suffix "elevation") "Observer elevation in degrees"
          (str/includes? suffix "bank") "Observer bank angle in degrees"
          (str/includes? suffix "latitude") "Observer latitude in decimal degrees"
          (str/includes? suffix "longitude") "Observer longitude in decimal degrees"
          (str/includes? suffix "fix_type") "Observer GPS fix type"
          :else (str "Observer " suffix)))

      (= field-name "target_id")
      "Target tracking identifier"

      ;; System mode fields
      (contains? #{"cv_dumping" "geodesic_mode" "recognition_mode"
                    "stabilization_mode" "tracking" "vampire_mode"} field-name)
      (str (str/replace field-name "_" " ") " state")

      ;; Client fields
      (= field-name "client_app")
      "Client application type"

      (= field-name "client_type")
      "Client connection type"

      (= field-name "state_source")
      "Source pipeline for this state update"

      (= field-name "zone_id")
      "IANA timezone identifier"

      ;; Monotonic time fields
      (str/includes? field-name "monotonic")
      (cond
        (str/includes? field-name "system") "System monotonic time in microseconds"
        (str/includes? field-name "day") "Day camera frame monotonic time in microseconds"
        (str/includes? field-name "heat") "Heat camera frame monotonic time in microseconds"
        :else "Monotonic timestamp in microseconds")

      ;; PTS fields
      (str/includes? field-name "pts")
      (cond
        (str/includes? field-name "day") "Day camera frame PTS in nanoseconds"
        (str/includes? field-name "heat") "Heat camera frame PTS in nanoseconds"
        :else "Presentation timestamp in nanoseconds")

      ;; Humidity/pressure
      (= field-name "humidity")
      "Relative humidity percentage (0-100)"

      (= field-name "pressure")
      "Atmospheric pressure in pascals"

      ;; Manual time
      (= field-name "manual_timestamp")
      "Manually-set timestamp value"

      (= field-name "use_manual_time")
      "Use manual time instead of GPS/NTP"

      ;; offset_value -1 to 1
      (and (= field-name "offset_value")
           (= (:gte constraints) -1) (= (:lte constraints) 1))
      "Signed offset value (-1.0 to 1.0)"

      ;; target_value with azimuth range
      (and (= field-name "target_value")
           (= (:gte constraints) 0))
      "Target position value"

      ;; value with wide azimuth range (-360 to 360)
      (and (= field-name "value")
           (or (= (:gt constraints) -360) (= (:gte constraints) -360))
           (or (= (:lt constraints) 360) (= (:lte constraints) 360)))
      "Azimuth angle value in degrees"

      ;; Shift DDE
      (and (= field-name "value")
           (= (:gte constraints) -100) (= (:lte constraints) 100))
      "DDE level shift amount (-100 to 100)"

      ;; Sharpness value
      (and (= field-name "value")
           (= (:gte constraints) 0) (= (:lte constraints) 1)
           (nil? (:example constraints)))
      "Normalized value (0.0 to 1.0)"

      ;; Magnetic declination on cmd
      (and (= field-name "value")
           (= (:gte constraints) -180) (or (= (:lt constraints) 180)))
      "Angle value in degrees (-180 to 180)"

      ;; Bool fields - toggle state descriptions
      (and (= field-type :bool) (= field-name "value"))
      "Enable/disable state"

      (and (= field-type :bool) (= field-name "flag"))
      "Enable/disable flag"

      (and (= field-type :bool) (str/starts-with? (or field-name "") "auto_"))
      (str "Auto-" (subs field-name 5) " enabled state")

      ;; Message-type fields with :raw semantic — generic
      (and (= field-type :message) (nil? constraints))
      (if type-ref
        (str "See [[proto/" type-ref "]]")
        nil)

      ;; Fallback: leave nil (will not add description)
      :else nil)))

(defn maybe-add-description [field msg-id]
  (if (seq (:description field))
    field ;; already has description
    (let [desc (generate-field-description (:name field) (:constraints field) (:type field) (:type-ref field))]
      (if desc
        (assoc field :description desc)
        field))))

;; ============================================================================
;; Phase 2c: Generate enum value descriptions
;; ============================================================================

(defn enum-value-prefix [values]
  ;; Find common prefix by looking at all value names
  (when (seq values)
    (let [names (map :name values)
          ;; Find longest common prefix
          min-name (apply min-key count names)
          prefix-len (count (take-while
                             (fn [i]
                               (let [c (nth min-name i)]
                                 (every? #(and (< i (count %)) (= (nth % i) c)) names)))
                             (range (count min-name))))]
      (subs min-name 0 prefix-len))))

(defn humanize-suffix [suffix]
  (-> suffix
      (str/replace #"^_+" "")
      (str/replace "_" " ")
      str/lower-case
      str/capitalize))

(def manual-enum-descriptions
  ;; [enum-id value-name] -> description
  {;; Accumulator states
   "JON_GUI_DATA_ACCUMULATOR_STATE_UNSPECIFIED" "Unspecified/default value"
   "JON_GUI_DATA_ACCUMULATOR_STATE_UNKNOWN"     "Unknown battery state"
   "JON_GUI_DATA_ACCUMULATOR_STATE_EMPTY"       "Battery empty"
   "JON_GUI_DATA_ACCUMULATOR_STATE_1"           "Battery level 1 (lowest)"
   "JON_GUI_DATA_ACCUMULATOR_STATE_2"           "Battery level 2"
   "JON_GUI_DATA_ACCUMULATOR_STATE_3"           "Battery level 3"
   "JON_GUI_DATA_ACCUMULATOR_STATE_4"           "Battery level 4"
   "JON_GUI_DATA_ACCUMULATOR_STATE_5"           "Battery level 5"
   "JON_GUI_DATA_ACCUMULATOR_STATE_6"           "Battery level 6"
   "JON_GUI_DATA_ACCUMULATOR_STATE_FULL"        "Battery fully charged"
   "JON_GUI_DATA_ACCUMULATOR_STATE_CHARGING"    "Battery charging"

   ;; Detection status
   "DETECTION_STATUS_UNSPECIFIED"  "Unspecified/default value"
   "DETECTION_STATUS_OK"           "Detection running normally"
   "DETECTION_STATUS_NOT_READY"    "Detection system not ready"
   "DETECTION_STATUS_IPC_TIMEOUT"  "IPC communication timeout"
   "DETECTION_STATUS_INFER_FAILED" "Neural network inference failed"
   "DETECTION_STATUS_ERROR"        "Detection system error"

   ;; GPS fix types
   "JON_GUI_DATA_GPS_FIX_TYPE_UNSPECIFIED" "Unspecified/default value"
   "JON_GUI_DATA_GPS_FIX_TYPE_NONE"        "No fix"
   "JON_GUI_DATA_GPS_FIX_TYPE_1D"          "1D fix (time only)"
   "JON_GUI_DATA_GPS_FIX_TYPE_2D"          "2D fix (latitude/longitude)"
   "JON_GUI_DATA_GPS_FIX_TYPE_3D"          "3D fix (lat/lon/altitude)"
   "JON_GUI_DATA_GPS_FIX_TYPE_MANUAL"      "Manual position entry"

   ;; Compass calibration status
   "JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_UNSPECIFIED"      "Unspecified/default value"
   "JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_NOT_CALIBRATING"  "Not calibrating"
   "JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_SHORT" "Short calibration in progress"
   "JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_LONG"  "Long calibration in progress"
   "JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_FINISHED"         "Calibration finished"
   "JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_ERROR"            "Calibration error"

   ;; Compass units
   "JON_GUI_DATA_COMPASS_UNITS_UNSPECIFIED" "Unspecified/default value"
   "JON_GUI_DATA_COMPASS_UNITS_DEGREES"     "Degrees (0-360)"
   "JON_GUI_DATA_COMPASS_UNITS_MILS"        "NATO mils (0-6400)"
   "JON_GUI_DATA_COMPASS_UNITS_GRAD"        "Gradians (0-400)"
   "JON_GUI_DATA_COMPASS_UNITS_MRAD"        "Milliradians"

   ;; Video channels
   "JON_GUI_DATA_VIDEO_CHANNEL_UNSPECIFIED" "Unspecified/default value"
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT"        "Thermal/IR camera"
   "JON_GUI_DATA_VIDEO_CHANNEL_DAY"         "Day/visible camera"

   ;; State sources
   "JON_GUI_DATA_STATE_SOURCE_UNSPECIFIED"     "Unspecified/default value"
   "JON_GUI_DATA_STATE_SOURCE_DAY_PIPELINE"    "Day camera pipeline"
   "JON_GUI_DATA_STATE_SOURCE_HEAT_PIPELINE"   "Heat camera pipeline"
   "JON_GUI_DATA_STATE_SOURCE_SYSTEM"          "System-level state"

   ;; Client types
   "JON_GUI_DATA_CLIENT_TYPE_UNSPECIFIED"          "Unspecified/default value"
   "JON_GUI_DATA_CLIENT_TYPE_INTERNAL_CV"          "Internal CV module"
   "JON_GUI_DATA_CLIENT_TYPE_LOCAL_NETWORK"        "Local network client"
   "JON_GUI_DATA_CLIENT_TYPE_CERTIFICATE_PROTECTED" "Certificate-authenticated client"
   "JON_GUI_DATA_CLIENT_TYPE_LIRA"                 "LIRA integration client"

   ;; Client apps
   "JON_GUI_DATA_CLIENT_APP_UNSPECIFIED"     "Unspecified/default value"
   "JON_GUI_DATA_CLIENT_APP_BROWSER_UI"      "Browser-based UI"
   "JON_GUI_DATA_CLIENT_APP_BROWSER_MAP"     "Browser-based map view"
   "JON_GUI_DATA_CLIENT_APP_DESKTOP_NATIVE"  "Native desktop application"
   "JON_GUI_DATA_CLIENT_APP_MOBILE_NATIVE"   "Native mobile application"

   ;; Rotary directions
   "JON_GUI_DATA_ROTARY_DIRECTION_UNSPECIFIED"       "Unspecified/default value"
   "JON_GUI_DATA_ROTARY_DIRECTION_CLOCKWISE"         "Clockwise"
   "JON_GUI_DATA_ROTARY_DIRECTION_COUNTER_CLOCKWISE" "Counter-clockwise"

   ;; Rotary modes
   "JON_GUI_DATA_ROTARY_MODE_UNSPECIFIED"    "Unspecified/default value"
   "JON_GUI_DATA_ROTARY_MODE_INITIALIZATION" "Platform initializing"
   "JON_GUI_DATA_ROTARY_MODE_SPEED"          "Speed control mode"
   "JON_GUI_DATA_ROTARY_MODE_POSITION"       "Position control mode"
   "JON_GUI_DATA_ROTARY_MODE_STABILIZATION"  "Gyro-stabilized mode"
   "JON_GUI_DATA_ROTARY_MODE_TARGETING"      "Target tracking mode"
   "JON_GUI_DATA_ROTARY_MODE_VIDEO_TRACKER"  "Video tracker following mode"

   ;; Ext battery status
   "JON_GUI_DATA_EXT_BAT_STATUS_UNSPECIFIED" "Unspecified/default value"
   "JON_GUI_DATA_EXT_BAT_STATUS_CHARGING"    "Battery charging"
   "JON_GUI_DATA_EXT_BAT_STATUS_DISCHARGING" "Battery discharging"
   "JON_GUI_DATA_EXT_BAT_STATUS_BALANCING"   "Cell balancing"

   ;; Heat AGC modes
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_UNSPECIFIED" "Unspecified/default value"
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_1"           "AGC mode 1"
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_2"           "AGC mode 2"
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_3"           "AGC mode 3"

   ;; Heat filters
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_UNSPECIFIED"  "Unspecified/default value"
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_WHITE"    "Hot white polarity"
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_BLACK"    "Hot black polarity"
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA"        "Sepia color mapping"
   "JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA_INVERSE" "Inverted sepia color mapping"

   ;; GPS units
   "JON_GUI_DATA_GPS_UNITS_UNSPECIFIED"             "Unspecified/default value"
   "JON_GUI_DATA_GPS_UNITS_DECIMAL_DEGREES"         "Decimal degrees (DD)"
   "JON_GUI_DATA_GPS_UNITS_DEGREES_MINUTES_SECONDS" "Degrees/minutes/seconds (DMS)"
   "JON_GUI_DATA_GPS_UNITS_DEGREES_DECIMAL_MINUTES" "Degrees/decimal minutes (DDM)"

   ;; FX modes day
   "JON_GUI_DATA_FX_MODE_DAY_DEFAULT" "Default processing"
   "JON_GUI_DATA_FX_MODE_DAY_A"      "FX preset A"
   "JON_GUI_DATA_FX_MODE_DAY_B"      "FX preset B"
   "JON_GUI_DATA_FX_MODE_DAY_C"      "FX preset C"
   "JON_GUI_DATA_FX_MODE_DAY_D"      "FX preset D"
   "JON_GUI_DATA_FX_MODE_DAY_E"      "FX preset E"
   "JON_GUI_DATA_FX_MODE_DAY_F"      "FX preset F"

   ;; FX modes heat
   "JON_GUI_DATA_FX_MODE_HEAT_DEFAULT" "Default processing"
   "JON_GUI_DATA_FX_MODE_HEAT_A"      "FX preset A"
   "JON_GUI_DATA_FX_MODE_HEAT_B"      "FX preset B"
   "JON_GUI_DATA_FX_MODE_HEAT_C"      "FX preset C"
   "JON_GUI_DATA_FX_MODE_HEAT_D"      "FX preset D"
   "JON_GUI_DATA_FX_MODE_HEAT_E"      "FX preset E"
   "JON_GUI_DATA_FX_MODE_HEAT_F"      "FX preset F"

   ;; OSD screens
   "JON_GUI_DATA_REC_OSD_SCREEN_UNSPECIFIED"         "Unspecified/default value"
   "JON_GUI_DATA_REC_OSD_SCREEN_MAIN"                "Main interface screen"
   "JON_GUI_DATA_REC_OSD_SCREEN_LRF_MEASURE"         "Laser rangefinder measurement screen"
   "JON_GUI_DATA_REC_OSD_SCREEN_LRF_RESULT"          "Laser rangefinder results screen"
   "JON_GUI_DATA_REC_OSD_SCREEN_LRF_RESULT_SIMPLIFIED" "Simplified rangefinder results"

   ;; Localizations
   "JON_GUI_DATA_SYSTEM_LOCALIZATION_UNSPECIFIED" "Unspecified/default value"
   "JON_GUI_DATA_SYSTEM_LOCALIZATION_EN"          "English"
   "JON_GUI_DATA_SYSTEM_LOCALIZATION_UA"          "Ukrainian"
   "JON_GUI_DATA_SYSTEM_LOCALIZATION_AR"          "Arabic"
   "JON_GUI_DATA_SYSTEM_LOCALIZATION_CS"          "Czech"

   ;; LRF scan modes
   "JON_GUI_DATA_LRF_SCAN_MODE_UNSPECIFIED"       "Unspecified/default value"
   "JON_GUI_DATA_LRF_SCAN_MODE_1_HZ_CONTINUOUS"   "1 Hz continuous scanning"
   "JON_GUI_DATA_LRF_SCAN_MODE_4_HZ_CONTINUOUS"   "4 Hz continuous scanning"
   "JON_GUI_DATA_LRF_SCAN_MODE_10_HZ_CONTINUOUS"  "10 Hz continuous scanning"
   "JON_GUI_DATA_LRF_SCAN_MODE_20_HZ_CONTINUOUS"  "20 Hz continuous scanning"
   "JON_GUI_DATA_LRF_SCAN_MODE_100_HZ_CONTINUOUS" "100 Hz continuous scanning"
   "JON_GUI_DATA_LRF_SCAN_MODE_200_HZ_CONTINUOUS" "200 Hz continuous scanning"

   ;; Laser pointer modes
   "JON_GUI_DATA_LRF_LASER_POINTER_MODE_UNSPECIFIED" "Unspecified/default value"
   "JON_GUI_DATA_LRF_LASER_POINTER_MODE_OFF"         "Laser pointer off"
   "JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_1"        "Laser pointer mode 1"
   "JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_2"        "Laser pointer mode 2"

   ;; Time formats
   "JON_GUI_DATA_TIME_FORMAT_UNSPECIFIED"  "Unspecified/default value"
   "JON_GUI_DATA_TIME_FORMAT_H_M_S"       "Hours:Minutes:Seconds"
   "JON_GUI_DATA_TIME_FORMAT_Y_m_D_H_M_S" "Year/Month/Day Hours:Minutes:Seconds"})

(defn add-enum-value-descriptions [enum]
  (update enum :values
    (fn [values]
      (mapv (fn [val]
              (if (or (seq (:description val))
                      (and (:description val) (not= (:description val) "") (not= (:description val) "-")))
                val
                (if-let [desc (get manual-enum-descriptions (:name val))]
                  (assoc val :description desc)
                  val)))
            values))))

;; ============================================================================
;; Apply all fixes
;; ============================================================================

(defn fix-message [msg-id msg]
  (let [;; Fix interaction references
        msg (update msg :interaction fix-interaction-refs)
        ;; Fix interaction completeness (add missing :ui-pattern/:feedback)
        msg (if (:interaction msg)
              (update msg :interaction (partial fix-interaction-completeness msg-id))
              msg)
        ;; Fix fields
        msg (update msg :fields
              (fn [fields]
                (mapv (fn [field]
                        (-> field
                            (->> (fix-field-semantic-type msg-id))
                            (maybe-add-description msg-id)))
                      fields)))]
    msg))

(println "\nApplying fixes...")

(def fixed-db
  (-> db
      ;; Fix messages
      (update :messages
        (fn [messages]
          (into {}
            (map (fn [[msg-id msg]]
                   [msg-id (fix-message msg-id msg)])
                 messages))))
      ;; Fix enums
      (update :enums
        (fn [enums]
          (into {}
            (map (fn [[enum-id enum]]
                   [enum-id (add-enum-value-descriptions enum)])
                 enums))))))

;; ============================================================================
;; Save and report
;; ============================================================================

(println "Saving fixed database to:" db-path)
(spit db-path (pr-str fixed-db))

;; Count what changed
(let [orig-msgs (vals (:messages db))
      fixed-msgs (vals (:messages fixed-db))
      orig-fields (mapcat :fields orig-msgs)
      fixed-fields (mapcat :fields fixed-msgs)
      new-descs (count (filter (fn [[o f]]
                                 (and (not (seq (:description o)))
                                      (seq (:description f))))
                               (map vector orig-fields fixed-fields)))
      orig-enum-vals (mapcat #(:values (val %)) (:enums db))
      fixed-enum-vals (mapcat #(:values (val %)) (:enums fixed-db))
      new-enum-descs (count (filter (fn [[o f]]
                                      (and (or (nil? (:description o)) (= "" (:description o)) (= "-" (:description o)))
                                           (seq (:description f))))
                                    (map vector orig-enum-vals fixed-enum-vals)))]
  (println)
  (println "Results:")
  (println "  Field descriptions added:" new-descs)
  (println "  Enum value descriptions added:" new-enum-descs)
  (println)
  (println "Done! Run lint to verify:  bb docs/.protodoc/scripts/proto-lint.clj docs/.protodoc/proto-db.edn"))
