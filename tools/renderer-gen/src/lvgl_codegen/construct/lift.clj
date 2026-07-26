(ns lvgl-codegen.construct.lift
  "Lift POC #1's extracted enum EDN into hydrated `:enum` constructs
   (`docs/lvgl-factory/03-FACTORY-DESIGN.md`, build step 2). Runs the enum slice
   of the enrichment passes over the probe-resolved facts:

   - **pass 1 (lift)** — each `{typedef [{:name :value}]}` entry → an `:enum`
     construct (LVGL enums are anonymous typedefs, already paired to their name by
     the extractor — pass 2 is free here).
   - **pass 3 (values)** — `:resolved-int` straight from the authoritative probe.
   - **pass 4 (cast-class)** — `:direct` for the enums `renderer.c` direct-casts
     (parity-gated), `:lut` otherwise (the default per O1).
   - **pass 6 (name-map)** — `:proto-type` / `:proto-name` / `:clj-keyword` via
     camel-snake-kebab.
   - **pass 7 (numbering, provisional)** — `:proto-number` = `:resolved-int` as
     a placeholder; the AUTHORITATIVE numbers are stamped by
     `lvgl-codegen.construct.registry/apply-numbering` from the assign-once
     registry. For direct-cast enums the two coincide (the parity
     contract); for `:lut` enums only the registry knows the wire number.

   `:bitmask?` is not yet inferred (left `false`); it informs the LUT/cast policy,
   not the value mapping, so it is a later-pass refinement (pass 3's `(1<<n)`
   detection needs whole-enum context to avoid misflagging sequential members like
   `LV_ALIGN_TOP_LEFT=1`)."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [lvgl-codegen.construct.schema :as schema]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def ^:private direct-cast-typedefs
  "Enum typedefs `renderer.c` direct-casts (proto int == LVGL int). This set is
   the ONE home of that fact; `direct-cast-parity-violations` below is the
   guard that holds it, and `lvgl-codegen.lvgl-parity-test` proves the guard
   fires. Do not restate the set's SIZE anywhere — a copied count rots the next
   time the set grows.

   Stays private: the guard dispatches on each construct's `:cast-class`, which
   is derived from this set at lift time, so nothing outside needs the set
   itself."
  #{"lv_align_t" "lv_flex_align_t" "lv_grid_align_t" "lv_text_align_t" "lv_text_decor_t"
    "lv_border_side_t" "lv_dir_t" "lv_grad_dir_t" "lv_blend_mode_t" "lv_base_dir_t"
    "lv_label_long_mode_t" "lv_bar_mode_t" "lv_arc_mode_t" "lv_roller_mode_t"
    "lv_scale_mode_t" "lv_chart_type_t" "lv_chart_axis_t"})

(def proto-emitted-typedefs
  "The LVGL-mirror enum typedefs the proto carries today: the direct-cast set
   plus `lv_flex_flow_t` (decoupled via `renderer.c`'s `flex_flow_lut`, hence
   `:lut`). The factory emits + registers exactly this set; it grows as setter
   association (pass 5) lands. Our proto-only enums (WidgetType,
   StylePropertyType, …) are NOT LVGL mirrors and stay outside the factory."
  (conj direct-cast-typedefs "lv_flex_flow_t"))

(def authoring-only-typedefs
  "Enum typedefs surfaced ONLY as authoring keyword→int bindings (raw LVGL
   header values, OR-able bitmasks): NO proto enum is emitted and NO registry
   numbering applies. The wire carries the OR'd uint32, which `renderer.c`
   direct-casts (`lv_obj_add_flag` / `lv_obj_add_state`). These carry raw
   `:resolved-int` values all the way to emission, which makes them the one
   part of the committed bindings whose VALUES a live extraction genuinely
   pins — `make -f renderer.mk construct-bindings` is that gate."
  #{"lv_obj_flag_t" "lv_state_t"})

(def member-fact
  "One POC #1 probe fact: a constant name + its authoritative value."
  [:map {:closed true} [:name [:string {:min 1}]] [:value :int]])

(def enum-edn-schema
  "POC #1's output shape: `{ \"lv_align_t\" [{:name \"LV_..\" :value N} ..] }` —
   the lift's (and the factory entrypoint's) input contract."
  [:map-of [:string {:min 1}] [:vector member-fact]])

(defn- sentinel-member?
  "LVGL sentinel / private constants that must never become proto members: a
   leading-underscore name (LVGL-private, e.g. `_LV_FLEX_COLUMN`) or a `_LAST`
   count sentinel (e.g. `LV_SCALE_MODE_LAST` — no underscore prefix, so the
   name alone is the signal). Level-1 facts keep them (raw truth); the lift is
   where the classification decision lives."
  [{mname :name}]
  (or (str/starts-with? mname "_") (str/ends-with? mname "_LAST")))
