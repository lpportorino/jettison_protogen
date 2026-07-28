;; Independent check of ONE surprising derivation result, using the REAL
;; pipeline function (expand/expand->variants) rather than proven-pairs' own
;; resolver: does "text-accent-text @caption" resolve text-color to fg-1?
(require '[clojure.data.json :as json] '[clojure.java.io :as io]
         '[lvgl-codegen.expand :as expand] '[clojure.edn :as edn])
(def tokens
  {:tokens (let [raw (with-open [r (io/reader "../../output/manifests/design-tokens.json")]
                       (json/read r :key-fn keyword))]
             (into {} (map (fn [[k v]] [k (update v :kind keyword)])) (:tokens raw)))})
(def comps (with-open [r (java.io.PushbackReader. (io/reader "../../tools/renderer-gen/edn/components.edn"))]
             (edn/read r)))
(def cls (expand/expand-class-macros (:class-defs comps) "text-accent-text @caption"))
(println "expanded:" cls)
(let [parsed (expand/parse-class-string cls)
      variants (expand/expand->variants tokens parsed)]
  (doseq [g variants]
    (println "state-selector" (:state-selector g)
             "idx0 text-color =>" (get-in g [:variants 0 :props :text-color])
             "| idx1 =>" (get-in g [:variants 1 :props :text-color]))))
(println "fg-1 light/dark =" (get-in tokens [:tokens :fg-1]))
(println "accent-text     =" (get-in tokens [:tokens :accent-text]))
