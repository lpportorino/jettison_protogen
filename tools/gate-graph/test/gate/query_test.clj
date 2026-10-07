(ns gate.query-test
  "Bounded traversal, canonical bytes and continuation identity are separate contracts."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.fixtures :as f]
            [gate.inspection-contract :as ic]
            [gate.query :as query]
            [malli.core :as m]))

(def request {:select {:op :nodes} :budget {:visits 100 :rows 100 :bytes 65536}})

(deftest canonical-bytes-round-trip-and-ignore-map-insertion-and-inventory-order
  (let [graph (f/example)
        reordered (-> (into (sorted-map-by #(compare (str %2) (str %1))) graph)
                      (update :nodes #(vec (reverse %))))
        a (query/prepare graph) b (query/prepare reordered)
        encoded (canonical/encode (:graph a) 65536)]
    (is (= (:artifact a) (:artifact b)))
    (is (= (:graph a) (edn/read-string encoded)))
    (is (= encoded (binding [*print-length* 0 *print-level* 0 *print-readably* false]
                     (canonical/encode (:graph a) 65536))))))

(deftest unicode-and-escapes-round-trip-through-ascii-edn
  (let [graph (assoc-in (f/example) [:nodes 0 :label] "Tree λ 🌳\n\"quote\" \\ path")
        encoded (canonical/encode graph 65536)]
    (is (every? #(< (int %) 128) encoded))
    (is (= graph (edn/read-string encoded)))
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (canonical/sha256 "")))))

(deftest pages-have-stable-order-and-no-omissions-or-duplicates
  (let [context (query/prepare (f/example))
        limited (assoc-in request [:budget :rows] 1)
        one (query/page context limited)
        two (query/page context (assoc limited :cursor (:next one)))
        three (query/page context (assoc limited :cursor (:next two)))]
    (is (= ["check" "compile" "root"] (mapv :id (concat (:rows one) (:rows two) (:rows three)))))
    (is (= :row-limit (:stop one)))
    (is (= :complete (:stop three)))
    (is (nil? (:next three)))))

(deftest filter-scanning-spends-visits-even-with-no-matches
  (let [context (query/prepare (f/example))
        filtered-request {:select {:op :nodes :where {:outcome :failed}}
                          :budget {:visits 1 :rows 10 :bytes 4096}}
        page (query/page context filtered-request)]
    (is (= [] (:rows page)))
    (is (= 1 (:visited page)))
    (is (= :visit-limit (:stop page)))
    (is (= 1 (get-in page [:next :offset])))))

(deftest byte-budget-covers-whole-page-and-permits-retrying-a-large-row
  (let [graph (assoc-in (f/example) [:nodes 2 :label] (apply str (repeat 500 "λ")))
        context (query/prepare graph)
        small (assoc-in request [:budget :bytes] 1024)
        page (query/page context small)]
    (is (= :byte-limit (:stop page)))
    (is (empty? (:rows page)))
    (is (= 0 (get-in page [:next :offset])))
    (is (<= (canonical/utf8-size (canonical/encode page 65536)) 1024))
    (is (= "check" (-> (query/page context (assoc request :cursor (:next page))) :rows first :id)))))

(deftest cursors-bind-the-entire-artifact-and-selection
  (let [context (query/prepare (f/example))
        continuation (:next (query/page context (assoc-in request [:budget :rows] 1)))
        changed (query/prepare (assoc-in (f/example) [:nodes 0 :label] "Changed observation"))]
    (doseq [[ctx req] [[changed request]
                       [context (assoc request :select {:op :children :anchor "root"})]]]
      (is (= :invalid-cursor
             (try (query/page ctx (assoc req :cursor continuation))
                  (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))))))

(deftest children-and-half-open-window-selectors
  (let [context (query/prepare (f/example))
        children (query/page context (assoc request :select {:op :children :anchor "root"}))
        window (query/page context (assoc request :select
                                          {:op :window :interval {:start-ns "40" :end-ns "41"}}))
        empty-window (query/page context (assoc request :select
                                                {:op :window :interval {:start-ns "40" :end-ns "40"}}))]
    (is (= ["check" "compile"] (mapv :id (:rows children))))
    (is (= ["check" "root"] (mapv :id (:rows window))))
    (is (empty? (:rows empty-window)))))

(deftest eval-and-unbounded-requests-are-outside-the-contract
  (is (false? (m/validate ic/Request (assoc request :eval "(System/exit 0)"))))
  (is (false? (m/validate ic/Request (assoc-in request [:budget :visits] 0))))
  (is (false? (m/validate ic/Request (assoc-in request [:budget :rows] 1000000)))))

(deftest library-refusals-have-closed-error-records
  (is (false? (m/validate c/Failure {:code :future-code})))
  (is (false? (m/validate c/Failure {:code :invalid-cursor :secret "extra"})))
  (is (m/validate c/Failure
                  (try (canonical/encode (f/example) 1)
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))

(deftest measurement-pages-preserve-unknowns-and-account-for-filtered-visits
  (let [a (assoc (f/measurement) :id "a" :quantity :cpu-system-ns)
        b (assoc (f/measurement) :id "b")
        c (assoc (f/measurement) :id "c" :status :unavailable :value nil :reason :not-collected)
        context (query/prepare (assoc (f/example) :measurements [c a b]))
        input {:select {:op :measurements :where {:node "compile" :quantity :cpu-user-ns}}
               :budget {:visits 1 :rows 1 :bytes 4096}}
        first-page (query/page context input)
        second-page (query/page context (assoc input :cursor (:next first-page)))
        last-page (query/page context (assoc input :cursor (:next second-page)))]
    (is (= [] (:rows first-page)))
    (is (= :visit-limit (:stop first-page)))
    (is (= 1 (:visited first-page)))
    (is (= [b] (:rows second-page)))
    (is (= [c] (:rows last-page)))
    (is (= :complete (:stop last-page)))
    (is (= last-page (edn/read-string (canonical/encode last-page 4096))))))

(deftest nil-owner-selects-backdrop-without-attributing-it-to-a-task
  (let [background (assoc (f/measurement) :id "background" :node nil :resource "host" :method :proc-stat)
        context (query/prepare (assoc (f/example) :measurements [(f/measurement) background]))
        result (query/page context (assoc request :select {:op :measurements :where {:node nil}}))]
    (is (= [background] (:rows result)))
    (is (false? (m/validate ic/Selection {:op :measurements :where {:expression '(identity true)}})))))

(deftest measurement-continuations-cannot-switch-inventories-or-filter-semantics
  (let [context (query/prepare (assoc (f/example) :measurements
                                      [(assoc (f/measurement) :id "a") (assoc (f/measurement) :id "b")]))
        input (-> request (assoc :select {:op :measurements}) (assoc-in [:budget :rows] 1))
        continuation (:next (query/page context input))]
    (is (m/validate ic/Cursor (assoc continuation :offset 1000000)))
    (is (false? (m/validate ic/Cursor (assoc continuation :offset 1000001))))
    (doseq [selection [{:op :nodes} {:op :measurements :where {:status :measured}}]]
      (is (= :invalid-cursor
             (try (query/page context (assoc input :select selection :cursor continuation))
                  (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))
    (is (= :invalid-cursor
           (try (query/page context (assoc input :cursor (assoc continuation :offset 3)))
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))
