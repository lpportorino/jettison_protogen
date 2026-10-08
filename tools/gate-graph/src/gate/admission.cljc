(ns gate.admission
  "Bounded EDN profile for artifacts and toolbox requests; no general reader or evaluation."
  (:require [gate.contract :as c]
            [gate.graph :as graph]
            [gate.inspection-contract :as ic]
            [gate.run-contract :as r]
            [gate.schema :as schema]
            [gate.viewer-contract :as vc]
            [malli.core :as m]))

(def default-limits
  "Explicit admission policy; callers may lower individual limits for their interface."
  {:bytes 134217728 :depth 64 :values 16777216 :collection 1000000
   :string-units 4096 :token-units 256})

(def SourceText
  "Maximum portable UTF-16 input envelope; the admission policy separately limits strict UTF-8 bytes."
  [:string {:max 134217728}])

(def ^:private keyword-table
  ;; Traverse shipped schema data only. Input text never reaches keyword interning.
  (into {} (map (juxt str identity))
        (filter keyword? (tree-seq coll? seq [schema/registry r/registry]))))
(def ^:private keyword-schema (into [:enum] (sort (vals keyword-table))))
(def ^:private raw-value
  "Internal parsing domain, not a public normalized record or extension mechanism."
  [:schema
   {:registry
    {::raw [:or :nil :boolean [:int {:min 0 :max 2147483647}]
            [:string {:max 4096}] keyword-schema
            [:vector {:max 1000000} [:ref ::raw]]
            [:map-of {:max 1000000} keyword-schema [:ref ::raw]]]}}
   [:ref ::raw]])
(def ^:private offset-schema [:int {:min 0 :max 134217728}])
(def ^:private count-schema [:int {:min 0 :max 16777216}])
(def ^:private context-schema
  [:map {:closed true} [:text [:string {:max 134217728}]] [:limits c/AdmissionLimits]])
(def ^:private parsed-schema [:tuple offset-schema count-schema raw-value])
(def ^:private frame-schema
  [:or
   [:map {:closed true} [:kind [:= :vector]] [:items [:vector {:max 1000000} raw-value]]]
   [:map {:closed true} [:kind [:= :map]]
    [:entries [:map-of {:max 1000000} keyword-schema raw-value]] [:field [:maybe keyword-schema]]]])
(def ^:private target-schemas
  {:viewer-manifest (m/schema vc/Manifest)
   :graph (m/schema c/Graph) :query-request (m/schema ic/Request)
   :aggregate-request (m/schema ic/AggregateRequest) :diff-request (m/schema ic/DiffRequest)
   :value (m/schema schema/Encodable) :gate-definitions (m/schema r/Gates)
   :cache-receipt (m/schema r/Receipt) :input-snapshot (m/schema r/Snapshot)
   :coordinator-batch (m/schema r/Batch) :attempt-observation (m/schema r/AttemptObservation)
   :process-observation (m/schema r/ProcessObservation) :clocked-process (m/schema r/ClockedProcess)
   :process-capture (m/schema r/ProcessCapture) :process-batch-report (m/schema r/ProcessBatchReport)
   :container-observation (m/schema r/ContainerObservation)
   :output-publication (m/schema r/OutputPublication) :contained-observation (m/schema r/ContainedObservation)
   :runtime-observation (m/schema r/RuntimeObservation) :test-observation (m/schema r/TestObservation)
   :clocked-tests (m/schema r/ClockedTests)})

(defn- refuse!
  "Report a bounded location and named refusal without echoing possibly private input."
  [code offset]
  (throw (ex-info "EDN admission refused" {:code code :offset offset :offset-unit :utf16})))
(m/=> refuse! [:=> [:cat c/AdmissionCode offset-schema] :nil])

(defn- unit-at
  "Read a UTF-16 unit or the explicit end sentinel, identically on JVM and JS."
  [text offset]
  (if (< offset (count text))
    #?(:clj (int (.charAt ^String text offset)) :cljs (.charCodeAt text offset))
    -1))
(m/=> unit-at [:=> [:cat SourceText offset-schema] [:int {:min -1 :max 65535}]])

