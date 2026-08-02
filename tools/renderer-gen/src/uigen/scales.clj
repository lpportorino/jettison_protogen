(ns uigen.scales
  "The single home for `semantic-type → wire-scale`: the fixed-point factor
   mapping a proto numeric value to the INTEGER the controls.wasm ABI and the
   slider widgets ride (LVGL values are int-only). R5b carries the wire-scale on
   each CmdSpec FieldPatch, and the renderer applies it in the direction that
   RECOVERS the proto value: the DOUBLE_LE/FLOAT_LE encodings DIVIDE the live
   int by it (proto value = ABI int ÷ scale, the inverse of this table's own
   definition), while PADDED_VARINT multiplies — which is identity in practice,
   because an integer leaf's ABI int is already in the proto's own unit and
   every int leaf in this vocabulary carries a scale of 1. So the cmd.* bytes
   controls.wasm relays via `host_command` are already in proto units. Display units
   (the `%`/`°` shown in labels, precision, display-format) are a SEPARATE
   emit-time concern read from the endpoint's :unit / :precision /
   :display-format — deliberately NOT this table.

   Scales are deliberate per-semantic-type choices, NOT derivable from the
   proto `precision` field (which drifts within a semantic-type — two
   `:angle` fields at precision 0 vs 2, `:normalized` at 0 vs 2). They are
   overridable per-command later via the node-config tuning layer.
   Resolved per research agent abef6d82 (decision 1)."
  (:require [asgard.schema :as s]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def wire-scales
  "semantic-type keyword → integer wire-scale (proto value × scale = ABI int).
   Covers every NUMERIC semantic-type present in proto-db; non-numeric kinds
   (:enum-label, :toggle-state) are intentionally absent (scaling them is a
   bug — `scale-for` throws)."
  {:normalized 1000 ; [0,1]    → per-mille [0,1000]
   :angle 100 ; degrees  → centi-degrees (0.01° resolution)
   :coordinate-geo 10000000 ; degrees  → 1e7 scaled-degrees (GPS convention)
   :distance 100 ; metres   → centimetres
   :duration 1000 ; seconds  → milliseconds
   :temperature 10 ; degrees  → deci-degrees
   :count 1 ; integer counts — no scaling
   :percentage 1 ; already an integer percent [0,100]
   :raw 1 ; unprocessed integer
   :timestamp 1}) ; integer epoch — no scaling

(defn scale-for
  "Wire-scale for `semantic-type` (string or keyword). Throws for a
   non-numeric or unknown type: scaling an enum/toggle is a bug, and a NEW
   numeric semantic-type must be added to `wire-scales` deliberately rather
   than silently mis-scaled (see-something-say-something)."
  [semantic-type]
  (let [k (keyword semantic-type)]
    (or (get wire-scales k)
        (throw (ex-info
                (str "uigen.scales: no wire-scale for semantic-type " (pr-str semantic-type)
                     " — add it to wire-scales deliberately"
                     " (scale is NOT derivable from proto precision)")
                {:semantic-type semantic-type :known (vec (sort (keys wire-scales)))})))))
(m/=> scale-for [:=> [:cat [:or :keyword s/ne-string]] :int])

(defn numeric-semantic-type?
  "True when `semantic-type` has a wire-scale (i.e. is a scalable numeric).
   Accepts nil (a field with no semantic-type) → false, so it is a safe GUARD
   before `scale-for` (which throws on unmapped)."
  [semantic-type]
  (contains? wire-scales (keyword semantic-type)))
(m/=> numeric-semantic-type? [:=> [:cat [:maybe [:or :keyword s/ne-string]]] :boolean])

;; ── fraction-window fields ───────────────────────────────────────────────────
;; A field whose constraints are a dimensionless fraction window (⊆ [-1,1]) —
;; coordinate-geo NDC, normalized, or a nil-semantic-type fraction — can ride
;; the native per-mille integer ABI without inventing semantic metadata.
(def fraction-wire-scale
  "LVGL wire-scale for a nil-semantic-type fraction field (per-mille)."
  1000)

(defn fraction-window?
  "True when `constraints` bound the value in [lo,hi] within [-1,1] (lo<hi) — a
   dimensionless fraction window. Derived from the WINDOW, not a guessed
   semantic-type, so it covers coordinate-geo NDC + normalized + nil-st fractions
   uniformly without mutating metadata."
  [constraints]
  (let [{:keys [gte gt lte lt]} constraints
        lo (or gte gt)
        hi (or lte lt)]
    (boolean (and lo hi (<= -1 lo) (<= hi 1) (< lo hi)))))
(m/=> fraction-window? [:=> [:cat [:maybe [:map-of :keyword :any]]] :boolean])

(defn fraction-field?
  "True for a nil-semantic-type bounded double/float in a fraction window — a
   dimensionless fraction admissible as a slider at the per-mille scale, WITHOUT
   inventing a semantic-type (keeps scale-for's no-guess throw intact; this is a
   deliberate NEW admission, not a relaxation)."
  [field-type semantic-type constraints]
  (boolean (and (nil? semantic-type)
                (#{"double" "float"} field-type)
                (fraction-window? constraints))))
(m/=> fraction-field?
      [:=>
       [:cat [:maybe [:string {:min 1}]] [:maybe [:or :keyword s/ne-string]]
        [:maybe [:map-of :keyword :any]]] :boolean])

;; ── constraint → inclusive render bound (exclusive-bound correctness) ─────────
;; A widget's min/max is an INCLUSIVE raw value. buf.validate :lt/:gt are
;; EXCLUSIVE. For an INTEGER field, :lt N rejects N (the inclusive max is N-1);
;; :gt N rejects N (inclusive min is N+1). For a float/double the open interval
;; can't be represented by an int-stepped widget, so the bound value is used (the
;; standard convention; off-by-epsilon is invisible on a continuous control).
;; Using :lt directly as an integer max is an off-by-one that ships an
;; out-of-range value (e.g. zone_id :lt 595 → a selectable 595 the server rejects).
(def ^:private int-field-types
  "proto integer types whose exclusive :lt/:gt bound is off-by-one from inclusive."
  #{"int32" "uint32" "int64" "uint64"})

(defn constraint-max
  "Inclusive max-raw from `constraints` for a field of `field-type`, or nil when
   unbounded. :lte is already inclusive; an exclusive :lt on an integer field
   becomes (dec lt); on a float field the bound value is used as-is."
  [field-type constraints]
  (let [{:keys [lte lt]} constraints]
    (cond (some? lte) lte
          (and (some? lt) (int-field-types field-type)) (dec lt)
          (some? lt) lt
          :else nil)))
(m/=> constraint-max
      [:=> [:cat [:maybe [:or :keyword s/ne-string]] [:maybe [:map-of :keyword :any]]]
       [:maybe number?]])

(defn constraint-min
  "Inclusive min-raw from `constraints` for a field of `field-type`, or nil when
   unbounded. :gte is already inclusive; an exclusive :gt on an integer field
   becomes (inc gt); on a float field the bound value is used as-is."
  [field-type constraints]
  (let [{:keys [gte gt]} constraints]
    (cond (some? gte) gte
          (and (some? gt) (int-field-types field-type)) (inc gt)
          (some? gt) gt
          :else nil)))
(m/=> constraint-min
      [:=> [:cat [:maybe [:or :keyword s/ne-string]] [:maybe [:map-of :keyword :any]]]
       [:maybe number?]])