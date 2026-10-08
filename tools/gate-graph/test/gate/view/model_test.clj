(ns gate.view.model-test
  "Independent membership, duration and pagination oracles for reversible presentation."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.fixtures :as fixtures]
            [gate.view.contract :as vc]
            [gate.view.model :as view]
            [malli.core :as m]))

(deftest fold-keeps-gaps-and-overlap
  (let [base (second (:nodes (fixtures/example)))
        nodes (mapv (fn [id start end] (assoc base :id id :interval {:start-ns (str start) :end-ns (str end)}))
                    ["a" "b" "c"] [0 5 25] [10 20 30])
        folded (view/fold nodes)]
    (is (= ["a" "b" "c"] (:members folded)))
    (is (= "25" (get-in folded [:summary :occupied-ns])))
    (is (= "30" (get-in folded [:summary :envelope-ns])))
    (is (= "30" (get-in folded [:summary :duration-sum-ns])))
    (is (= 2 (count (:occupied folded))))
    (is (= folded (view/fold (vec (reverse nodes)))))))

(deftest point-window-boundaries
  (is (view/overlaps? {:start-ns "5" :end-ns "5"} {:start-ns "5" :end-ns "6"}))
  (is (not (view/overlaps? {:start-ns "6" :end-ns "6"} {:start-ns "5" :end-ns "6"})))
  (is (not (view/overlaps? {:start-ns "0" :end-ns "9"} {:start-ns "5" :end-ns "5"}))))

(deftest discrete-union-oracle
  (is (:pass? (tc/quick-check
               100
               (prop/for-all [pairs (gen/vector (gen/tuple (gen/choose 0 100) (gen/choose 0 30)) 1 100)]
                             (let [base (second (:nodes (fixtures/example)))
                                   nodes (mapv (fn [i [a length]] (assoc base :id (str "n" i) :interval {:start-ns (str a) :end-ns (str (+ a length))})) (range) pairs)
                                   points (set (mapcat (fn [[a length]] (range a (+ a length))) pairs))]
                               (= (str (count points)) (get-in (view/fold nodes) [:summary :occupied-ns]))))
               :seed 260108))))

(deftest bounded-pages-conserve-typed-edges
  (let [context (view/prepare (fixtures/example))
        request {:select {:op :edges :members ["compile"]} :budget {:visits 10 :rows 1 :bytes 4096}}
        answer (view/page context request)]
    (is (= (:edges (fixtures/example)) (:rows answer)))
    (is (= :complete (:stop answer)))
    (is (empty? (:rows (view/page context (assoc request :select {:op :correlation :interval {:start-ns "0" :end-ns "100"}})))))
    (let [search {:select {:op :search :text ""} :budget {:visits 1 :rows 1 :bytes 4096}}
          pages (loop [request search rows []]
                  (let [page (view/page context request) rows (into rows (:rows page))]
                    (if-let [cursor (:next page)] (recur (assoc request :cursor cursor) rows) rows)))]
      (is (= (set (map :id (:nodes (fixtures/example)))) (set (map :id pages)))))))

