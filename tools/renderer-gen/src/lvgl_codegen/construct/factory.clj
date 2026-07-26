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
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [lvgl-codegen.construct.emit :as emit]
            [lvgl-codegen.construct.lift :as lift]
            [lvgl-codegen.construct.registry :as registry]
            [lvgl-codegen.construct.schema :as schema]
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
  (->> (lift/enum-edn->constructs enum-edn)
       (filterv #(contains? lift/proto-emitted-typedefs (:typedef-name %)))
       lift/inject-synthetics
       (registry/apply-numbering reg)
       (sort-by :proto-type)
       vec))
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

   UNREACHABLE TODAY: nothing calls this, and nothing in this tree produces the
   `enum-edn` it needs. The committed output is therefore a projection with no
   producer and no freshness check — NOT, as an earlier docstring here claimed,
   one pinned by a staleness test. There is no such test."
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

   UNREACHABLE TODAY, and NOT staleness-gated — see `generate-bindings!`. The
   header it would write is compiled into the renderer, so a drift here is a
   silently mis-mapped enum the C compiler cannot catch: the header IS the
   declaration."
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