(ns gate.record-view-contract
  "Closed structural navigation over explicit admitted report kinds; no arbitrary EDN boundary."
  (:require [gate.archive-contract :as archive]
            [gate.catalog-contract :as catalog]
            [gate.contract :as c]
            [gate.paired-performance-contract :as paired]
            [gate.repository-contract :as repository]
            [gate.run-contract :as run]
            [malli.core :as m]))

(def Kind catalog/ReportKind)
(def payload-schemas
  {:paired-performance paired/Document :artifact-catalog catalog/Document :run-archive archive/Document :graph c/Graph :process-batch-report run/ProcessBatchReport
   :test-batch-report run/TestBatchReport :gate-definitions run/Gates
   :coordinator-batch run/Batch :repository-observation repository/Observation})
(def Payload (into [:or] (vals payload-schemas)))
(def Field
  (into [:enum]
        (sort (distinct
               (for [schema (tree-seq coll? seq (vals payload-schemas))
                     :when (and (vector? schema) (= :map (first schema)))
                     entry (drop 2 schema) :when (vector? entry)]
                 (first entry))))))
(def KeywordValue
  (into [:enum]
        (sort (distinct
               (for [schema (tree-seq coll? seq (vals payload-schemas))
                     :when (and (vector? schema) (contains? #{:enum :=} (first schema)))
                     value (rest schema) :when (keyword? value)] value)))))
(def Scalar [:or :nil :boolean :int
             [:string {:max 4096}] KeywordValue])
(def Value
  "Internal bounded subvalues of the selected owner contracts, not an admitted document or extension vocabulary."
  [:schema {:registry {::value [:or Scalar
                                [:vector {:max 1000000} [:ref ::value]]
                                [:map-of {:max 64} Field [:ref ::value]]]}}
   [:ref ::value]])
(def Step [:or Field [:int {:min 0 :max 1000000}]])
(def Path [:vector {:max 64} Step])
(def Index [:int {:min 0 :max 1000000}])
(def Bytes [:int {:min 1024 :max 65536}])
(def ^:private context-seal #?(:clj (Object.) :cljs (js-obj)))
(def Seal [:fn #(identical? context-seal %)])
(deftype PreparedRecord [kind artifact document seal]
  #?(:clj clojure.lang.ILookup :cljs ILookup)
  (#?(:clj valAt :cljs -lookup) [_ field]
    (case field :kind kind :artifact artifact :document document nil))
  (#?(:clj valAt :cljs -lookup) [_ field not-found]
    (case field :kind kind :artifact artifact :document document not-found)))
(deftype RecordValue [value seal]
  #?(:clj clojure.lang.ILookup :cljs ILookup)
  (#?(:clj valAt :cljs -lookup) [_ field] (when (= :value field) value))
  (#?(:clj valAt :cljs -lookup) [_ field not-found] (if (= :value field) value not-found)))
(def Prepared
  "Opaque immutable owner-verified context; validating this concrete sealed type does not rewalk the admitted payload."
  [:fn #(and (instance? PreparedRecord %) (identical? context-seal (.-seal ^PreparedRecord %)))])
(def Subtree
  "Opaque value reached from a verified context; private path helpers do not revalidate its unvisited descendants."
  [:fn #(and (instance? RecordValue %) (identical? context-seal (.-seal ^RecordValue %)))])
#?(:clj
   (do
     (alter-meta! #'->PreparedRecord assoc :private true :doc "Internal constructor; sealing happens only after the record owner validates its whole input.")
     (alter-meta! #'->RecordValue assoc :private true :doc "Internal constructor for a subtree handle reached through an admitted typed path.")))
(m/=> ->PreparedRecord [:=> [:cat Kind c/Digest Payload Seal] Prepared])
(m/=> ->RecordValue [:=> [:cat Value Seal] Subtree])

(defn- seal-record
  "Seal one previously verified immutable record; callers must use record-view/prepare, never accept contexts as external evidence."
  [kind artifact document]
  (PreparedRecord. kind artifact document context-seal))
(m/=> seal-record [:=> [:cat Kind c/Digest Payload] Prepared])

(defn locate
  "Resolve an admitted structural path without enumerating or validating its unvisited subtree."
  [context path]
  (let [value (reduce (fn [value step]
                        (cond
                          (and (map? value) (keyword? step) (contains? value step)) (get value step)
                          (and (vector? value) (integer? step) (< step (count value))) (nth value step)
                          :else (throw (ex-info "Record inspection refused" {:code :missing-record-path}))))
                      (:document context) path)]
    (RecordValue. value context-seal)))
(m/=> locate [:=> [:cat Prepared Path] Subtree])

(def Base
  [:map {:closed true} [:path Path] [:parent [:maybe Path]] [:slot [:maybe Step]]])
(def Descriptor
  [:or (into Base [[:kind [:= :map]] [:children Index]])
   (into Base [[:kind [:= :vector]] [:children Index]])
   (into Base [[:kind [:= :leaf]] [:value Scalar]])])
(def Selection [:map {:closed true} [:op [:= :children]] [:path Path]])
(def Budget [:map {:closed true} [:visits [:int {:min 1 :max 1000000}]]
             [:rows [:int {:min 1 :max 1000}]] [:bytes Bytes]])
(def Position
  [:map {:closed true} [:version [:= 1]] [:kind Kind]
   [:artifact c/Digest] [:selection c/Digest] [:offset Index]])
(def Cursor (conj Position [:position c/Digest]))
(def Request
  [:map {:closed true} [:select Selection] [:budget Budget] [:cursor {:optional true} Cursor]])
(def Page
  [:map {:closed true} [:kind Kind] [:artifact c/Digest] [:path Path]
   [:container Descriptor] [:children Index] [:start Index] [:end Index]
   [:rows [:vector {:max 1000} Descriptor]] [:visited Index]
   [:stop [:enum :complete :row-limit :visit-limit :byte-limit]]
   [:no-progress? :boolean] [:next [:maybe Cursor]]])
(def Direction [:enum :self :parent :first-child :next-sibling :previous-sibling])
(def MoveRequest [:map {:closed true} [:path Path] [:direction Direction] [:bytes Bytes]])
(def Move
  [:map {:closed true} [:kind Kind] [:artifact c/Digest] [:from Path] [:direction Direction]
   [:status [:enum :located :boundary]] [:location [:maybe Descriptor]]])
(def ReadOptions [:map {:closed true} [:kind {:optional true} Kind] [:expected-artifact {:optional true} c/Digest]])
(def ReadRequest (into Request [[:kind {:optional true} Kind] [:expected-artifact {:optional true} c/Digest]]))
(def ReadMove (into MoveRequest [[:kind {:optional true} Kind] [:expected-artifact {:optional true} c/Digest]]))
(def Failure
  [:map {:closed true}
   [:code [:enum :invalid-record :invalid-record-request :invalid-record-move :invalid-record-cursor
           :missing-record-path :record-artifact-mismatch :encoded-byte-limit]]])
(def Encodable [:or Descriptor Selection Position Cursor Request Page MoveRequest Move ReadRequest ReadMove Failure])
(def registry
  {::kind Kind ::path Path ::descriptor Descriptor ::selection Selection ::position Position
   ::cursor Cursor ::request Request ::page Page ::move-request MoveRequest ::move Move
   ::read-options ReadOptions ::read-request ReadRequest ::read-move ReadMove ::failure Failure})
