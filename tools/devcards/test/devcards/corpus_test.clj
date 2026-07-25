(ns devcards.corpus-test
  "Unit tests for the generic themed-corpus driver (`devcards.corpus`) — the
   render-loop/error-partitioning/golden-diff mechanics every bring-your-own-
   corpus consumer plugs into, exercised over a fake render fn (no wasm, no
   host)."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.corpus :as corpus]
            [devcards.probe :as probe]))

(def ^:private screens
  [{:id "b-screen" :payload :b} {:id "a-screen" :payload :a}])

(def ^:private variants
  [{:key :dark :dark 1} {:key :light :dark 0}])

(defn- fake-result
  "Render stand-in: deterministic sha per (id, variant); throws for the one
   poisoned pair; carries a finding for a-screen dark."
  [{:keys [id]} {vk :key}]
  (when (and (= id "b-screen") (= vk :light))
    (throw (ex-info "renderer rejected screen" {:id id})))
  {:sha256 (str (name vk) "-" id)
   :w 10
   :h 20
   :findings (if (and (= id "a-screen") (= vk :dark))
               [{:invariant :zero-area :node "lv_label#1"}]
               [])})

(deftest render-corpus-partitions-and-tags
  (let [{:keys [by-variant findings errors]}
        (corpus/render-corpus screens variants fake-result)]
    (testing "errored pair lands in :errors, tagged, and OUT of the golden maps"
      (is (= [{:variant :light :id "b-screen" :error "renderer rejected screen"}]
             errors))
      (is (= #{"a-screen" "b-screen"} (set (keys (get by-variant :dark)))))
      (is (= #{"a-screen"} (set (keys (get by-variant :light))))))
    (testing "golden entries carry exactly sha/w/h"
      (is (= {:sha256 "dark-a-screen" :w 10 :h 20}
             (get-in by-variant [:dark "a-screen"]))))
    (testing "golden maps are sorted by id (stable manifest diffs)"
      (is (sorted? (get by-variant :dark)))
      (is (= ["a-screen" "b-screen"] (vec (keys (get by-variant :dark))))))
    (testing "findings are tagged with their variant + id"
      (is (= [{:invariant :zero-area :node "lv_label#1" :variant :dark :id "a-screen"}]
             findings)))))

(deftest render-corpus-refuses-empty
  (testing "a zero-screen or zero-variant corpus proves nothing — throw, never
            return a vacuous green"
    (is (thrown? Exception (corpus/render-corpus [] variants fake-result)))
    (is (thrown? Exception (corpus/render-corpus screens [] fake-result)))))

(deftest diff-cards-three-ways
  (let [expected {"a" {:sha256 "x"} "b" {:sha256 "y"} "gone" {:sha256 "z"}}
        actual {"a" {:sha256 "x"} "b" {:sha256 "DRIFT"} "new" {:sha256 "n"}}
        {:keys [mismatched missing unexpected]} (corpus/diff-cards expected actual)]
    (is (= [{:id "b" :expected "y" :actual "DRIFT"}] mismatched))
    (is (= ["gone"] missing))
    (is (= ["new"] unexpected))))

(deftest diff-cards-clean
  (let [cards {"a" {:sha256 "x"}}]
    (is (= {:mismatched [] :missing [] :unexpected []}
           (corpus/diff-cards cards cards)))))

(deftest probe-find-uid
  (testing "hit returns the exact node (depth-first, descendants included);
            miss returns nil"
    (let [tree {:type "lv_obj" :coords [0 0 9 9]
                :children [{:type "lv_obj" :coords [1 1 4 4] :uid 7
                            :children [{:type "lv_label" :coords [2 2 3 3]
                                        :uid 42 :children []}]}]}]
      (is (= {:type "lv_label" :coords [2 2 3 3] :uid 42 :children []}
             (probe/find-uid tree 42)))
      (is (nil? (probe/find-uid tree 999))))))
