(ns gate.record-view-test
  "Independent complete structural traversal, exact leaf and hostile cursor oracles for admitted report kinds."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.archive :as archive]
            [gate.archive-test :as archives]
            [gate.canonical :as canonical]
            [gate.fixtures :as fixtures]
            [gate.record-view :as view]
            [gate.record-view-contract :as rc]
            [gate.report-io :as io]
            [gate.run-test :as runs]
            [malli.core :as m])
  (:import [java.nio.file Files] [java.nio.file.attribute FileAttribute]))

(def budget {:visits 1 :rows 1 :bytes 65536})
(defn failure [f] (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))
(defn oracle [document]
  (letfn [(walk [path value]
            (into {path (cond (map? value) {:kind :map :children (count value)}
                              (vector? value) {:kind :vector :children (count value)}
                              :else {:kind :leaf :value value})}
                  (mapcat (fn [[slot child]] (walk (conj path slot) child))
                          (cond (map? value) value (vector? value) (map-indexed vector value) :else []))))]
    (walk [] document)))
(defn children [context path limits]
  (loop [request {:select {:op :children :path path} :budget limits} found []]
    (let [page (view/page context request)]
      (is (m/validate rc/Page page))
      (is (<= (canonical/utf8-size (canonical/encode page 65536)) (:bytes limits)))
      (is (false? (:no-progress? page)))
      (is (= (:children (:container page)) (:children page)))
      (if-let [cursor (:next page)]
        (do (is (pos? (- (:end page) (:start page))))
            (recur (assoc request :cursor cursor) (into found (:rows page))))
        (do (is (= :complete (:stop page))) (is (= (:end page) (:children page)))
            (into found (:rows page)))))))
(defn traversed [context limits]
  (loop [todo [[]] found {}]
    (if-let [path (peek todo)]
      (let [location (:location (view/navigate context {:path path :direction :self :bytes 65536}))
            rows (if (= :leaf (:kind location)) [] (children context path limits))]
        (is (= (when (seq path) (pop path)) (:parent location)))
        (is (= (peek path) (:slot location)))
        (recur (into (pop todo) (map :path rows))
               (assoc found path (select-keys location [:kind :value :children]))))
      found)))

(deftest every-admitted-archive-field-and-empty-container-is-reachable
  (let [document (archives/document) context (view/prepare :run-archive document)]
    (is (= (oracle document) (traversed context budget)))
    (is (= (oracle document) (traversed context {:visits 1000 :rows 2 :bytes 4096})))
    (is (= (:artifact document) (:artifact context)))
    (is (= {:kind :vector :children 0} (get (traversed context budget) [:graph :measurements])))
    (is (= {:kind :leaf :value true} (get (traversed context budget) [:graph :run :complete?])))
    (is (= {:kind :leaf :value nil} (get (traversed context budget) [:graph :resources 0 :parent])))
    (is (= {:kind :leaf :value "9007199254740993"} (get (traversed context budget) [:graph :run :clock :origin-ns])))))

(def batch-record
  {:schema/version 1 :status :passed :schedule {:order ["compile"] :selected ["compile"] :deselected []}
   :dispatches [{:gate "compile" :outcome :passed :at-ns "100" :adapter-interval nil :work nil :blocked-by []}] :duration-ns "100" :clock "synthetic-clock"})
(def report-record
  {:schema/version 1 :status :passed :batch batch-record :graph (fixtures/example) :captures []})

(deftest known-kinds-own-their-whole-document-not-unopened-siblings
  (doseq [[kind document] [[:graph (assoc (fixtures/decision) :edges [])] [:run-archive (archives/document)]
                           [:gate-definitions [runs/gate]] [:repository-observation (archives/observation)]
                           [:coordinator-batch batch-record] [:process-batch-report report-record]
                           [:test-batch-report report-record]]]
    (let [context (view/prepare kind document)]
      (is (= (oracle document) (traversed context {:visits 100 :rows 20 :bytes 65536})))
      (let [file (Files/createTempFile "record-view-" ".edn" (make-array FileAttribute 0))]
        (try (spit (str file) (canonical/encode document 1048576))
             (is (= document (io/read-record! (str file) kind admission/default-limits)))
             (is (thrown? clojure.lang.ExceptionInfo (io/read-record! (str file) (if (= kind :run-archive) :graph :run-archive) admission/default-limits)))
             (finally (Files/deleteIfExists file))))))
  (is (thrown? clojure.lang.ExceptionInfo (view/prepare :graph {:arbitrary "unknown"})))
  (is (not (m/validate rc/Kind :raw-edn))))

