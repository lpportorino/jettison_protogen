(ns lvgl-codegen.construct.factory
  "The factory entrypoint: extracted enum facts → hydrated IR →
   generated proto text (`docs/lvgl-factory/03-FACTORY-DESIGN.md`, build
   step 4). The pipeline: lift (POC #1 facts → constructs) → filter to the
   proto-emitted set → inject proto-only synthetics → stamp assign-once
   numbers from the assign-once registry → emit + `buf format` (the re-parse gate).

   The entrypoint deliberately does NOT reconcile: an unpinned member fails
   `apply-numbering` loudly. Growing the registry is a separate, deliberate
   act (`registry/reconcile` + `save-registry!` + a reviewed commit), never a
   side effect of generating."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [lvgl-codegen.construct.emit :as emit]
            [lvgl-codegen.construct.lift :as lift]
            [lvgl-codegen.construct.registry :as registry]
            [lvgl-codegen.construct.schema :as schema]
            [lvgl-codegen.style-props :as style-props]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def ^:private proto-preamble
  "Header of the generated file. The package matches the hand-written
   `ui_ast.proto` so generated enums are drop-in `ui.*` types."
  "syntax = \"proto3\";\n\npackage ui;\n")

(defn emitted-enums
  "Enum facts + registry → the stamped proto-emitted IR: lift → filter to the
   proto-emitted set → inject synthetics → registry numbering, in
   `:proto-type` order (deterministic). The shared front half of every
   emitter (proto text, Clojure bindings, Malli specs)."
  [enum-edn reg]
  ;; COMPLETENESS BEFORE ANYTHING ELSE. The filter below silently yields a
  ;; shorter set when a typedef is absent, so an incomplete extraction would
  ;; emit a truncated-but-valid projection and a freshness gate would then
  ;; blame the committed FILE for being stale. Refuse here, where the shortfall
  ;; is still visible as a shortfall.
  (when-let [missing (seq (lift/missing-required-typedefs enum-edn))]
    (throw (ex-info "Incomplete LVGL extraction: required enum typedefs absent"
                    {:missing missing
                     :extracted (count enum-edn)
                     :required (count lift/required-typedefs)})))
  (let [enums (->> (lift/enum-edn->constructs enum-edn)
                   (filterv #(contains? lift/proto-emitted-typedefs (:typedef-name %)))
                   lift/inject-synthetics
                   (registry/apply-numbering reg)
                   (sort-by :proto-type)
                   vec)]
    ;; The direct-cast parity contract, checked HERE because this is the shared
    ;; front half of every emitter — so no emission path can skip it. It has to
    ;; be a throw rather than a report: the emitters' next step is to write the
    ;; number into a committed projection, and a wrong direct-cast number is
    ;; invisible to the C compiler (the generated header IS the declaration).
    (when-let [violations (seq (lift/direct-cast-parity-violations enums))]
      (throw (ex-info "Direct-cast enum parity violated: proto number != LVGL header value"
                      {:violations violations})))
    enums))
(m/=> emitted-enums
      [:=> [:cat lift/enum-edn-schema registry/registry-schema]
       [:vector schema/enum-construct]])

(defn authoring-enums
  "Enum facts → the AUTHORING-ONLY hydrated IR
   (`lift/authoring-only-typedefs`): lifted like every enum but NEVER
   registry-numbered, synthetic-injected, or proto-emitted — their bindings
   carry raw LVGL header values (the lift's probe-resolved ints), OR'd onto
   the wire by emit-proto."
  [enum-edn]
  (->> (lift/enum-edn->constructs enum-edn)
       (filterv #(contains? lift/authoring-only-typedefs (:typedef-name %)))
       (sort-by :proto-type)
       vec))
(m/=> authoring-enums [:=> [:cat lift/enum-edn-schema] [:vector schema/enum-construct]])

(defn enum-edn->proto-text
  "Enum facts + registry → the generated proto enum text."
  [enum-edn reg]
  (str proto-preamble
       "\n"
       (str/join "\n" (map emit/enum->proto (emitted-enums enum-edn reg)))))
(m/=> enum-edn->proto-text
      [:=> [:cat lift/enum-edn-schema registry/registry-schema] [:string {:min 1}]])

(defn generate-enums-proto!
  "Write the generated enum `.proto` for `enum-edn` to `out-path`, numbered
   from the committed registry, then canonicalize with `buf format -w` —
   buf re-parses the file, so a malformed emission fails here, never
   downstream in proto-sync."
  [enum-edn out-path]
  (spit out-path
        (enum-edn->proto-text enum-edn (registry/load-registry registry/registry-path)))
  (let [{:keys [exit err out]} (sh/sh "buf" "format" "-w" out-path)]
    (when-not (zero? exit)
      (throw (ex-info "buf format rejected the generated proto"
                      {:path out-path :exit exit :err err :out out}))))
  nil)
(m/=> generate-enums-proto! [:=> [:cat lift/enum-edn-schema [:string {:min 1}]] :nil])

(defn generate-bindings!
  "Write the COMMITTED generated Clojure bindings ns
   (`src/lvgl_codegen/generated/enums.clj`) for `enum-edn`, numbered from the
   committed registry.

   Reached via this ns's `-main`, which `renderer.mk`'s `construct-bindings`
   drives: it extracts the enum EDN from the vendored LVGL headers, emits to a
   temp dir, and byte-compares against the committed copy. So the output is a
   projection WITH a producer and a freshness gate — the state an earlier
   docstring here correctly reported as missing."
  [enum-edn out-path]
  (io/make-parents out-path)
  (spit out-path
        (emit/enums->bindings-ns
         (emitted-enums enum-edn (registry/load-registry registry/registry-path))
         (authoring-enums enum-edn)))
  nil)
(m/=> generate-bindings! [:=> [:cat lift/enum-edn-schema [:string {:min 1}]] :nil])

(defn generate-luts!
  "Write the generated C LUT header (`generated/ui_luts.h`) for the `:lut`
   enums, numbered from the committed registry.

   Reached and staleness-gated the same way as `generate-bindings!` — which
   matters more here: this header is COMPILED INTO the renderer, so a drift is
   a silently mis-mapped enum the C compiler cannot catch (the header IS the
   declaration). That is precisely why the gate emits both from one extraction."
  [enum-edn out-path]
  (io/make-parents out-path)
  (spit out-path
        (emit/luts->header (emitted-enums enum-edn
                                          (registry/load-registry registry/registry-path))))
  nil)
(m/=> generate-luts! [:=> [:cat lift/enum-edn-schema [:string {:min 1}]] :nil])

(defn enum-coverage-gap
  "P-cov seed — the universe-vs-corpus report: every LVGL enum typedef
   consumed by an extracted two-arg setter but ABSENT from
   `lift/proto-emitted-typedefs`, mapped to its consuming setters (sorted,
   deterministic). What the proto cannot yet express; the data behind the
   future coverage gate."
  [setter-edn enum-edn]
  (->> (lift/setter-edn->constructs setter-edn enum-edn)
       (filter :enum-ref)
       (remove #(contains? lift/proto-emitted-typedefs (:enum-ref %)))
       (group-by :enum-ref)
       (map (fn [[typedef setters]] [typedef (vec (sort (map :c-name setters)))]))
       (into (sorted-map))))
(m/=> enum-coverage-gap
      [:=> [:cat lift/setter-edn-schema lift/enum-edn-schema]
       [:map-of [:string {:min 1}] [:vector [:string {:min 1}]]]])

(defn- splice-enum
  "Replace `proto-type`'s hand-written enum block in `text` with its generated
   counterpart. Exactly one upstream match required — zero means upstream
   renamed or moved the enum; fail loud, never emit a half-spliced proto."
  [text {:keys [proto-type] :as construct}]
  (let [pattern (re-pattern
                 (str "enum " (java.util.regex.Pattern/quote proto-type) " \\{[^}]*\\}\n"))
        matches (count (re-seq pattern text))]
    (when-not (= 1 matches)
      (throw (ex-info "Expected exactly one upstream enum block to splice"
                      {:proto-type proto-type :matches matches})))
    (str/replace text pattern (str/re-quote-replacement (emit/enum->proto construct)))))
(m/=> splice-enum [:=> [:cat [:string {:min 1}] schema/enum-construct] [:string {:min 1}]])

(defn assemble-ui-ast-proto!
  "Compose the FULL `ui_ast.proto` (O3): the authored MESSAGE section read
   from the pinned submodule (the UI-AST messages are OUR wire design, not
   LVGL mirrors — they stay authored), with every factory-generated
   LVGL-mirror enum block spliced in place of its hand-written counterpart,
   then `buf format -w` as the re-parse gate. The output is the O2-cutover
   candidate that eventually flips the proto's home to the factory."
  [enum-edn upstream-path out-path]
  (let [enums (emitted-enums enum-edn (registry/load-registry registry/registry-path))]
    (io/make-parents out-path)
    (spit out-path (reduce splice-enum (slurp upstream-path) enums))
    (let [{:keys [exit err out]} (sh/sh "buf" "format" "-w" out-path)]
      (when-not (zero? exit)
        (throw (ex-info "buf format rejected the assembled proto"
                        {:path out-path :exit exit :err err :out out})))))
  nil)
(m/=> assemble-ui-ast-proto!
      [:=> [:cat lift/enum-edn-schema [:string {:min 1}] [:string {:min 1}]] :nil])

(def lvgl-mirrored-constants
  "Every constant `lvgl-codegen.style-props` hand-carries from the LVGL
   headers, paired with the C constant it MUST equal.

   Lives HERE rather than beside the constants because `style_props.clj` is
   byte-mirrored into a consumer that has no `construct/` namespaces — a var
   only that consumer cannot reach would read as dead code on its side. The
   cost is that a newly added `lv-state-*`/`lv-part-*` does not pair itself;
   `style_props.clj` carries a note at the definitions saying so."
  {"LV_STATE_DEFAULT" style-props/lv-state-default
   "LV_STATE_PRESSED" style-props/lv-state-pressed
   "LV_STATE_FOCUSED" style-props/lv-state-focused
   "LV_STATE_DISABLED" style-props/lv-state-disabled
   "LV_PART_SCROLLBAR" style-props/lv-part-scrollbar
   "LV_PART_INDICATOR" style-props/lv-part-indicator
   "LV_PART_KNOB" style-props/lv-part-knob
   "LV_PART_SELECTED" style-props/lv-part-selected
   "LV_PART_ITEMS" style-props/lv-part-items
   "LV_PART_CURSOR" style-props/lv-part-cursor})

(defn hand-carried-mirror-violations
  "Every `lvgl-mirrored-constants` pair whose hand-carried value
   disagrees with the extracted LVGL header value, as
   `{:constant .. :carried N :extracted M}` maps (empty when they all agree).

   A constant named in that map but ABSENT from the extraction is itself a
   violation (`:extracted nil`), not a skip: it means the header stopped
   declaring it — a rename or removal across an LVGL major — which is exactly
   the drift the pairing exists to catch. Skipping it would make the guard
   quietest precisely when it should be loudest."
  [enum-edn]
  ;; Destructured to LOCALS that do not shadow clojure.core/name — the keyword
  ;; keys stay `:name`/`:value` (they are the extractor's on-disk vocabulary).
  (let [by-name (into {} (for [[_ members] enum-edn
                               {c-name :name c-value :value} members]
                           [c-name c-value]))]
    (vec (for [[c-name carried] (sort lvgl-mirrored-constants)
               :let [extracted (get by-name c-name)]
               :when (not= carried extracted)]
           {:constant c-name :carried carried :extracted extracted}))))
(m/=> hand-carried-mirror-violations
      [:=> [:cat lift/enum-edn-schema]
       [:vector [:map {:closed true}
                 [:constant [:string {:min 1}]]
                 [:carried :int]
                 [:extracted [:maybe :int]]]]])

(defn- parse-args
  "Closed --enums/--bindings-out/--luts-out flag pairs; unknown flags fail loud."
  [args]
  (reduce (fn [acc [flag value]]
            (case flag
              "--enums" (assoc acc :enums value)
              "--bindings-out" (assoc acc :bindings-out value)
              "--luts-out" (assoc acc :luts-out value)
              (throw (ex-info (str "unknown flag: " flag)
                              {:flag flag
                               :expected #{"--enums" "--bindings-out" "--luts-out"}}))))
          {}
          (partition 2 args)))
(m/=> parse-args
      [:=> [:cat [:sequential [:string {:min 1}]]]
       [:map [:enums {:optional true} [:string {:min 1}]]
        [:bindings-out {:optional true} [:string {:min 1}]]
        [:luts-out {:optional true} [:string {:min 1}]]]])

(defn -main
  "Emit BOTH committed projections from one extraction: the Clojure bindings ns
   to `--bindings-out` and the C LUT header to `--luts-out`, reading the
   extracted enum EDN named by `--enums`.

   Both destinations are mandatory and never guessed — the caller
   (`renderer.mk`'s `construct-bindings`) emits to a temp dir and byte-compares,
   so a destination this tool invented would defeat the freshness check.

   The two are emitted TOGETHER on purpose: they are the same extraction viewed
   twice (Clojure maps and C tables), numbered from the same assign-once
   registry, so emitting one without the other is how they drift apart."
  [& args]
  (let [{:keys [enums bindings-out luts-out]} (parse-args args)]
    (doseq [[flag v] [["--enums" enums]
                      ["--bindings-out" bindings-out]
                      ["--luts-out" luts-out]]]
      (when-not v
        (throw (ex-info (str flag " is required") {:args (vec args)}))))
    (let [edn (edn/read-string (slurp enums))]
      (when-let [violations (seq (hand-carried-mirror-violations edn))]
        (throw (ex-info "Hand-carried LVGL constant disagrees with the header"
                        {:violations violations})))
      (generate-bindings! edn bindings-out)
      (generate-luts! edn luts-out)
      (println (str "construct-factory: " (count lift/required-typedefs)
                    " required typedefs emitted (of " (count edn)
                    " extracted) -> " bindings-out " + " luts-out))))
  nil)
(m/=> -main [:=> [:cat [:* [:string {:min 1}]]] :nil])