(defn- admit-bytes!
  "Check strict source UTF-8 size before parsing, without allocating an encoded byte array."
  [text max-bytes]
  (loop [offset 0 size 0]
    (when (< offset (count text))
      (let [unit (unit-at text offset)
            high? (<= 55296 unit 56319)
            low? (<= 56320 unit 57343)
            width (cond (< unit 128) 1 (< unit 2048) 2 high? 4 :else 3)]
        (when (> (+ size width) max-bytes) (refuse! :input-byte-limit offset))
        (when (or low? (and high? (not (<= 56320 (unit-at text (inc offset)) 57343))))
          (refuse! :invalid-input-unicode offset))
        (recur (+ offset (if high? 2 1)) (+ size width))))))
(m/=> admit-bytes! [:=> [:cat SourceText [:int {:min 1 :max 134217728}]] :nil])

(defn- separator?
  "Recognize the ASCII whitespace and comma separators in this EDN profile."
  [unit]
  (contains? #{9 10 12 13 32 44} unit))
(m/=> separator? [:=> [:cat [:int {:min -1 :max 65535}]] :boolean])

(defn- skip-space
  "Skip bounded whitespace and semicolon comments without materializing their contents."
  [text offset]
  (loop [position offset comment? false]
    (let [unit (unit-at text position)]
      (cond
        (= -1 unit) position
        (or (= 10 unit) (= 13 unit)) (recur (inc position) false)
        (or comment? (= 59 unit)) (recur (inc position) true)
        (separator? unit) (recur (inc position) false)
        :else position))))
(m/=> skip-space [:=> [:cat [:string {:max 134217728}] offset-schema] offset-schema])

(defn- token-end
  "Locate a token's end under its own bound, before allocating the substring."
  [context offset]
  (let [text (:text context) limit (get-in context [:limits :token-units])]
    (loop [position offset]
      (let [unit (unit-at text position)]
        (if (or (separator? unit) (contains? #{-1 34 59 91 93 123 125} unit))
          position
          (do (when (>= (- position offset) limit) (refuse! :input-token-limit position))
              (recur (inc position))))))))
(m/=> token-end [:=> [:cat context-schema offset-schema] offset-schema])

(defn- parse-token
  "Accept finite registered keywords, booleans, nil and canonical bounded integers."
  [context offset used]
  (let [end (token-end context offset) token (subs (:text context) offset end)
        value (cond
                (= token "nil") nil
                (= token "true") true
                (= token "false") false
                (= 58 (unit-at token 0))
                (if (contains? keyword-table token) (get keyword-table token)
                    (refuse! :unknown-keyword offset))
                (re-matches #"(?:0|[1-9][0-9]*)" token)
                (do (when (or (> (count token) 10)
                              (and (= 10 (count token)) (pos? (compare token "2147483647"))))
                      (refuse! :input-integer-limit offset))
                    #?(:clj (Long/parseLong token) :cljs (js/parseInt token 10)))
                :else (refuse! :invalid-edn offset))]
    [end used value]))
(m/=> parse-token [:=> [:cat context-schema offset-schema count-schema] parsed-schema])

(defn- escape-unit
  "Decode one EDN string escape and return its next position and one UTF-16 unit."
  [text offset]
  (let [unit (unit-at text offset)
        simple {34 34, 92 92, 110 10, 114 13, 116 9, 98 8, 102 12}]
    (cond
      (contains? simple unit) [(inc offset) (get simple unit)]
      (= 117 unit)
      (let [end (+ offset 5)]
        (when (> end (count text)) (refuse! :invalid-edn offset))
        (let [hex (subs text (inc offset) end)]
          (when-not (re-matches #"[0-9a-fA-F]{4}" hex) (refuse! :invalid-edn offset))
          [end #?(:clj (Integer/parseInt hex 16) :cljs (js/parseInt hex 16))]))
      :else (refuse! :invalid-edn offset))))
(m/=> escape-unit
      [:=> [:cat [:string {:max 134217728}] offset-schema]
       [:tuple offset-schema [:int {:min 0 :max 65535}]]])

(defn- parse-string
  "Bound decoded string units while retaining escaped UTF-16 exactly, including lone surrogates."
  [context offset used]
  (let [text (:text context) limit (get-in context [:limits :string-units])]
    (loop [position (inc offset) parts []]
      (let [unit (unit-at text position)]
        (cond
          (= -1 unit) (refuse! :invalid-edn position)
          (= 34 unit) [(inc position) used (apply str parts)]
          :else
          (do (when (>= (count parts) limit) (refuse! :input-string-limit position))
              (let [[end decoded] (if (= 92 unit) (escape-unit text (inc position))
                                      [(inc position) unit])]
                (recur end (conj parts #?(:clj (str (char decoded))
                                          :cljs (js/String.fromCharCode decoded)))))))))))
(m/=> parse-string [:=> [:cat context-schema offset-schema count-schema] parsed-schema])

(defn- admit-next!
  "Spend nesting/value budgets and check collection capacity before reading the next item."
  [context frame offset used depth]
  (when (> depth (get-in context [:limits :depth])) (refuse! :input-depth-limit offset))
  (when (>= used (get-in context [:limits :values])) (refuse! :input-value-limit offset))
  (let [limit (get-in context [:limits :collection])]
    (when (and (= :vector (:kind frame)) (>= (count (:items frame)) limit))
      (refuse! :input-collection-limit offset))
    (when (and (= :map (:kind frame)) (nil? (:field frame)))
      (when (>= (count (:entries frame)) limit) (refuse! :input-collection-limit offset))
      (when-not (= 58 (unit-at (:text context) offset)) (refuse! :invalid-edn offset)))))
(m/=> admit-next!
      [:=> [:cat context-schema [:maybe frame-schema] offset-schema count-schema
            [:int {:min 1 :max 65}]] :nil])

(defn- append-item
  "Attach a parsed value or reserve a unique map key before parsing its value."
  [frame item offset]
  (if (= :vector (:kind frame))
    (update frame :items conj item)
    (if-let [field (:field frame)]
      (-> frame (assoc-in [:entries field] item) (assoc :field nil))
      (do (when-not (keyword? item) (refuse! :invalid-edn offset))
          (when (contains? (:entries frame) item) (refuse! :duplicate-map-key offset))
          (assoc frame :field item)))))
(m/=> append-item [:=> [:cat frame-schema raw-value offset-schema] frame-schema])

(defn- close-frame
  "Require the matching delimiter and a complete map pair before finishing a collection."
  [frame unit offset]
  (cond
    (and (= :vector (:kind frame)) (= 93 unit)) (:items frame)
    (and (= :map (:kind frame)) (= 125 unit) (nil? (:field frame))) (:entries frame)
    :else (refuse! :invalid-edn offset)))
(m/=> close-frame
      [:=> [:cat [:maybe frame-schema] [:enum 93 125] offset-schema] raw-value])

(defn- parse-form
  "Parse with a bounded explicit frame stack; nesting never grows instrumented call depth."
  [context offset]
  (loop [position offset used 0 frames []]
    (let [start (skip-space (:text context) position)
          unit (unit-at (:text context) start)
          frame (peek frames)]
      (cond
        (= -1 unit) (refuse! :invalid-edn start)
        (contains? #{93 125} unit)
        (let [value (close-frame frame unit start) remaining (pop frames)]
          (if (empty? remaining) [(inc start) used value]
              (recur (inc start) used
                     (conj (pop remaining) (append-item (peek remaining) value start)))))
        :else
        (do (admit-next! context frame start used (inc (count frames)))
            (case unit
              91 (recur (inc start) (inc used) (conj frames {:kind :vector :items []}))
              123 (recur (inc start) (inc used) (conj frames {:kind :map :entries {} :field nil}))
              (let [[end total value] (if (= 34 unit) (parse-string context start (inc used))
                                          (parse-token context start (inc used)))]
                (if (empty? frames) [end total value]
                    (recur end total (conj (pop frames) (append-item frame value start)))))))))))
(m/=> parse-form [:=> [:cat context-schema offset-schema] parsed-schema])

(defn decode
  "Load one closed EDN value under explicit budgets; graph targets also enforce global invariants.
   The caller already owns the input string: file/network adapters must separately bound I/O."
  [text target limits]
  (admit-bytes! text (:bytes limits))
  (let [start (skip-space text 0)
        [end _ value] (parse-form {:text text :limits limits} start)
        trailing (skip-space text end)]
    (when (< trailing (count text)) (refuse! :trailing-input trailing))
    (when-not (m/validate (get target-schemas target) value) (refuse! :invalid-input-shape start))
    (if (= :graph target) (graph/require-valid! value) value)))
(m/=> decode [:=> [:cat SourceText c/AdmissionTarget c/AdmissionLimits] [:or schema/Encodable r/Encodable]])