(m/=> sentinel-member? [:=> [:cat member-fact] :boolean])

(defn- typedef->root
  "\"lv_align_t\" -> \"align\" (strip the `lv_` prefix + `_t` suffix)."
  [typedef]
  (-> typedef
      (str/replace #"^lv_" "")
      (str/replace #"_t$" "")))
(m/=> typedef->root [:=> [:cat [:string {:min 1}]] [:string {:min 1}]])

(defn- typedef->proto-type
  "\"lv_align_t\" -> \"Align\" (the proto enum type name)."
  [typedef]
  (csk/->PascalCaseString (typedef->root typedef)))
(m/=> typedef->proto-type [:=> [:cat [:string {:min 1}]] [:string {:min 1}]])

(defn- member-prefix
  "\"lv_align_t\" -> \"LV_ALIGN_\" (the shared member-name prefix)."
  [typedef]
  (str "LV_" (str/upper-case (typedef->root typedef)) "_"))
(m/=> member-prefix [:=> [:cat [:string {:min 1}]] [:string {:min 1}]])

(defn- member->clj-keyword
  "\"LV_ALIGN_CENTER\" + \"LV_ALIGN_\" -> :center (falls back to stripping
   `LV_` when a member doesn't share the typedef prefix)."
  [c-name prefix]
  (csk/->kebab-case-keyword (if (str/starts-with? c-name prefix)
                              (subs c-name (count prefix))
                              (str/replace c-name #"^LV_" ""))))
(m/=> member->clj-keyword [:=> [:cat [:string {:min 1}] [:string {:min 1}]] :keyword])

(defn- lift-member
  "One probe fact `{:name :value}` -> an `enum-member` construct."
  [prefix {mname :name mvalue :value}]
  {:c-name mname
   :resolved-int mvalue
   :bitmask? false
   :clj-keyword (member->clj-keyword mname prefix)
   :proto-name (str/replace mname #"^LV_" "")
   :proto-number mvalue})
(m/=> lift-member [:=> [:cat [:string {:min 1}] member-fact] some?])

(defn- lift-enum
  "One `[typedef members]` entry -> a validated `:enum` construct (sentinel /
   private members dropped)."
  [[typedef members]]
  (let [prefix (member-prefix typedef)]
    {:kind :enum
     :typedef-name typedef
     :anon? true
     :cast-class (if (contains? direct-cast-typedefs typedef) :direct :lut)
     :proto-type (typedef->proto-type typedef)
     :members
     (into [] (comp (remove sentinel-member?) (map #(lift-member prefix %))) members)}))
(m/=> lift-enum [:=> [:cat [:tuple [:string {:min 1}] [:vector member-fact]]] some?])

(defn enum-edn->constructs
  "Lift POC #1's enum EDN map into hydrated `:enum` constructs, validated against
   `schema/ir` (throws with a Malli explanation on any malformed result)."
  [enum-edn]
  (schema/validate-ir! (mapv lift-enum enum-edn)))
(m/=> enum-edn->constructs [:=> [:cat enum-edn-schema] schema/ir])

(def ^:private synthetic-proto-members
  "Proto-only enum members with NO LVGL counterpart, keyed by typedef. The
   renderer special-cases them (`FLEX_FLOW_NONE` = \"no flex layout\" rather
   than any `lv_flex_flow_t` value), so they exist on the wire but not in the
   headers. Injected at the HEAD of the member vector (proto3 requires the
   first member to be the zero value); their numbers come from the registry
   like every member's, so the table carries only names."
  {"lv_flex_flow_t" [{:proto-name "FLEX_FLOW_NONE" :clj-keyword :none}]})

(defn inject-synthetics
  "ir→ir: prepend each enum's proto-only synthetic members (idempotent — a
   member already present by `:proto-name` is not re-added). `:proto-number`
   is provisional 0 until `registry/apply-numbering` stamps it."
  [enums]
  (mapv (fn [{:keys [typedef-name members] :as construct}]
          (if-let [synths (get synthetic-proto-members typedef-name)]
            (let [present (into #{} (map :proto-name) members)
                  to-add (into []
                               (comp (remove #(contains? present (:proto-name %)))
                                     (map (fn [{:keys [proto-name clj-keyword]}]
                                            {:c-name nil
                                             :resolved-int nil
                                             :bitmask? false
                                             :clj-keyword clj-keyword
                                             :proto-name proto-name
                                             :proto-number 0})))
                               synths)]
              (assoc construct :members (into to-add members)))
            construct))
        enums))
(m/=> inject-synthetics
      [:=> [:cat [:vector schema/enum-construct]] [:vector schema/enum-construct]])

(def setter-fact
  "One extracted setter fact (`extract-lvgl-setters.sh` output row)."
  [:map {:closed true} [:c-name [:string {:min 1}]] [:owner [:string {:min 1}]]
   [:prop [:string {:min 1}]] [:value-c-type [:string {:min 1}]] [:style-prop? :boolean]])

(def setter-edn-schema
  "The setter extractor's output shape — pass 5's input contract."
  [:vector setter-fact])

(defn setter-edn->constructs
  "Pass 5: lift extracted setter facts into validated `:setter` constructs,
   cross-linked to the enum typedef each consumes (`:enum-ref` = the value
   C-type when it IS an extracted enum typedef, nil for scalar/struct values)."
  [setter-edn enum-edn]
  (schema/validate-ir! (mapv (fn [{:keys [c-name owner prop value-c-type style-prop?]}]
                               {:kind :setter
                                :c-name c-name
                                :widget owner
                                :prop prop
                                :value-c-type value-c-type
                                :enum-ref (when (contains? enum-edn value-c-type)
                                            value-c-type)
                                :style-prop? style-prop?})
                             setter-edn)))
(m/=> setter-edn->constructs [:=> [:cat setter-edn-schema enum-edn-schema] schema/ir])

(def required-typedefs
  "Every LVGL typedef the factory must find in an extraction to emit a COMPLETE
   projection: the proto-emitted set plus the authoring-only set.

   Derived, never restated — a hand-listed copy would silently stop matching
   the day either set grows, which is precisely the drift this guards."
  (into proto-emitted-typedefs authoring-only-typedefs))

(defn missing-required-typedefs
  "The `required-typedefs` absent from `enum-edn`, sorted (empty when the
   extraction is complete).

   THE FAILURE THIS EXISTS FOR IS NOT AN EMPTY EXTRACTION, IT IS A PARTIAL ONE.
   Emptiness is easy to picture and nearly impossible to hit: the extractor's
   final awk closes its map unconditionally, so a zero-record run still emits
   `}` — a well-formed, non-empty, entirely contentless table. A completeness
   check over the typedefs we actually consume catches that case AND the one
   that really happens: a `lv_conf.h` feature flag turned off, or an upstream
   header move, dropping a few typedefs while the rest extract cleanly.

   Without this, `emitted-enums`' filter silently yields a SHORTER enum set,
   every emitter writes a truncated-but-valid projection, and a freshness gate
   comparing it to the committed copy reports the FILE as stale — inverting the
   diagnosis and overwriting good source with an incomplete regeneration."
  [enum-edn]
  (vec (sort (remove #(contains? enum-edn %) required-typedefs))))
(m/=> missing-required-typedefs
      [:=> [:cat enum-edn-schema] [:vector [:string {:min 1}]]])

(defn direct-cast-parity-violations
  "Every place a DIRECT-CAST enum's assigned proto number disagrees with the
   LVGL header value it must equal, as
   `{:typedef .. :member .. :proto-number N :resolved-int M}` maps (empty when
   the contract holds).

   THE CONTRACT. `renderer.c` casts a direct-cast enum's proto int STRAIGHT to
   the LVGL type — no lookup table stands between them — so the two integers
   are one integer wearing two names. A `:lut` enum is exempt because its
   generated table is exactly the indirection that lets the numbers differ.

   WHY THIS CANNOT BE LEFT TO THE COMPILER. Both sides are plain ints, so a
   divergence is not a type error anywhere: the generated header IS the
   declaration, and the wrong value simply becomes the enum's meaning. It
   surfaces as a widget aligning or wrapping wrongly at runtime, arbitrarily
   far from the pin bump that caused it.

   Members with a nil `:resolved-int` are proto-only SYNTHETICS (no LVGL
   constant behind them, e.g. `FLEX_FLOW_NONE`); they have nothing to agree
   with and are skipped rather than reported."
  [enums]
  (vec (for [{:keys [typedef-name cast-class members]} enums
             :when (= :direct cast-class)
             {:keys [c-name resolved-int proto-number]} members
             :when (and (some? resolved-int) (not= resolved-int proto-number))]
         {:typedef typedef-name
          :member c-name
          :proto-number proto-number
          :resolved-int resolved-int})))
(m/=> direct-cast-parity-violations
      [:=> [:cat [:vector schema/enum-construct]]
       [:vector [:map {:closed true}
                 [:typedef [:string {:min 1}]]
                 [:member [:maybe [:string {:min 1}]]]
                 [:proto-number :int]
                 [:resolved-int :int]]]])