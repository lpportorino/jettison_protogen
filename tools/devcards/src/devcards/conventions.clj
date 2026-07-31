(ns devcards.conventions
  "The ui-render-conventions manifest: fold the authored EDN home together with
   the LVGL-derived sections and emit the deterministic JSON projection
   committed beside it (conventions/ui-render-conventions.edn +
   lvgl-codegen.generated.enums -> conventions/ui-style-conventions.json).
   Determinism contract: every map is re-normalized to sorted order on read, so
   two consecutive emits are byte-identical regardless of EDN reader map
   ordering.

   TWO HOMES, SPLIT BY PROVENANCE. A section whose members are LVGL HEADER
   FACTS is DERIVED from `lvgl-codegen.generated.enums`, whose bytes
   `make -f renderer.mk construct-bindings` asserts equal to a live extraction
   of the vendored headers. Everything else — the render protocol, the proto
   slot table, the per-widget state commitments — is authored in the EDN and
   has no upstream to derive from. A derived table hand-carried in the EDN
   would be a second source that agrees with the first only by luck and
   diverges in silence, so the loader REFUSES one rather than merging over it.

   The JSON projection is the CONSUMER-FACING export: a producer building
   `ui.UiAst` off this repo's pin reads it to spell lv_state_t, lv_part_t and
   lv_obj_flag_t bits, none of which travel on the wire as anything but raw
   ints. A vocabulary missing here is authored downstream as magic numbers."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [lvgl-codegen.generated.enums :as enums]))

(def edn-home
  "The authored manifest (tool-relative)."
  "conventions/ui-render-conventions.edn")

(def json-out
  "The generated JSON projection (tool-relative; committed beside
   `edn-home` at conventions/ui-style-conventions.json)."
  "conventions/ui-style-conventions.json")

(def lvgl-derived-sections
  "Manifest section -> the generated LVGL binding it is DERIVED from.

   `lv_state_t` bits ride WidgetNode.states and the low half of a StyleGroup
   selector; `lv_obj_flag_t` bits ride obj_flags / obj_flags_clear, which
   renderer.c direct-casts into lv_obj_add_flag / lv_obj_remove_flag. Both are
   authoring-only enums — no proto enum, no registry numbering — so the
   generated bindings are the only machine-checked home either one has."
  {:obj-flags enums/obj-flag-keyword->int
   :state-selectors enums/state-keyword->int})

(def required-authored-sections
  "Sections the AUTHORED EDN must carry. The derived sections are deliberately
   absent: they cannot be missing at runtime without a namespace load failure,
   so asserting their presence here would be a check that cannot fail."
  [:version :render-protocol :style-prop-slots :part-selectors :widget-states])

(defn- sort-maps
  "Recursively normalize every map to sorted-map — the determinism term."
  [form]
  (walk/postwalk (fn [x] (if (map? x) (into (sorted-map) x) x)) form))

(defn load-conventions
  "Read + normalize the manifest: the authored EDN home folded together with
   the LVGL-derived sections. Throws (fail loud — a partial or double-sourced
   manifest must never emit) on malformed EDN, on an authored section a derived
   one owns, on a missing required authored section, or on an empty derived
   table."
  []
  (let [authored (sort-maps (edn/read-string (slurp edn-home)))
        derived lvgl-derived-sections]
    (when-some [hand (seq (sort (filter (set (keys derived)) (keys authored))))]
      (throw (ex-info "conventions manifest hand-carries an LVGL-derived section"
                      {:hand-carried (vec hand)
                       :home edn-home
                       :derived-from (vec (sort (keys derived)))})))
    (doseq [k required-authored-sections]
      (when-not (contains? authored k)
        (throw (ex-info "conventions manifest missing required section"
                        {:missing k :home edn-home}))))
    (when-some [empties (seq (sort (for [[k table] derived :when (empty? table)] k)))]
      (throw (ex-info "LVGL-derived conventions section is empty"
                      {:empty (vec empties)})))
    (merge authored (sort-maps derived))))

(defn emit-json!
  "Write the deterministic JSON projection to `out`, reporting the size of
   every map-valued section. The report is DERIVED from the loaded manifest
   rather than naming sections, so a section added to either home reports
   itself.

   `out` is a PARAMETER because the freshness lane emits to a temp dir and
   byte-compares — a destination this namespace insisted on would defeat that
   check. The no-arg arity writes the committed projection in place."
  ([] (emit-json! json-out))
  ([out]
   (let [m (load-conventions)]
     (spit out (with-out-str (json/pprint m :key-fn name)))
     (println "wrote" out
              (apply str (for [[k v] m :when (map? v)]
                           (str " | " (name k) ": " (count v))))))))

(defn -main
  "CLI entry: regenerate the JSON projection, in place or at an explicit
   destination. More than one argument fails loud rather than silently using
   the first."
  [& args]
  (when (next args)
    (throw (ex-info "devcards.conventions takes at most one destination path"
                    {:args (vec args)})))
  (emit-json! (or (first args) json-out)))
