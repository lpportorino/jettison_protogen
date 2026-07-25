(ns lvgl-codegen.json-schema
  "Malli → JSON Schema export for the proto-IR `Screen` schema — the deep-
   validation contract `tools/ptool validate` embeds. The conversion
   chokepoint pattern is lifted from jettison's hephaestus MCP surface
   (`heph.tools.envelope/malli->json-schema`): ONE conversion site, a typed
   failure class, deterministic pretty JSON.

   The exported keys and enum members are snake_case proto names — by
   construction they match ptool's decode contract (protojson
   `UseProtoNames` + `EmitDefaultValues`), because `proto-schema/screen`
   itself is written in proto field names."
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk]
            [lvgl-codegen.proto-schema :as proto-schema]
            [malli.core :as m]
            [malli.json-schema :as mjs]))

(set! *warn-on-reflection* true)

(def schema-path
  "Canonical home of the committed export (go:embed'ed by ptool)."
  "tools/ptool/schema/screen.schema.json")

(defn malli->json-schema
  "Convert a Malli schema to a JSON Schema map. The one chokepoint for the
   conversion — emission failures carry the typed class
   `::json-schema-gen-failed` as a single grep target."
  [malli-schema]
  (try (mjs/transform malli-schema)
       (catch Exception e
         (throw
          (ex-info "JSON Schema generation failed" {:type ::json-schema-gen-failed} e)))))
(m/=> malli->json-schema [:=> [:cat some?] [:map-of some? some?]])

(defn- jsonable
  "Normalize the transform output for JSON writing: keyword map keys →
   `name`, symbol keys (mjs's dotted definitions names) → `str` (matching the
   `$ref` strings), keyword VALUES (enum members) → `name`; every map sorted
   for deterministic output."
  [tree]
  (walk/postwalk (fn [node]
                   (cond (map? node)
                         (into (sorted-map)
                               (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]))
                               node)
                         (keyword? node) (name node)
                         :else node))
                 tree))
(m/=> jsonable [:=> [:cat some?] some?])

(defn- widen-uid-bounds
  "The IR carries :uid as a SIGNED int32 (pronto's uint32 carrier — a
   high-bit FNV-1a-32 uid is negative EDN-side), but ptool's canonical
   JSON dump (protojson) prints uint32 fields UNSIGNED. The exported
   schema validates the dump, so the uid property must admit the full
   32-bit space under either signing: [-2^31, 2^32)."
  [tree]
  (walk/postwalk
   (fn [node]
     (if (and (map? node) (get-in node [:properties :uid]))
       (update-in node [:properties :uid] merge {:minimum -2147483648 :maximum 4294967295})
       node))
   tree))
(m/=> widen-uid-bounds [:=> [:cat some?] some?])

(defn screen-json-schema-str
  "The proto-IR Screen schema as deterministic, pretty JSON."
  []
  (str (with-out-str (json/pprint (jsonable (widen-uid-bounds (malli->json-schema
                                                               proto-schema/screen)))))
       "\n"))
(m/=> screen-json-schema-str [:=> [:cat] [:string {:min 1}]])

(comment
  ;; Regenerate the committed export (staleness-gated by json-schema-test):
  (spit schema-path (screen-json-schema-str)))