(deftest cursor-binds-content-selection-kind-version-and-offset-integrity
  (let [context (view/prepare :run-archive (archives/document))
        request {:select {:op :children :path []} :budget budget}
        first-page (view/page context request) cursor (:next first-page)
        different (view/prepare :run-archive (archive/create (assoc archives/scope :label "Other")
                                                             (fixtures/example) (archives/acquired) (archives/acquired)))]
    (is (= :row-limit (:stop first-page)))
    (is (= 1 (:visited first-page)))
    (doseq [altered [(assoc cursor :offset 0) (assoc cursor :offset 1000000)
                     (assoc cursor :artifact (canonical/sha256 "other"))
                     (assoc cursor :selection (canonical/sha256 "other"))
                     (assoc cursor :position (canonical/sha256 "forged"))
                     (assoc cursor :kind :graph)]]
      (is (= :invalid-record-cursor (failure #(view/page context (assoc request :cursor altered))))))
    (is (thrown? clojure.lang.ExceptionInfo (view/page context (assoc request :cursor (assoc cursor :version 2)))))
    (is (= :invalid-record-cursor (failure #(view/page different (assoc request :cursor cursor)))))
    (is (= :invalid-record-cursor (failure #(view/page context (assoc request :cursor cursor :select {:op :children :path [:graph]})))))
    (is (= :visit-limit (:stop (view/page context (assoc-in request [:budget :rows] 2)))))
    (is (= 1 (:end (view/page context (assoc-in request [:budget :rows] 2)))))
    (is (= :record-artifact-mismatch (failure #(view/require-artifact! context (canonical/sha256 "wrong")))))
    (is (= context (view/require-artifact! context (:artifact context))))))

(deftest zipper-retains-leaf-types-and-explicit-boundaries
  (let [context (view/prepare :run-archive (archives/document))
        move (fn [path direction] (view/navigate context {:path path :direction direction :bytes 65536}))]
    (is (= [] (get-in (move [:graph] :parent) [:location :path])))
    (is (= [:graph :nodes] (get-in (move [:graph :nodes 0] :parent) [:location :path])))
    (is (= [:artifact] (get-in (move [] :first-child) [:location :path])))
    (is (= [:graph :nodes 1] (get-in (move [:graph :nodes 0] :next-sibling) [:location :path])))
    (is (= [:graph :nodes 0] (get-in (move [:graph :nodes 1] :previous-sibling) [:location :path])))
    (is (= [:graph :edges] (get-in (move [:graph :measurements] :previous-sibling) [:location :path])))
    (doseq [[path direction] [[[] :parent] [[:graph :nodes 0] :previous-sibling]
                              [[:graph :nodes 2] :next-sibling] [[:graph :measurements] :first-child]
                              [[:graph :nodes 0 :parent] :first-child]]]
      (is (= :boundary (:status (move path direction)))) (is (nil? (:location (move path direction)))))
    (doseq [path [[:graph :nodes 99] [:graph :nodes :id] [:graph :nodes 0 :label 0]]]
      (is (= :missing-record-path (failure #(move path :self)))))
    (doseq [request [{:select {:op :children :path []} :budget (assoc budget :rows 0)}
                     {:select {:op :children :path []} :budget (assoc budget :bytes 65537)}
                     {:select {:op :children :path []} :budget budget :unexpected true}]]
      (is (not (m/validate rc/Request request))) (is (thrown? clojure.lang.ExceptionInfo (view/page context request))))))

(deftest byte-rollover-is-explicit-and-long-unicode-leaves-remain-reachable
  (let [text (apply str (repeat 2000 "🌳"))
        document (-> [runs/gate] (assoc-in [0 :command] [text "small"]))
        context (view/prepare :gate-definitions document)
        request {:select {:op :children :path [0 :command]} :budget {:visits 10 :rows 10 :bytes 1024}}
        page (view/page context request)]
    (is (= :byte-limit (:stop page))) (is (true? (:no-progress? page)))
    (is (= 0 (:start page) (:end page))) (is (= 1 (:visited page)))
    (is (= page (view/page context (assoc request :cursor (:next page)))))
    (is (= [text "small"] (mapv :value (:rows (view/page context (assoc-in request [:budget :bytes] 65536))))))
    (is (= text (get-in (view/navigate context {:path [0 :command 0] :direction :self :bytes 65536}) [:location :value])))
    (is (= :encoded-byte-limit (failure #(view/navigate context {:path [0 :command 0] :direction :self :bytes 1024}))))
    (is (= :encoded-byte-limit (failure #(view/page context {:select {:op :children :path [0 :command 0]} :budget (:budget request)}))))
    (is (= (oracle document) (traversed context {:visits 100 :rows 2 :bytes 65536})))))

(deftest tampered-records-never-produce-an-inspection-context
  (is (= :archive-identity (failure #(view/prepare :run-archive (assoc-in (archives/document) [:graph :nodes 0 :label] "Changed")))))
  (is (= :archive-repository (failure #(view/prepare :repository-observation (assoc (archives/observation) :content (canonical/sha256 "other")))))))

(deftest prepared-context-is-not-a-caller-supplied-map-or-unsealed-instance
  (let [document (archives/document) context (view/prepare :run-archive document)]
    (is (m/validate rc/Prepared context))
    (is (not (contains? (ns-publics (quote gate.record-view-contract)) (quote seal-record))))
    (is (not (m/validate rc/Prepared {:kind :run-archive :artifact (:artifact document) :document document})))
    (is (not (m/validate rc/Prepared (gate.record_view_contract.PreparedRecord. :run-archive (:artifact document) document nil))))
    (is (not (m/validate rc/Subtree (gate.record_view_contract.RecordValue. {:unknown true} nil))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (view/page {:kind :run-archive :artifact (:artifact document) :document document}
                            {:select {:op :children :path []} :budget budget})))))

(deftest pure-owner-records-retain-signed-exit-integers-without-widening-file-admission
  (let [process {:schema/version 1 :status :exited :exit -9 :pid "1" :elapsed-ns "1" :containment :none
                 :cleanup-required? false :observed-processes 1 :observed-processes-stopped? true :log nil}
        capture {:schema/version 1 :run "demo" :gate "compile" :label "Compile"
                 :source-digest (canonical/sha256 "source") :command ["synthetic"] :cwd "."
                 :process {:schema/version 1 :clock "synthetic-clock" :offset-ns "0" :observation process}
                 :validation {:source-before nil :source-after nil :inputs-before nil :inputs-after nil
                              :coverage-error? false :publication-error? false} :coverage nil}
        document (assoc report-record :captures [capture]) context (view/prepare :process-batch-report document)]
    (is (= -9 (get-in (view/navigate context {:path [:captures 0 :process :observation :exit] :direction :self :bytes 4096}) [:location :value])))
    (is (= (oracle document) (traversed context {:visits 100 :rows 20 :bytes 65536})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (admission/decode (canonical/encode document 65536) :process-batch-report admission/default-limits)))))

(deftest partial-byte-pages-resume-without-skipping-first-unreturned-member
  (let [document (mapv #(assoc runs/gate :id (str "declaration/" %)) (range 20))
        context (view/prepare :gate-definitions document)
        limits {:visits 100 :rows 100 :bytes 1024}
        page (view/page context {:select {:op :children :path []} :budget limits})]
    (is (= :byte-limit (:stop page)))
    (is (false? (:no-progress? page)))
    (is (< 0 (:end page) (:children page)))
    (is (= (inc (count (:rows page))) (:visited page)))
    (is (= (mapv vector (range 20)) (mapv :path (children context [] limits))))))
