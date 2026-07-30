(ns wirelut-equiv
  "Exhaustive behavioural equivalence of the OLD inline `case` against the NEW map
  lookup in `lvgl-codegen.emit-proto`, over the whole domain `emit-widget` can hand
  it.

  Run: cd tools/renderer-gen && clojure -Sdeps '{:aliases {:p {:extra-paths [\"dev\"]}}}' -M:p dev/wirelut_equiv.clj

  A PROBE, NOT A GATE. `lvgl-codegen.wire-lut-test` is the standing static check
  over that vocabulary and rides `check-renderer`; this file only records the
  one-time equivalence argument for the rewrite, and nothing gates on its output."
  (:require
   [lvgl-codegen.emit-proto :as ep]))
(defn old-case [layout]
  (case layout
    :flex-row :FLEX_FLOW_ROW
    :flex-col :FLEX_FLOW_COLUMN
    :flex-row-wrap :FLEX_FLOW_ROW_WRAP
    :flex-row-reverse :FLEX_FLOW_ROW_REVERSE
    :flex-row-wrap-reverse :FLEX_FLOW_ROW_WRAP_REVERSE
    :flex-col-wrap :FLEX_FLOW_COLUMN_WRAP
    :flex-col-reverse :FLEX_FLOW_COLUMN_REVERSE
    :flex-col-wrap-reverse :FLEX_FLOW_COLUMN_WRAP_REVERSE
    :FLEX_FLOW_NONE))
(defn new-lookup [layout] (get ep/layout-flow-keyword->member layout :FLEX_FLOW_NONE))
(def domain (concat [:flex-row :flex-col :flex-row-wrap :flex-row-reverse
                     :flex-row-wrap-reverse :flex-col-wrap :flex-col-reverse
                     :flex-col-wrap-reverse]
                    [:grid :flex :unknown-kw "flex-row" 7 [:flex-row] {}]))
(let [bad (remove (fn [v] (= (old-case v) (new-lookup v))) domain)]
  (println "domain size:" (count domain))
  (println "disagreements:" (pr-str (vec bad)))
  (println "old results:" (pr-str (mapv old-case domain)))
  (System/exit (if (seq bad) 1 0)))
