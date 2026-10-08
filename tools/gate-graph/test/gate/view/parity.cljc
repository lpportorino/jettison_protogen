(ns gate.view.parity
  "Executable public synthetic corpus; assert semantics before comparing JVM/CLJS canonical output."
  (:require [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.decimal :as decimal]
            [gate.query :as query]
            [gate.view.contract :as contract]
            [gate.view.model :as view]
            [gate.view.opportunity :as opportunity]
            [malli.core :as m]))

(defn check!
  "Fail with a static case name; success contributes one non-vacuity assertion."
  [checks label condition]
  (when-not condition (throw (ex-info "API parity assertion failed" {:case label})))
  (swap! checks inc))

(defn failure-code
  "Capture named production failures without enabling diagnostic instrumentation."
  [f]
  (try (f) :no-failure
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
         (:code (ex-data error)))))

(defn graph
  "A serial synthetic pair with typed finish/start dependency and portable hostile label units."
  []
  {:schema/version 1
   :run {:id "parity" :source-digest (apply str (repeat 64 "a")) :complete? true
         :clock {:id "synthetic-clock" :origin-ns "9007199254740993"}
         :end-ns "100" :expected-keys ["compile" "check"]}
   :sources [{:id "capture" :kind :capture :digest (apply str (repeat 64 "b")) :label "Synthetic"}]
   :resources [{:id "worker" :parent nil :source "capture" :kind :process :label "Worker"}]
   :nodes [{:id "root" :key "chain" :label "Root" :parent nil :source "capture"
            :kind :chain :record :execution :attempt 1 :outcome :passed
            :interval {:start-ns "0" :end-ns "100"}}
           {:id "compile" :key "compile" :label (str "Compile 🌳 " #?(:clj (char 0xd800) :cljs (js/String.fromCharCode 0xd800)))
            :parent "root" :source "capture" :kind :gate :record :execution :attempt 1 :outcome :passed
            :interval {:start-ns "0" :end-ns "40"}}
           {:id "check" :key "check" :label "Check </script>" :parent "root" :source "capture"
            :kind :gate :record :execution :attempt 1 :outcome :failed
            :interval {:start-ns "40" :end-ns "100"}}]
   :edges [{:id "compiled-before-check" :from {:node "compile" :phase :finish}
            :to {:node "check" :phase :start} :source "capture" :kind :requires :evidence :observed}]
   :measurements [{:id "cpu" :node "compile" :resource "worker" :source "capture"
                   :quantity :cpu-user-ns :form :delta :accounting :inclusive
                   :interval {:start-ns "0" :end-ns "40"} :status :measured
                   :value "80" :reason nil :method :wait4}]})

(defn sample-series
  "Generate one contiguous series with exact IDs/times and explicit synthetic values."
  [values]
  (mapv (fn [i value]
          (assoc (first (:measurements (graph))) :id (str "sample-" i) :value (str value)
                 :interval {:start-ns (str (* i 10)) :end-ns (str (* (inc i) 10))}))
        (range) values))

(defn emit!
  "Emit an admitted canonical value with a stable case label."
  [label value]
  (println (str label "\t" (canonical/encode value 1048576))))

(defn pages
  "Reconstruct a small fixture with a hard page ceiling; never assume a short page is final."
  [page-fn context request]
  (loop [request request found [] attempts 0]
    (when (= attempts 64) (throw (ex-info "Parity continuation did not complete" {:case :page-ceiling})))
    (let [page (page-fn context request) found (conj found page)]
      (if (= :complete (:stop page)) found
          (recur (assoc request :cursor (:next page)) found (inc attempts))))))

(defn -main
  "Assert exact folds, page conservation, bounded errors and detector policy on both runtimes."
  [& _]
  (let [checks (atom 0) source (graph) context (view/prepare source)
        query-context (query/prepare source)
        budget {:visits 1 :rows 1 :bytes 4096}
        selections [{:op :members :members ["compile" "check"]}
                    {:op :edges :members ["compile"]}
                    {:op :neighborhood :members ["compile"]}
                    {:op :search :text ""}
                    {:op :correlation :interval {:start-ns "0" :end-ns "40"}}]
        expected [["check" "compile"] ["compiled-before-check"] ["check"]
                  ["check" "compile" "root"] ["cpu"]]]
    (doseq [[selection ids] (map vector selections expected)]
      (let [answers (pages view/page context {:select selection :budget budget})]
        (check! checks [:rows (:op selection)] (= ids (mapv :id (mapcat :rows answers))))
        (check! checks [:complete (:op selection)] (nil? (:next (peek answers))))
        (doseq [[i answer] (map-indexed vector answers)]
          (check! checks [:schema (:op selection) i] (m/validate contract/Page answer))
          (check! checks [:bytes (:op selection) i]
                  (<= (canonical/utf8-size (canonical/encode answer 4096)) 4096))
          (check! checks [:visits (:op selection) i] (<= (:visited answer) 1))
          (emit! (str "view-" (name (:op selection)) "-" i) answer))))
    (doseq [[selection ids] [[{:op :nodes} ["check" "compile" "root"]]
                             [{:op :children :anchor "root"} ["check" "compile"]]
                             [{:op :window :interval {:start-ns "40" :end-ns "41"}} ["check" "root"]]
                             [{:op :measurements :where {:node "compile"}} ["cpu"]]]]
      (let [answers (pages query/page query-context {:select selection :budget budget})]
        (check! checks [:query-rows (:op selection)] (= ids (mapv :id (mapcat :rows answers))))
        (doseq [[i answer] (map-indexed vector answers)]
          (emit! (str "query-" (name (:op selection)) "-" i) answer))))
    (let [large-context (view/prepare (assoc-in source [:nodes 2 :label] (apply str (repeat 200 "🌳"))))
          request {:select {:op :search :text ""} :budget {:visits 10 :rows 10 :bytes 1024}}
          stopped (view/page large-context request)
          resumed (view/page large-context (-> request (assoc :cursor (:next stopped))
                                               (assoc-in [:budget :bytes] 65536)))]
      (check! checks :byte-stop (= :byte-limit (:stop stopped)))
      (check! checks :byte-no-row (empty? (:rows stopped)))
      (check! checks :byte-same-offset (= 0 (get-in stopped [:next :offset])))
      (check! checks :byte-resume (= ["check" "compile" "root"] (mapv :id (:rows resumed))))
      (check! checks :byte-envelope (<= (canonical/utf8-size (canonical/encode stopped 4096)) 1024))
      (emit! "byte-stop" stopped)
      (emit! "byte-resume" resumed))
    (let [base (second (:nodes source)) origin "9007199254740993"
          nodes (mapv (fn [id start end]
                        (assoc base :id id :interval {:start-ns (decimal/add origin (str start))
                                                      :end-ns (decimal/add origin (str end))}))
                      ["a" "b" "c"] [0 5 25] [10 20 30])
          folded (view/fold nodes)]
      (check! checks :fold-union (= "25" (get-in folded [:summary :occupied-ns])))
      (check! checks :fold-envelope (= "30" (get-in folded [:summary :envelope-ns])))
      (check! checks :fold-sum (= "30" (get-in folded [:summary :duration-sum-ns])))
      (check! checks :fold-gaps (= 2 (count (:occupied folded))))
      (check! checks :fold-permutation (= folded (view/fold (vec (reverse nodes)))))
      (check! checks :fold-roundtrip (= folded (admission/decode (canonical/encode folded 65536) :value admission/default-limits)))
      (emit! "large-decimal-fold" folded))
    (let [request {:select {:op :search :text ""} :budget budget}
          cursor (:next (view/page context request))]
      (doseq [[label expected call]
              [[:unknown-member :missing-view-member #(view/page context (assoc request :select {:op :members :members ["absent"]}))]
               [:duplicate-member :invalid-view-request #(view/page context (assoc request :select {:op :members :members ["compile" "compile"]}))]
               [:extra-key :invalid-view-request #(view/page context (assoc request :extra true))]
               [:wrong-selection :invalid-view-cursor #(view/page context (assoc request :select {:op :search :text "changed"} :cursor cursor))]
               [:wrong-artifact :invalid-view-cursor #(view/page context (assoc request :cursor (assoc cursor :artifact (apply str (repeat 64 "0")))))]
               [:cursor-offset :invalid-view-cursor #(view/page context (assoc request :cursor (assoc cursor :offset 1000)))]
               [:reversed-window :invalid-view-request #(view/overlaps? {:start-ns "7" :end-ns "3"} {:start-ns "0" :end-ns "10"})]
               [:detector-size :invalid-opportunity-samples #(opportunity/detect (vec (repeat 513 (first (:measurements source)))))]
               [:unknown-edn-keyword :unknown-keyword #(admission/decode "{:select {:op :not-registered}}" :view-request admission/default-limits)]]]
        (let [actual (failure-code call)]
          (check! checks label (= expected actual))
          (println (str "error-" (name label) "\t" actual)))))
    (check! checks :point-left (view/overlaps? {:start-ns "5" :end-ns "5"} {:start-ns "5" :end-ns "6"}))
    (check! checks :point-right (not (view/overlaps? {:start-ns "6" :end-ns "6"} {:start-ns "5" :end-ns "6"})))
    (check! checks :empty-window (not (view/overlaps? {:start-ns "0" :end-ns "9"} {:start-ns "5" :end-ns "5"})))
    (let [series (sample-series (concat (repeat 20 80) [10 10 10]))
          candidates (opportunity/detect series)]
      (check! checks :dip (= [:activity-dip] (mapv :kind candidates)))
      (check! checks :candidate-permutation (= candidates (opportunity/detect (vec (reverse series)))))
      (check! checks :gap (empty? (opportunity/detect (assoc-in series [20 :interval :start-ns] "201"))))
      (check! checks :partial (empty? (opportunity/detect (assoc-in series [19 :status] :partial))))
      (check! checks :threshold-strict (empty? (opportunity/detect (sample-series (concat (repeat 20 80) [40 40 40])))))
      (check! checks :pressure-excluded (empty? (opportunity/detect (mapv #(assoc % :quantity :pressure-io-some-us) series))))
      (emit! "activity-candidates" candidates)
      (check! checks :candidate-roundtrip (= candidates (admission/decode (canonical/encode candidates 65536) :value admission/default-limits))))
    (let [text (canonical/encode source 65536)]
      (check! checks :unicode-roundtrip (= source (admission/decode text :graph admission/default-limits)))
      (println (str "graph-digest\t" (:artifact query-context))))
    (check! checks :non-vacuous (>= @checks 60))
    (println (str "assertions\t" @checks))))

#?(:cljs (set! *main-cli-fn* -main))
