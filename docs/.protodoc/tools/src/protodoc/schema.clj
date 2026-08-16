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

;; Display unit — the CLOSED spelling vocabulary for `:unit`.
;;
;; WHY CLOSED. This key names the label a consumer prints beside a value, so two
;; spellings of one quantity are two labels on one screen. As a bare `:string`
;; it drifted exactly that way, and nothing could see it: the per-field rule in
;; `protodoc.lint` compares a field against ITSELF and the group rule fires only
;; where the corpus can arbitrate its own split, so a quantity spelled two ways
;; across fields that share neither a description nor a display-format was
;; unreachable by both. An enum is reachable by construction — every spelling is
;; judged, whether or not any other field agrees with it.
;;
;; ONE SPELLING PER QUANTITY, and the SURVIVOR IS THE ONE A READER SEES. That is
;; this repository's own precedent rather than a rule invented here: the
;; `meters` -> `m` hand-fix resolved toward what the field PRINTS, and the rules
;; that followed it resolved `degrees` -> `°` and `pixels` -> `px` on the same
;; ground. Counting occurrences is explicitly NOT the tie-breaker, and for the
;; SI time family it gives the opposite answer — the full words outnumbered the
;; symbols, while every display-format in that family printed a symbol.
;;
;; A QUANTITY WITH NO ACCEPTED SYMBOL KEEPS ITS WORD. `minutes` and `months` are
;; a matched pair on the clock-stepping commands; `min` is an accepted symbol
;; and `month` has none, so abbreviating half the pair would reintroduce the
;; inconsistency this vocabulary exists to remove. They are single spellings of
;; distinct quantities, which is all closing the type asks for.
;;
;; `μs` IS U+03BC GREEK SMALL LETTER MU, never U+00B5 MICRO SIGN. The two are
;; visually identical and are DIFFERENT STRINGS, so an author who types the
;; other one gets a rejection whose expected-value list looks like the value
;; they just wrote. That confusion is the reason to close the type rather than
;; an argument against it: as a bare string the wrong codepoint would have
;; entered the corpus silently and reached every consumer of the manifests.
(def Unit
  [:enum
   ;; Angle and temperature
   "°" "°C"
   ;; Dimensionless ratios and ordinals
   "%" "normalized" "NDC" "x" "index"
   ;; Length
   "m"
   ;; Time
   "ns" "μs" "ms" "s" "minutes" "months"
   ;; Electrical
   "V" "A" "mA" "W" "mW"
   ;; Imaging
   "px" "fps"])

;; Field-level interaction metadata
(def FieldMeta
  [:map
   [:semantic-type {:optional true} SemanticType]
   [:unit {:optional true} Unit]
   [:precision {:optional true} nat-int?]        ; Decimal places (0-6)
   [:display-format {:optional true} :string]    ; "{value * 100}%" or "{value}°"
   [:presets {:optional true} [:vector [:or number? :string]]]])  ; Includes "auto"

;; A reference to a proto message or enum, by its full id: "ser.JonGuiDataGps",
;; "cmd.DayCamera.SetIris". Every id carries at least one dot, because
;; parse/build-message-id joins the package, the nesting path and the name.
(def ^:private message-id-fragment "[A-Za-z_][A-Za-z0-9_]*")

(def MessageRef
  [:re {:error/message "not a proto message id (expected <package>.<Name>)"}
   (re-pattern (str "^" message-id-fragment "(\\." message-id-fragment ")+$"))])

;; A reference from a command to the state that reflects it. TWO GRAINS, told
;; apart by the separator:
;;
;;   "ser.JonGuiDataCameraDay"          — the whole message reflects the command
;;   "ser.JonGuiDataCameraDay#iris_pos" — one named FIELD reflects it
;;
;; The field grain exists because a consumer asking "which state does this
;; command move" needs a field to match on — clearing a :pending-timeout, or
;; drawing the reverse index — and a message-grained pointer makes it guess
;; among that message's fields. The message grain stays legal because it is the
;; honest answer for a oneof routing container and for a whole-message refresh,
;; where no single field is the subject.
;;
;; '#' RATHER THAN A DOT, and neither reason is style. A dotted "Outer.field" is
;; ambiguous with a NESTED MESSAGE id — build-message-id joins nesting with dots,
;; so "ser.Foo.bar" is a legal message id and a resolver splitting at the last
;; dot cannot tell which was meant. '#' is not a legal proto identifier
;; character, so the split is total. It is also Obsidian's in-page anchor
;; separator, so the rendered wikilink [[proto/ser.Foo#bar]] still resolves to a
;; real page; a dotted spelling would render a link to a page that does not
;; exist, on every field-grained entry.
;;
;; WHAT THIS SHAPE CANNOT SAY, stated because it reads like an omission: a
;; message-grained entry that is deliberate and one nobody has narrowed yet are
;; spelled identically. The lint rules below check that a named field EXISTS,
;; never that a pointer is as narrow as it could be.
(def StateRef
  [:re {:error/message "not a state reference (expected <package>.<Name> or <package>.<Name>#<field>)"}
   (re-pattern (str "^" message-id-fragment "(\\." message-id-fragment ")+"
                    "(#" message-id-fragment ")?$"))])

;; Message-level interaction metadata
(def InteractionMeta
  [:map
   [:purpose {:optional true} :string]
   [:category {:optional true} MessageCategory]
   [:ui-pattern {:optional true} UIPattern]
   [:feedback {:optional true} FeedbackType]
   [:timeout-ms {:optional true} pos-int?]       ; Default 2000ms
   [:related-state {:optional true} [:vector StateRef]]
   [:related-commands {:optional true} [:vector MessageRef]]
   [:preconditions {:optional true} [:vector :string]]
   [:notes {:optional true} :string]])

(defn split-state-ref
  "Split a `:related-state` reference into [message-id field-name].

   ONE HOME for the grammar StateRef declares, because three call sites need it
   and each getting it slightly wrong is how a vocabulary rots: the lint rules
   resolve both halves, and the manifest builder needs the MESSAGE half alone to
   name a doc file — `proto/ser.Foo#bar.md` is not a path.

   `field-name` is nil for a whole-message reference. The split is on the FIRST
   '#'; a second one cannot occur in a StateRef-conforming value, and a value
   that does not conform has already failed `validate!` before any caller here
   sees it."
  [ref-str]
  (let [idx (.indexOf ^String ref-str "#")]
    (if (neg? idx)
      [ref-str nil]
      [(subs ref-str 0 idx) (subs ref-str (inc idx))])))

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
