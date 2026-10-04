(ns native-size-probe
  "Manual sizing and negative-control campaign for LVGL 9.6.
   Canonical fixtures remain the ordinary gate; this standalone campaign
   is not part of the pure Clojure unit suite.
   Run from tools/devcards with the Graal JVM:
   clojure -M:bindings dev/native_size_probe.clj
   Positive cards come from the real corpus; narrow twins are independent
   negative controls, never a designed-flags exemption."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]
            [devcards.invariants :as invariants]))

(def spec (fixtures/load-spec))
(def canvas (get-in spec [:render :canvas]))
(def selected
  (vec (for [widget (:widgets spec)
             card (:cards widget)
             :when (or (and (= "lv_dropdown" (:tag widget))
                            (#{:small :medium} (:size card)))
                       (= "lv_scale/default/small/vertical" (:id card)))]
         {:widget widget :card card})))

(defn findings [{:keys [widget card]} family dark]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas) :h (:h canvas)})]
    (try
      (host/set-theme-family! h family)
      (host/render-card! h {:pb (fixtures/build-card canvas (:type widget) card)
                            :bp 0 :dark dark})
      (invariants/tree-findings (:id card)
                                (json/read-str (host/dump-tree! h) :key-fn keyword)
                                {:vis-px? true})
      (finally (host/close! h)))))

(deftest authored-positive-sizes-fit-native-content
  (is (= 13 (count selected)) "all selected states remain in the proof")
  (let [dropdowns (filter #(= "lv_dropdown" (get-in % [:widget :tag])) selected)
        widths (into {} (map (fn [{:keys [card]}] [(:size card) (get-in card [:props :w])])) dropdowns)]
    (doseq [[tier entries] (group-by #(get-in % [:card :size]) dropdowns)]
      (is (= 1 (count (set (map #(get-in % [:card :props :w]) entries))))
          (str tier " changes state without changing the size axis")))
    (is (< (:small widths) (:medium widths)) "the positive size axis stays distinct"))
  (doseq [entry selected family [0 1 2] dark [0 1]]
    (let [found (findings entry family dark)]
      (is (empty? found) (pr-str {:card (get-in entry [:card :id])
                                  :family family :dark dark :findings found})))))

(deftest undersized-controls-still-report-intrinsic-overflow
  (doseq [[id width flag] [["lv_dropdown/default/small/mid" 112 :scrollable_overflow]
                           ["lv_scale/default/small/vertical" 35 :overflow]]
          :let [entry (first (filter #(= id (get-in % [:card :id])) selected))
                narrow (cond-> (assoc-in entry [:card :props :w] width)
                         (= flag :overflow) (assoc-in [:card :wrapper :props :w] (+ width 32)))]
          family [0 1 2] dark [0 1]]
    (let [flags (set (map :invariant (findings narrow family dark)))]
      (is (contains? flags flag) (pr-str {:card id :width width :family family :dark dark :flags flags})))))

(let [{test-count :test :keys [pass fail error]} (run-tests 'native-size-probe)]
  (shutdown-agents)
  (System/exit (if (and (pos? test-count) (pos? pass) (zero? fail) (zero? error)) 0 1)))
