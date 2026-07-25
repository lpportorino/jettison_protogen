(ns legos-shape-test
  "Pure-Clojure shape regression for the dock-panel lego — no wasm/render, the
   lego returns a node map, so this runs on bare clojure.

   Pins the validate-dock! contract: an EMPTY :stages vector is a VALID identity
   pipeline (0 cards, header + add-dropdown only) and must build, while a
   NON-vector :stages is still rejected (the relaxation must not become a
   no-op). Canary: revert the `(vector? stages)` relaxation back to
   `(and (vector? stages) (seq stages))` and cases 1+2 go red."
  (:require [devcards.legos :as legos]))

(defn- builds? [opts]
  (try (map? (legos/dock-panel opts)) (catch Throwable _ false)))

(defn- rejects? [opts]
  (try (legos/dock-panel opts) false (catch Throwable _ true)))

(defn -main [& _]
  (let [cases
        [["empty expanded dock builds (0 cards)" (builds? {:folded? false :badge 0 :stages []})]
         ["empty folded dock builds"             (builds? {:folded? true  :badge 0 :stages []})]
         ["non-vector :stages still rejected"     (rejects? {:folded? false :badge 0 :stages "x"})]
         ["nil :stages still rejected"            (rejects? {:folded? false :badge 0 :stages nil})]
         ["one-stage dock still builds"
          (builds? {:folded? false :badge 1 :stages [{:id "a" :label "A" :enabled? true}]})]]
        fails (remove second cases)]
    (doseq [[nm ok] cases] (println (if ok "PASS" "FAIL") nm))
    (if (seq fails)
      (do (println (count fails) "FAILED") (System/exit 1))
      (do (println "all" (count cases) "green") (System/exit 0)))))
