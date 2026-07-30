(ns lvgl-codegen.normalize
  "Proto3 default-value normalization for roundtrip equality.
   Proto3 does NOT serialize default values (0, \"\", false, enum-0, empty
   collections). After roundtrip, the deserialized side may have different keys
   than the source. This module provides three strategies:
   A) Canonical: roundtrip both sides through proto serialization.
   B) Strip: recursively remove proto3 default-valued keys from both sides.
   C) Schema: fill missing optional fields with proto3 defaults via Malli.

   Also provides sanitize-for-proto to clean Malli-generated data before
   serialization (strip nil optional fields that pronto rejects)."
  (:require [com.rpl.specter :as s]
            [lvgl-codegen.proto-schema :as proto-schema]
            [lvgl-codegen.proto-ser :as proto-ser]
            [malli.core :as m]
            [malli.transform :as mt]))

(set! *warn-on-reflection* true)

;; -- Specter recursive navigator --
(def ^:private all-nested-maps
  "Navigate to every map at any depth in a nested map/vector structure.
   Uses cond-path so scalars (keywords, numbers, strings) are not navigated
   into — prevents MAP-VALS from being applied to non-map leaves."
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (s/recursive-path
   []
   p
   (s/cond-path map? (s/stay-then-continue [s/MAP-VALS p]) vector? [s/ALL p])))

;; -- Proto3 default values for each field type --
(def ^:private enum-zero-values
  "Enum values that map to proto3 value 0 (not serialized on wire)."
  #{:WIDGET_OBJ :PROP_BG_COLOR :FLEX_FLOW_NONE :FLEX_ALIGN_START :GRID_ALIGN_START
    :TEXT_ALIGN_AUTO :TEXT_DECOR_NONE :BLEND_MODE_NORMAL :BASE_DIR_LTR :GRAD_DIR_NONE
    :DIR_NONE :ALIGN_DEFAULT :BORDER_SIDE_NONE :LABEL_LONG_MODE_WRAP :BAR_MODE_NORMAL
    :ARC_MODE_NORMAL :ROLLER_MODE_NORMAL :SCALE_MODE_HORIZONTAL_TOP :CHART_TYPE_NONE
    :CHART_AXIS_PRIMARY_Y :SUBJECT_INT :TRIGGER_CLICKED :COMPARE_EQ})

(defn- proto3-default?
  "Returns true if value is a proto3 default that would not be serialized."
  [value]
  (or (nil? value)
      (and (number? value) (zero? value))
      (and (string? value) (empty? value))
      (false? value)
      (and (vector? value) (empty? value))
      (and (map? value) (empty? value))
      (contains? enum-zero-values value)))

(defn strip-defaults
  "Recursively remove keys where the value is a proto3 default.
   Walks nested maps and vectors of maps."
  [data]
  (cond (map? data) (let [stripped (into {}
                                         (comp (remove (fn [[_k v]] (proto3-default? v)))
                                               (map (fn [[k v]] [k (strip-defaults v)])))
                                         data)]
                      (when (seq stripped) stripped))
        (vector? data) (mapv strip-defaults data)
        :else data))

;; -- Pre-serialization sanitization --
(defn sanitize-for-proto
  "Recursively clean a proto IR map for safe pronto serialization.
   Removes nil-valued keys (pronto rejects nil for repeated/map fields)
   and empty maps/vectors that would be no-ops in proto3."
  [data]
  (s/setval [all-nested-maps s/MAP-VALS nil?] s/NONE data))

;; -- Schema-driven normalization (Strategy C) --
(def normalize-with-schema
  "Fill proto3 default values for all missing optional fields using the Malli
   schema. Recursive — walks [:ref ::widget-node] automatically.
   Alternative to canonicalize (no serialization roundtrip needed)."
  (m/decoder proto-schema/screen
             (mt/default-value-transformer {:malli.transform/add-optional-keys true})))

;; -- Canonical roundtrip normalization --
(defn canonicalize
  "Roundtrip a screen IR map through proto serialization for canonical form.
   Sanitizes input first to handle nil optional fields from generators.
   Both serialization and deserialization normalize the key set to match
   what proto3 actually preserves."
  [screen-ir]
  (-> screen-ir
      sanitize-for-proto
      proto-ser/ir->bytes
      proto-ser/bytes->screen))

(defn normalize-for-comparison
  "Prepare source and deserialized maps for equality comparison.
   Returns [normalized-source normalized-deser] pair.
   Uses canonical roundtrip (Strategy A) — both sides go through proto."
  [source-ir deser-ir]
  [(canonicalize source-ir) (canonicalize deser-ir)])

;; -- Function schema registrations --
;; proto3-default? and strip-defaults are recursive walkers over arbitrary
;; proto-IR EDN. proto3-default? explicitly checks (nil? value) first, and
;; strip-defaults recurses through its own (instrumented) var onto every map
;; value — including nil, scalars, and vectors mid-recursion. Their argument is
;; genuinely any-incl-nil, so :any is the honest schema (tightening it would
;; make thrower-mode instrumentation reject legitimate recursive calls).
(m/=> proto3-default? [:=> [:cat :any] :boolean])

(m/=> strip-defaults [:=> [:cat :any] :any])

;; normalize-with-schema receives a CONFORMING screen IR (validated against
;; proto-schema/screen by the roundtrip/schema tests). sanitize-for-proto,
;; canonicalize and normalize-for-comparison run on PRE-conforming maps —
;; sanitizing/canonicalizing/reconciling shape is their JOB, and pronto-deser
;; output need not strictly match the schema — so their honest arg is map?
;; (a non-map still fails: the instrumentation canary is preserved).
(m/=> sanitize-for-proto [:=> [:cat map?] map?])

(m/=> normalize-with-schema [:=> [:cat proto-schema/screen] proto-schema/screen])

(m/=> canonicalize [:=> [:cat map?] proto-schema/screen])

(m/=> normalize-for-comparison [:=> [:cat map? map?] [:tuple :map :map]])