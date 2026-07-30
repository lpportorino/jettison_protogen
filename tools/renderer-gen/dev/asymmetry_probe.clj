(ns asymmetry-probe
  "Evidence probe: the TTF fallback arm is NOT symmetric across the two vendored
  faces. Read-only; mutates nothing.

  Run: cd tools/renderer-gen && clojure -Sdeps '{:aliases {:p {:extra-paths [\"dev\"]}}}' -M:p dev/asymmetry_probe.clj

  CARRIES AN `ns` FORM SO IT CAN BE GATED, which is the whole reason it has one. A
  dev file without one is collapsed by clj-kondo into a shared implicit `user`
  namespace, and CROSS-FILE collisions — duplicate require, shadowed var — then
  dominate what it reports for the whole directory. That is why
  `docs/.protodoc/scripts/` is still held out of every lane, and why
  `tools/devcards/dev` (whose files all carry `ns` forms) is gated."
  (:require
   [clojure.java.io :as io]
   [lvgl-codegen.font-metrics :as fm]))
(println "asset dir listing:"
         (sort (map #(.getName ^java.io.File %)
                    (.listFiles (io/file "../.." fm/asset-font-dir)))))
(doseq [[label tok] [["b612mono_bold_26 (the declared probe)" {:b612mono-bold-26 {:family "b612mono_bold" :size 26}}]
                     ["orbitron_bold_26 (hypothetical, NOT declared)" {:orbitron-bold-26 {:family "orbitron_bold" :size 26}}]]]
  (print (format "%-46s -> " label))
  (try
    ;; The undeclared-arm binding that used to sit here filtered an EMPTY literal,
    ;; so it was always nil and never read — dead on arrival rather than merely
    ;; unused. Deleted rather than renamed to `_`, which would have preserved a
    ;; computation that answers nothing.
    (let [m (fm/font-metrics {:repo-root "../.." :tokens {:fonts tok}})]
      (println "resolution:"
               (pr-str (mapv (juxt :name :resolution :asset)
                             (filter #(seq (:declared-by %)) (:fonts m))))))
    (catch clojure.lang.ExceptionInfo e (println "REFUSED:" (ex-message e)))))
