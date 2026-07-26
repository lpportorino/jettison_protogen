(ns devcards.conventions
  "The ui-render-conventions manifest: load the authored EDN home and emit
   its deterministic JSON projection, committed alongside it
   (conventions/ui-render-conventions.edn -> conventions/
   ui-style-conventions.json). Determinism contract: every map is
   re-normalized to sorted order on read, so two consecutive emits are
   byte-identical regardless of EDN reader map ordering."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.walk :as walk]))

(def edn-home
  "The authored manifest (tool-relative)."
  "conventions/ui-render-conventions.edn")

(def json-out
  "The generated JSON projection (tool-relative; committed beside
   `edn-home` at conventions/ui-style-conventions.json)."
  "conventions/ui-style-conventions.json")

(defn- sort-maps
  "Recursively normalize every map to sorted-map — the determinism term."
  [form]
  (walk/postwalk (fn [x] (if (map? x) (into (sorted-map) x) x)) form))

(defn load-conventions
  "Read + normalize the manifest. Throws on malformed EDN or a missing
   required section (fail loud — a partial manifest must never emit)."
  []
  (let [m (sort-maps (edn/read-string (slurp edn-home)))]
    (doseq [k [:version :render-protocol :style-prop-slots :part-selectors :state-selectors
               :widget-states]]
      (when-not (contains? m k)
        (throw (ex-info "conventions manifest missing required section"
                        {:missing k :home edn-home}))))
    m))

(defn emit-json!
  "Write the deterministic JSON projection."
  []
  (let [m (load-conventions)]
    (spit json-out (with-out-str (json/pprint m :key-fn name)))
    (println "wrote" json-out
             "| slots:" (count (:style-prop-slots m))
             "| states:" (count (:state-selectors m))
             "| parts:" (count (:part-selectors m)))))

(defn -main [& _] (emit-json!))