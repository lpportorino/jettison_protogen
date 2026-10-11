(ns gate.record-view
  "Demand-driven output pages and structural zipper moves over one verified immutable report.
   Preparation consumes the whole bounded record. Output traversal is lazy only by caller demand;
   it is not streaming input validation, an authenticated cursor or proof of unopened sibling artifacts."
  (:require [gate.archive :as archive]
            [gate.canonical :as canonical]
            [gate.catalog-identity :as catalog]
            [gate.contract :as c]
            [gate.graph :as graph]
            [gate.record-view-contract :as rc]
            [gate.repository-identity :as repository]
            [malli.core :as m]))

(defn- refuse!
  "Refuse with one closed code and no rejected data or private paths."
  [code]
  (throw (ex-info "Record inspection refused" {:code code})))
(m/=> refuse! [:=> [:cat (second (nth rc/Failure 2))] :nil])

(defn- verify-record!
  "Check the selected owner's schema and applicable graph, archive or repository invariants."
  [kind document]
  (when-not (and (m/validate (get rc/payload-schemas kind) document)
                 (m/validate rc/Value document)) (refuse! :invalid-record))
  (case kind
    :artifact-catalog (catalog/require-valid! document)
    :run-archive (archive/require-valid! document)
    :graph (graph/require-valid! document)
    :repository-observation (repository/require-observation! document)
    (:process-batch-report :test-batch-report) (graph/require-valid! (:graph document))
    nil)
  nil)
(m/=> verify-record! [:=> [:cat rc/Kind rc/Payload] :nil])

(defn prepare
  "Verify one known report kind and bind canonical content identity for reusable local navigation.
   Archive and repository identities retain their verified producer seals. Other records hash their
   complete canonical payload. This verifies the selected record's schema and applicable graph
   invariants; coordinator/declaration records do not independently prove producer honesty or cross-file bindings."
  [kind document]
  (verify-record! kind document)
  ;; Canonical encoding requires the contract namespace, so its private factory
  ;; is invoked only here, after owner verification, without a namespace cycle.
  (#'rc/seal-record kind (case kind :artifact-catalog (:artifact document) :run-archive (:artifact document) :repository-observation (:content document)
                               (canonical/sha256 (canonical/encode document 134217728))) document))
(m/=> prepare [:=> [:cat rc/Kind rc/Payload] rc/Prepared])

(defn require-artifact!
  "Require an explicitly expected identity; an unrelated sibling file is never silently substituted."
  [context expected]
  (when-not (= expected (:artifact context)) (refuse! :record-artifact-mismatch))
  context)
(m/=> require-artifact! [:=> [:cat rc/Prepared c/Digest] rc/Prepared])

(defn- child-count
  "Count immediate members only; empty containers are retained as observable locations."
  [handle]
  (let [value (:value handle)] (if (or (map? value) (vector? value)) (count value) 0)))
(m/=> child-count [:=> [:cat rc/Subtree] rc/Index])

(defn- steps
  "Use canonical keyword order for maps and direct indices for vectors without expanding large inventories."
  [handle]
  (let [value (:value handle)] (when (map? value) (vec (sort-by str (keys value))))))
(m/=> steps [:=> [:cat rc/Subtree] [:maybe [:vector {:max 64} rc/Field]]])

(defn- child-step
  "Locate one immediate member; vector indices remain exact integer coordinates."
  [handle index]
  (if (vector? (:value handle)) index (nth (steps handle) index)))
(m/=> child-step [:=> [:cat rc/Subtree rc/Index] rc/Step])

(defn- value-at
  "Resolve one typed location through the sealed context without validating unvisited descendants."
  [context path]
  (rc/locate context path))
(m/=> value-at [:=> [:cat rc/Prepared rc/Path] rc/Subtree])

(defn- descriptor
  "Describe a container without copying its children, or a leaf without changing its type or value."
  [context path]
  (let [value (:value (value-at context path))
        base {:path path :parent (when (seq path) (pop path)) :slot (peek path)}]
    (cond
      (map? value) (assoc base :kind :map :children (count value))
      (vector? value) (assoc base :kind :vector :children (count value))
      :else (assoc base :kind :leaf :value value))))
(m/=> descriptor [:=> [:cat rc/Prepared rc/Path] rc/Descriptor])

(defn- cursor
  "Bind kind, artifact, selection, version and offset; the digest is integrity checking, not authentication."
  [context selection offset]
  (let [position {:version 1 :kind (:kind context) :artifact (:artifact context) :selection selection :offset offset}]
    (assoc position :position (canonical/sha256 (canonical/encode position 16384)))))
(m/=> cursor [:=> [:cat rc/Prepared c/Digest rc/Index] rc/Cursor])

(defn- starting-offset
  "Reject mismatched or altered cursors and offsets beyond this container's actual child inventory."
  [context request selection total]
  (if-let [continuation (:cursor request)]
    (let [offset (:offset continuation)]
      (when-not (and (<= offset total) (= continuation (cursor context selection offset)))
        (refuse! :invalid-record-cursor))
      offset)
    0))