(deftest rejected-members-and-stale-continuations
  (let [context (view/prepare (fixtures/example))
        request {:select {:op :members :members ["compile" "check"]} :budget {:visits 10 :rows 1 :bytes 4096}}
        continuation (:next (view/page context request))
        code (fn [f] (try (f) (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    (is (= :invalid-view-request
           (code #(view/fold [(first (:nodes (fixtures/example))) (first (:nodes (fixtures/example)))]))))
    (doseq [[expected selection]
            [[:missing-view-member {:op :members :members ["missing"]}]
             [:invalid-view-request {:op :members :members ["compile" "compile"]}]
             [:invalid-view-request {:op :correlation :interval {:start-ns "2" :end-ns "1"}}]]]
      (is (= expected (code #(view/page context (assoc request :select selection))))))
    (is (= :invalid-view-cursor
           (code #(view/page context (assoc request :cursor (assoc continuation :offset 100))))))
    (is (= :invalid-view-cursor
           (code #(view/page (view/prepare (assoc-in (fixtures/example) [:nodes 0 :label] "Changed"))
                             (assoc request :cursor continuation)))))))

(deftest neighborhoods-return-nodes-not-edges
  (let [page (view/page (view/prepare (fixtures/example)) {:select {:op :neighborhood :members ["compile"]}
                                                           :budget {:visits 10 :rows 10 :bytes 4096}})]
    (is (= ["check"] (mapv :id (:rows page))))))

(deftest canonical-admission-and-non-bmp-byte-boundaries
  (let [request {:select {:op :search :text ""} :budget {:visits 10 :rows 10 :bytes 1024}}
        graph (assoc-in (fixtures/example) [:nodes 2 :label] (apply str (repeat 200 "🌳")))
        context (view/prepare graph)
        page (view/page context request)]
    (is (= request (admission/decode (canonical/encode request 4096) :view-request admission/default-limits)))
    (is (= page (admission/decode (canonical/encode page 4096) :value admission/default-limits)))
    (is (= :byte-limit (:stop page)))
    (is (empty? (:rows page)))
    (is (= 0 (get-in page [:next :offset])))
    (is (<= (canonical/utf8-size (canonical/encode page 4096)) 1024))
    (is (= "check" (-> (view/page context (-> request (assoc :cursor (:next page)) (assoc-in [:budget :bytes] 65536))) :rows first :id)))
    (doseq [bad [(assoc request :extra true) (assoc-in request [:select :op] :unknown)
                 (assoc-in request [:budget :rows] 0) (assoc-in request [:budget :visits] 128001)
                 (assoc-in request [:select :members] ["compile"])]]
      (is (false? (m/validate vc/Request bad))))
    (is (= :unknown-keyword
           (try (admission/decode "{:select {:op :arbitrary-code}}" :view-request admission/default-limits)
                (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))))

(deftest irregular-dag-pages-conserve-members-and-boundaries
  (is (:pass?
       (tc/quick-check
        40
        (prop/for-all [links (gen/vector gen/boolean 19)
                       members (gen/not-empty (gen/vector-distinct (gen/choose 0 19) {:max-elements 8}))]
                      (let [base (fixtures/example)
                            prototype (second (:nodes base))
                            nodes (mapv (fn [i] (assoc prototype :id (str "n" i) :key (str "n" i) :parent nil
                                                       :interval {:start-ns (str (* i 2)) :end-ns (str (inc (* i 2)))})) (range 20))
                            edges (vec (keep-indexed (fn [i linked?] (when linked? {:id (str "e" i) :from {:node (str "n" i) :phase :finish}
                                                                                    :to {:node (str "n" (inc i)) :phase :start}
                                                                                    :kind :requires :evidence :observed :source "capture"})) links))
                            value (-> base (assoc :nodes nodes :edges edges) (assoc-in [:run :expected-keys] (mapv :key nodes)))
                            context (view/prepare value)
                            ids (set (map #(str "n" %) members))
                            request {:select {:op :edges :members (vec (sort ids))} :budget {:visits 2 :rows 1 :bytes 4096}}
                            observed (loop [request request rows [] attempts 0]
                                       (if (= attempts 100) [:did-not-finish]
                                           (let [page (view/page context request) rows (into rows (:rows page))]
                                             (if-let [cursor (:next page)] (recur (assoc request :cursor cursor) rows (inc attempts)) rows))))
                            expected (filterv #(or (ids (get-in % [:from :node])) (ids (get-in % [:to :node])))
                                              (get-in context [:query :graph :edges]))]
                        (and (= expected observed)
                             (= (vec (sort ids)) (:members (view/fold (filterv #(ids (:id %)) nodes)))))))
        :seed 260109))))
