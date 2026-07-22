(ns lvgl-codegen.construct.schema
  "Hydrated IR (Level-2) Malli schemas for the LVGL factory — the closed,
   per-`:kind` constructs (`:enum` / `:struct` / `:setter`) that the enrichment
   passes produce and the emitters consume. Adapts `uroboros.construct.schema`
   to the LVGL domain; see `docs/lvgl-factory/03-FACTORY-DESIGN.md`.

   This is the shared leaf: every pass + emitter references these shapes, and
   none of them reference a pass (no cycle). The shapes are CLOSED — a stray key
   is a bug the boundary rejects, not silent data."
  (:require [malli.core :as m]))

(set! *warn-on-reflection* true)

(def enum-member
  "One enum member: C name + probe-resolved int (POC #1 is authoritative) +
   naming + the assign-once proto number (append-only, never renumbered).
   `:c-name`/`:resolved-int` are nil ONLY for proto-only SYNTHETIC members —
   no LVGL constant behind the pin (e.g. `FLEX_FLOW_NONE` = \"no flex layout\",
   special-cased by the renderer); their numbers still come from the registry."
  [:map {:closed true} [:c-name [:maybe [:string {:min 1}]]] [:resolved-int [:maybe :int]]
   [:bitmask? :boolean] [:clj-keyword :keyword] [:proto-name [:string {:min 1}]]
   [:proto-number [:int {:min 0}]]])

(def enum-construct
  "An `lv_*_t` enum typedef. LVGL enums are anonymous, paired to their typedef
   name (pass 2). `:cast-class` (pass 4 + O1) decides whether `renderer.c`
   direct-casts the proto int or routes it through a generated LUT."
  [:map {:closed true} [:kind [:= :enum]] [:typedef-name [:string {:min 1}]]
   [:anon? :boolean] [:cast-class [:enum :direct :lut]] [:proto-type [:string {:min 1}]]
   [:members [:vector {:min 1} enum-member]]])

(def struct-field
  "One struct field: C name/type + the emitted proto field name + assign-once
   number."
  [:map {:closed true} [:c-name [:string {:min 1}]] [:c-type [:string {:min 1}]]
   [:proto-type [:string {:min 1}]] [:proto-number [:int {:min 0}]]])

(def struct-construct
  "An `lv_*_t` struct (a style-prop carrier / descriptor) → a proto message."
  [:map {:closed true} [:kind [:= :struct]] [:c-name [:string {:min 1}]]
   [:proto-message [:string {:min 1}]] [:fields [:vector struct-field]]])

(def setter-construct
  "An `lv_<widget>_set_<prop>` / `lv_style_set_<prop>`, cross-linked (pass 5) to
   the enum it consumes (`:enum-ref` = that enum's `:typedef-name`, or nil)."
  [:map {:closed true} [:kind [:= :setter]] [:c-name [:string {:min 1}]]
   [:widget [:string {:min 1}]] [:prop [:string {:min 1}]]
   [:value-c-type [:string {:min 1}]] [:enum-ref [:maybe [:string {:min 1}]]]
   [:style-prop? :boolean]])

(def construct
  "Sum of the hydrated construct kinds, dispatched on `:kind` (the tagged-union
   shape `tight-schemas.md` § \"Sum types\" wants — an explicit `:kind` head)."
  [:multi {:dispatch :kind} [:enum enum-construct] [:struct struct-construct]
   [:setter setter-construct]])

(def ir "The whole hydrated IR — an ordered vector of constructs." [:vector construct])

(defn validate-ir!
  "Validate `constructs` against the IR schema. Returns the validated vector on
   success; throws `ex-info` carrying the Malli explanation on failure — the
   factory boundary fails loud (`fail-fast.md`), never emits from a bad IR.

   Takes `some?` (not the IR shape) deliberately: this IS the boundary validator,
   so a malformed value must reach it to be explained, not be pre-rejected."
  [constructs]
  (if (m/validate ir constructs)
    constructs
    (throw (ex-info "Invalid LVGL construct IR" {:explain (m/explain ir constructs)}))))
(m/=> validate-ir! [:=> [:cat some?] ir])