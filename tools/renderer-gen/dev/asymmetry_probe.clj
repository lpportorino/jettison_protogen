;; Evidence for FINAL_REPORT: the TTF fallback arm is NOT symmetric across the
;; two vendored faces. Read-only; mutates nothing.
(require '[lvgl-codegen.font-metrics :as fm] '[clojure.java.io :as io])
(println "asset dir listing:"
         (sort (map #(.getName ^java.io.File %)
                    (.listFiles (io/file "../.." fm/asset-font-dir)))))
(doseq [[label tok] [["b612mono_bold_26 (the declared probe)" {:b612mono-bold-26 {:family "b612mono_bold" :size 26}}]
                     ["orbitron_bold_26 (hypothetical, NOT declared)" {:orbitron-bold-26 {:family "orbitron_bold" :size 26}}]]]
  (print (format "%-46s -> " label))
  (try
    (let [m (fm/font-metrics {:repo-root "../.." :tokens {:fonts tok}})
          r (first (filter #(empty? (:declared-by %)) []))]
      (println "resolution:"
               (pr-str (mapv (juxt :name :resolution :asset)
                             (filter #(seq (:declared-by %)) (:fonts m))))))
    (catch clojure.lang.ExceptionInfo e (println "REFUSED:" (ex-message e)))))
