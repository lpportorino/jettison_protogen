(ns scratchcard.diff-test
  "Pins ATTRIBUTION, which is the whole point of the diff.

  A diff that reports \"3 cells moved\" without saying whether the input, the
  renderer or the JUDGEMENT changed sends the reader hunting in the wrong
  place. The judgement axis is the one a naive diff omits, and omitting it
  makes a threshold edit read as a pixel regression."
  (:require
   [clojure.test :refer [deftest is testing]]
   [scratchcard.diff :as diff]))

(def ^:private base
  {:run {:id "0001"}
   :input {:sha256 "in-a"}
   :wasm {:sha256 "wasm-a"}
   :protogen {:sha "git-a"}
   :lanes {:producers [:overlap] :thresholds {:overlap/gap-px 0}
           :caps {:vis-px? true} :classes-sha "cls-a"}
   :findings {:count 0 :live-sample []}
   :renders [{:cell "asgard-dark-800x480" :fb-sha256 "fb-a"}
             {:cell "asgard-light-800x480" :fb-sha256 "fb-b"}]})

(defn- moved-cells [m] (assoc m :renders [{:cell "asgard-dark-800x480" :fb-sha256 "fb-X"}
                                          {:cell "asgard-light-800x480" :fb-sha256 "fb-b"}]))

(deftest identical-runs-report-no-cause-and-no-movement
  (let [d (diff/compare-runs base (assoc-in base [:run :id] "0002"))]
    (is (empty? (:causes d)))
    (is (empty? (get-in d [:cells :moved])))
    (is (= 2 (get-in d [:cells :unchanged])))))

(deftest each-cause-is-attributed-to-ITS-OWN-axis
  (testing "input only"
    (let [d (diff/compare-runs base (moved-cells (assoc-in base [:input :sha256] "in-b")))]
      (is (= #{:input} (:causes d)))))
  (testing "renderer via the wasm"
    (let [d (diff/compare-runs base (moved-cells (assoc-in base [:wasm :sha256] "wasm-b")))]
      (is (= #{:renderer} (:causes d)))))
  (testing "renderer via the protogen sha"
    (let [d (diff/compare-runs base (moved-cells (assoc-in base [:protogen :sha] "git-b")))]
      (is (= #{:renderer} (:causes d)))))
  (testing "JUDGEMENT — a threshold edit is not a pixel regression"
    (let [d (diff/compare-runs base (assoc-in base [:lanes :thresholds] {:overlap/gap-px 1}))]
      (is (= #{:judgement} (:causes d)))))
  (testing "judgement via the classification table"
    (let [d (diff/compare-runs base (assoc-in base [:lanes :classes-sha] "cls-b"))]
      (is (= #{:judgement} (:causes d))))))

(deftest causes-are-a-SET-because-two-things-can-change-at-once
  ;; Picking one to report would be a guess presented as a finding.
  (let [b (-> base (assoc-in [:input :sha256] "in-b")
              (assoc-in [:wasm :sha256] "wasm-b")
              moved-cells)]
    (is (= #{:input :renderer} (:causes (diff/compare-runs base b))))))

(deftest pixels-that-move-with-nothing-upstream-are-UNEXPLAINED
  ;; The most interesting answer in the vocabulary: it means the renderer is
  ;; not deterministic under any input this archive can see. Reporting it as
  ;; "no cause" would bury exactly that.
  (let [d (diff/compare-runs base (moved-cells base))]
    (is (= #{:unexplained} (:causes d)))
    (is (= ["asgard-dark-800x480"] (get-in d [:cells :moved])))))

(deftest a-cell-present-in-only-one-run-is-a-MATRIX-change-not-a-moved-pixel
  ;; Conflating them would report adding a resolution as a rendering regression.
  (let [b (update base :renders conj {:cell "asgard-dark-390x844" :fb-sha256 "fb-c"})
        d (diff/compare-runs base b)]
    (is (empty? (get-in d [:cells :moved])))
    (is (= ["asgard-dark-390x844"] (get-in d [:cells :only-in-to])))
    (is (empty? (get-in d [:cells :only-in-from])))))

(deftest findings-that-appear-and-disappear-are-reported-separately
  (let [a (assoc base :findings {:count 1 :live-sample [{:invariant :overlap}]})
        b (assoc base :findings {:count 1 :live-sample [{:invariant :clipped}]})
        d (diff/compare-runs a b)]
    (is (= {:clipped 1} (get-in d [:findings :appeared])))
    (is (= {:overlap 1} (get-in d [:findings :disappeared])))))

(deftest describe-names-the-cause
  (let [d (diff/compare-runs base (moved-cells (assoc-in base [:input :sha256] "in-b")))]
    (is (re-find #"input" (diff/describe d)))
    (is (re-find #"1 cell" (diff/describe d)))))
