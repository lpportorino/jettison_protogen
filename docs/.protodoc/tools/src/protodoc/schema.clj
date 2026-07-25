(ns protodoc.schema
  "Malli schemas for proto documentation database."
  (:require [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]))

;; Custom generators for realistic test data
(def proto-generators
  {:field-name (gen/elements ["value" "mode" "level" "position" "enabled"
                              "target" "speed" "timeout" "status" "count"
                              "offset" "limit" "state" "type" "name"])
   :message-name (gen/elements ["SetIris" "SetZoom" "SetFocus" "GetStatus"
                                "Enable" "Disable" "Configure" "Reset"
                                "Start" "Stop" "Query" "Update" "Root"])
   :package-name (gen/elements ["cmd.DayCamera" "cmd.HeatCamera" "cmd.Rotary"
                                "cmd.Lrf" "cmd.System" "cmd.GPS" "cmd.Compass"
                                "ser" "cmd"])
   :proto-file (gen/elements ["jon_shared_cmd.proto"
                              "jon_shared_cmd_day_camera.proto"
                              "jon_shared_cmd_heat_camera.proto"
                              "jon_shared_data_types.proto"])})

;; Constraint schema for buf.validate rules
;; Note: Not closed to allow future buf.validate extensions
(def Constraints
  [:map
   [:gt {:optional true} number?]
   [:gte {:optional true} number?]
   [:lt {:optional true} number?]
   [:lte {:optional true} number?]
   [:min-len {:optional true} int?]
   [:max-len {:optional true} int?]
   [:min-items {:optional true} int?]
   [:max-items {:optional true} int?]
   [:defined-only {:optional true} boolean?]
   [:not-in {:optional true} [:vector int?]]
   [:in {:optional true} [:vector :string]]
   [:required {:optional true} boolean?]
   [:pattern {:optional true} :string]
   [:email {:optional true} boolean?]
   [:example {:optional true} [:vector number?]]])

;; ============================================================================
;; Platform-Agnostic Interaction Metadata
;; ============================================================================

;; Message category - functional classification
(def MessageCategory
  [:enum :sensor :actuator :settings :status :lifecycle :diagnostic])

;; UI Pattern - hierarchical naming convention
;; Atomic: single word (toggle, slider, stepper)
;; Molecular: hyphenated pair (slider-with-steppers)
;; Composite: descriptive (slider-with-presets, directional-mover)
(def UIPattern
  [:enum
   ;; Atomic patterns (single control)
   :toggle              ; Binary on/off
   :action-button       ; Fire-and-forget click
   :cyclic-button       ; Cycles through enum values
   :slider              ; Continuous range (standalone)
   :stepper             ; ± discrete steps
   :indicator           ; Read-only display
   :enum-picker         ; Select from enum values
   :number-input        ; Direct numeric entry for a single min-only/bounded value
   :roi-selection       ; Region of interest on video

   ;; Molecular patterns (2-3 controls)
   :slider-with-steppers    ; Slider + ± buttons (CLAHE)
   :press-accelerating      ; Hold with ramp-up (DDE)

   ;; Composite patterns (4+ controls)
   :composite               ; Multi-field command form (per-field inputs + commit)
   :slider-with-presets     ; Slider + preset buttons + auto (Iris)
   :directional-mover       ; Step size + directional buttons (Zoom)
   :tabbed-config           ; Multiple tabs with controls (Alerts)
   :state-machine-menu])    ; Multi-mode with transitions (POI)

;; Feedback behavior - orthogonal to UI pattern
(def FeedbackType
  [:enum
   :fire-and-forget     ; No confirmation expected
   :pending-timeout     ; Set pending, clear on match or 2s timeout
   :poll-confirm        ; Poll every 100ms for state change
   :optimistic-visual   ; Visual update during drag, revert on timeout
   :dual-feedback])     ; Separate pending + active states (photo)

;; Semantic type - value classification for display formatting
(def SemanticType
  [:enum
   ;; Numeric types
   :normalized          ; 0.0-1.0 representing percentage (iris, zoom)
   :angle               ; Degrees (azimuth, elevation, FOV)
   :percentage          ; 0-100 explicit percentage
   :coordinate-geo      ; Lat/lon with high precision
   :coordinate-viewport ; -1 to 1 normalized viewport position
   :distance            ; Meters
   :temperature         ; Celsius
   :speed               ; Normalized -1 to 1 (rotation speed)
   :voltage             ; Volts
   :current             ; Amps
   :power               ; Watts
   :duration            ; Time duration
   :count               ; Integer count (satellites, level 0-100)
   :timestamp           ; Monotonic or PTS time
   :identifier          ; ID/index value

   ;; Display types
   :cardinal            ; N/NE/E/SE/S/SW/W/NW from angle
   :enum-label          ; Enum with localized display label
   :toggle-state        ; Boolean with on/off semantics
   :raw])               ; No transformation

;; Field-level interaction metadata
(def FieldMeta
  [:map
   [:semantic-type {:optional true} SemanticType]
   [:unit {:optional true} :string]              ; "°", "%", "V", "A", "W", "°C", "m"
   [:precision {:optional true} nat-int?]        ; Decimal places (0-6)
   [:display-format {:optional true} :string]    ; "{value * 100}%" or "{value}°"
   [:presets {:optional true} [:vector [:or number? :string]]]])  ; Includes "auto"