(m/=> starting-offset [:=> [:cat rc/Prepared rc/Request c/Digest rc/Index] rc/Index])

(defn- result
  "Expose exact visited suffix bounds; complete means this requested container is exhausted."
  [context selection path start end rows visited stop]
  (let [container (descriptor context path)]
    {:kind (:kind context) :artifact (:artifact context) :path path :container container
     :children (child-count (value-at context path)) :start start :end end
     :rows rows :visited visited :stop stop :no-progress? (and (= start end) (= stop :byte-limit))
     :next (when-not (= stop :complete) (cursor context selection end))}))
(m/=> result [:=> [:cat rc/Prepared c/Digest rc/Path rc/Index rc/Index
                   [:vector {:max 1000} rc/Descriptor] rc/Index [:enum :complete :row-limit :visit-limit :byte-limit]] rc/Page])

(defn- fits?
  "Check the complete canonical page envelope before admitting an additional row."
  [page max-bytes]
  (try (canonical/encode page max-bytes) true
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) error
         (if (= :encoded-byte-limit (:code (ex-data error))) false (throw error)))))
(m/=> fits? [:=> [:cat rc/Page rc/Bytes] :boolean])

(defn- scan
  "Spend one visit per candidate and preserve the first unreturned member for a byte-limited retry."
  [context request selection path start]
  (let [value (value-at context path) total (child-count value)
        {:keys [rows visits] max-bytes :bytes} (:budget request)]
    (loop [offset start found [] visited 0]
      (cond
        (= offset total) (result context selection path start offset found visited :complete)
        (= (count found) rows) (result context selection path start offset found visited :row-limit)
        (= visited visits) (result context selection path start offset found visited :visit-limit)
        :else
        (let [item (descriptor context (conj path (child-step value offset)))
              candidate (result context selection path start (inc offset) (conj found item) (inc visited)
                                (if (= (inc offset) total) :complete :byte-limit))]
          (if (fits? (if (= (inc offset) total) candidate (assoc candidate :visited 1000000)) max-bytes)
            (recur (inc offset) (conj found item) (inc visited))
            (result context selection path start offset found (inc visited) :byte-limit)))))))
(m/=> scan [:=> [:cat rc/Prepared rc/Request c/Digest rc/Path rc/Index] rc/Page])

(defn page
  "List one container's immediate fields/items with complete typed drill paths and explicit continuation.
   Every section is reachable by repeating this operation, including empty containers and scalar leaves.
   The whole page, including its cursor/envelope, fits the canonical UTF-8 EDN byte budget or refuses.
   A too-small budget may return :no-progress? true with an unchanged frontier; increase bytes.
   A leaf has zero children and is returned exactly in :container. No page asserts recursive/full-CI coverage."
  [context request]
  (when-not (m/validate rc/Request request) (refuse! :invalid-record-request))
  (let [selection (canonical/sha256 (canonical/encode (:select request) 16384))
        path (get-in request [:select :path])
        total (child-count (value-at context path))
        offset (starting-offset context request selection total)
        answer (scan context request selection path offset)]
    (canonical/encode answer (get-in request [:budget :bytes]))
    answer))
(m/=> page [:=> [:cat rc/Prepared rc/Request] rc/Page])

(defn- sibling-path
  "Return the neighboring member in canonical structural order, or an explicit boundary."
  [context path delta]
  (when (seq path)
    (let [parent (pop path) value (value-at context parent)
          position (if (vector? (:value value)) (peek path) (first (keep-indexed (fn [index field] (when (= field (peek path)) index)) (steps value))))
          next-position (+ position delta)]
      (when (< -1 next-position (child-count value))
        (conj parent (child-step value next-position))))))
(m/=> sibling-path [:=> [:cat rc/Prepared rc/Path [:enum -1 1]] [:maybe rc/Path]])

(defn- move-path
  "Move through structural parent/child/sibling coordinates; graph causal relations use graph query APIs."
  [context path direction]
  (let [value (value-at context path)]
    (case direction
      :self path
      :parent (when (seq path) (pop path))
      :first-child (when (pos? (child-count value)) (conj path (child-step value 0)))
      :next-sibling (sibling-path context path 1)
      :previous-sibling (sibling-path context path -1))))
(m/=> move-path [:=> [:cat rc/Prepared rc/Path rc/Direction] [:maybe rc/Path]])

(defn navigate
  "Move a structural zipper without returning its unvisited subtree. :self exposes root and exact leaves.
   Exhausted parent/child/sibling movement returns :boundary with nil :location; an absent source path refuses.
   Graph node parent IDs/children and causal edges retain their distinct query/view semantics."
  [context request]
  (when-not (m/validate rc/MoveRequest request) (refuse! :invalid-record-move))
  (let [{:keys [path direction] max-bytes :bytes} request
        target (move-path context path direction)
        answer {:kind (:kind context) :artifact (:artifact context) :from path :direction direction
                :status (if target :located :boundary)
                :location (when target (descriptor context target))}]
    (canonical/encode answer max-bytes)
    answer))
(m/=> navigate [:=> [:cat rc/Prepared rc/MoveRequest] rc/Move])