;; Message-level interaction metadata
(def InteractionMeta
  [:map
   [:purpose {:optional true} :string]
   [:category {:optional true} MessageCategory]
   [:ui-pattern {:optional true} UIPattern]
   [:feedback {:optional true} FeedbackType]
   [:timeout-ms {:optional true} pos-int?]       ; Default 2000ms
   [:related-state {:optional true} [:vector :string]]
   [:related-commands {:optional true} [:vector :string]]
   [:preconditions {:optional true} [:vector :string]]
   [:notes {:optional true} :string]])

;; Proto field types
(def FieldType
  [:enum :uint32 :int32 :uint64 :int64 :double :float
   :bool :string :bytes :enum :message])

;; Field within a message
(def Field
  [:map
   [:number pos-int?]
   [:name :string]
   [:type FieldType]
   [:type-ref {:optional true} :string]      ; For enum/message refs
   [:repeated {:optional true} boolean?]
   [:constraints {:optional true} Constraints]
   [:description {:optional true} :string]   ; User documentation
   [:interaction {:optional true} FieldMeta]]) ; Platform-agnostic display metadata

;; Oneof group
(def Oneof
  [:map
   [:name :string]
   [:required boolean?]
   [:fields [:vector pos-int?]]])            ; Field numbers in this oneof

;; Proto message
(def Message
  [:map
   [:id :string]                             ; e.g., "cmd.DayCamera.SetIris"
   [:name :string]                           ; e.g., "SetIris"
   [:package :string]                        ; e.g., "cmd.DayCamera"
   [:source :string]                         ; e.g., "jon_shared_cmd_day_camera.proto"
   [:description {:optional true} :string]   ; User documentation (markdown)
   [:fields [:vector Field]]
   [:oneofs {:optional true} [:vector Oneof]]
   [:interaction {:optional true} InteractionMeta]]) ; Platform-agnostic UI/behavior metadata

;; Enum value
(def EnumValue
  [:map
   [:number int?]                            ; Can be negative
   [:name :string]
   [:description {:optional true} :string]]) ; User documentation

;; Proto enum
(def ProtoEnum
  [:map
   [:id :string]                             ; e.g., "ser.JonGuiDataClientType"
   [:name :string]                           ; e.g., "JonGuiDataClientType"
   [:package :string]                        ; e.g., "ser"
   [:source :string]
   [:description {:optional true} :string]   ; User documentation (markdown)
   [:values [:vector EnumValue]]])

;; Search index entry
(def SearchIndex
  [:map-of :string [:vector :string]])       ; keyword -> [message-ids]

;; Complete database
(def ProtoDb
  [:map
   [:messages [:map-of :string Message]]
   [:enums [:map-of :string ProtoEnum]]
   [:search-index SearchIndex]])

;; Validation functions

(defn validate
  "Validate data against schema, returns nil if valid or error map."
  [schema data]
  (when-not (m/validate schema data)
    (me/humanize (m/explain schema data))))

(defn validate!
  "Validate data against schema, throws on failure."
  [schema data]
  (when-let [errors (validate schema data)]
    (throw (ex-info "Schema validation failed"
                    {:errors errors
                     :data data}))))

(defn valid?
  "Check if data is valid against schema."
  [schema data]
  (m/validate schema data))

;; Generator functions for testing

(defn generate
  "Generate random data conforming to schema."
  ([schema] (mg/generate schema))
  ([schema opts] (mg/generate schema opts)))

(defn generator
  "Get a test.check generator for schema."
  ([schema] (mg/generator schema))
  ([schema opts] (mg/generator schema opts)))

;; Schema with custom generators for realistic test data
(def Field-gen
  [:map
   [:number [:int {:min 1 :max 100 :gen/gen (gen/choose 1 50)}]]
   [:name [:string {:gen/gen (:field-name proto-generators)}]]
   [:type FieldType]
   [:type-ref {:optional true} :string]
   [:repeated {:optional true} boolean?]
   [:constraints {:optional true} Constraints]
   [:description {:optional true} [:string {:min 0 :max 200}]]])

(def Message-gen
  [:map
   [:id :string]
   [:name [:string {:gen/gen (:message-name proto-generators)}]]
   [:package [:string {:gen/gen (:package-name proto-generators)}]]
   [:source [:string {:gen/gen (:proto-file proto-generators)}]]
   [:description {:optional true} [:string {:min 0 :max 500}]]
   [:fields [:vector {:min 1 :max 8} Field-gen]]
   [:oneofs {:optional true} [:vector {:max 2} Oneof]]])

(def ProtoEnum-gen
  [:map
   [:id :string]
   [:name [:string {:gen/gen (gen/elements ["Mode" "Status" "Type" "State"])}]]
   [:package [:string {:gen/gen (:package-name proto-generators)}]]
   [:source [:string {:gen/gen (:proto-file proto-generators)}]]
   [:description {:optional true} [:string {:min 0 :max 200}]]
   [:values [:vector {:min 1 :max 6}
             [:map
              [:number [:int {:min 0 :max 10}]]
              [:name [:string {:gen/gen (gen/elements ["UNSPECIFIED" "VALUE_1" "VALUE_2" "MODE_A" "MODE_B"])}]]
              [:description {:optional true} [:string {:min 0 :max 100}]]]]]])
