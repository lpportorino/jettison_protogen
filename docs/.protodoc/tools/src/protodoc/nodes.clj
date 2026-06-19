(ns protodoc.nodes
  "Generate the layered LVGL node codegen IR (nodes.json) from proto-db.edn.

   Each L3 control node is derived purely from the proto + its `:interaction`
   overlay — \"add a node = add metadata\". A command-leaf message whose
   message-level `:interaction` is `:ui-pattern :slider` and whose single field
   is a `:semantic-type :normalized` double becomes a SliderControl node:

     state_field_path  ← related-state leaf (strip \"JonGuiData\", snake) + \".\"
                          + the command field (strip \"Set\", snake)
     scale             ← field :semantic-type (:normalized → 1000 per-mille)
     min/max           ← field {:gte :lte} constraints × scale
     presets           ← field :interaction :presets × scale
     command_id        ← the message :id (deterministic, unambiguous)
     title             ← the message :name minus a Set/Shift verb, camel-split

   Mirrors manifest.clj: pure db-in / json-out, wired into core.clj's command
   `case` + a `docs-nodes` make target. Non-slider / non-normalized command
   leaves are skipped (logged) until their kind lands — never silently emitted
   as a degraded node."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

;; ============================================================================
;; Name derivation (camelCase ↔ snake_case, leaf extraction)
;; ============================================================================

(defn leaf
  "Last dotted segment of a fully-qualified id (\"cmd.DayCamera.SetClaheLevel\"
   → \"SetClaheLevel\"; \"ser.JonGuiDataCameraDay\" → \"JonGuiDataCameraDay\")."
  [id]
  (last (str/split id #"\.")))

(defn camel-words
  "Split a CamelCase / PascalCase identifier into its word parts
   (\"SetClaheLevel\" → [\"Set\" \"Clahe\" \"Level\"])."
  [s]
  (->> (str/split s #"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
       (remove str/blank?)))

(defn camel->snake
  "Convert CamelCase to snake_case (\"CameraDay\" → \"camera_day\")."
  [s]
  (->> (camel-words s) (map str/lower-case) (str/join "_")))

(defn strip-prefix
  "Drop `prefix` from the front of `s` when present, else return `s`."
  [s prefix]
  (if (str/starts-with? s prefix) (subs s (count prefix)) s))

;; ============================================================================
;; Fixed-point scale (semantic-type → per-mille multiplier)
;; ============================================================================

;; A `:normalized` [0,1] double crosses the int-only WASM ABI at per-mille
;; (×1000). Other semantic-types (angle / temperature / voltage) need their own
;; entry; an unmapped one is a generator error (no silent ×1 that would
;; mis-scale), mirroring parse.clj's THROW-on-unknown-constraint posture.
(def ^:private scale-by-semantic-type
  {:normalized 1000})

(defn scale-for
  "Per-mille scale for a field's `:semantic-type`, or nil when unmapped."
  [semantic-type]
  (get scale-by-semantic-type semantic-type))

;; ============================================================================
;; Kind classification + node derivation
;; ============================================================================

(defn- single-field
  "The lone field of a single-field command message, or nil."
  [msg]
  (when (= 1 (count (:fields msg)))
    (first (:fields msg))))

(defn slider-node?
  "True when `msg` is a single-`:normalized`-double-field command leaf whose
   message-level `:interaction` is `:ui-pattern :slider`."
  [msg]
  (let [f (single-field msg)]
    (boolean
     (and (= :slider (get-in msg [:interaction :ui-pattern]))
          f
          (= :double (:type f))
          (= :normalized (get-in f [:interaction :semantic-type]))))))

(defn derive-slider-node
  "Derive the SliderControl node IR entry from a slider command-leaf `msg`.
   Throws ex-info when a load-bearing input is missing (fail-fast, never a
   degraded node)."
  [msg]
  (let [f (single-field msg)
        st (get-in f [:interaction :semantic-type])
        scale (scale-for st)
        related (first (get-in msg [:interaction :related-state]))
        cons (:constraints f)
        ;; accept inclusive (:gte/:lte) or exclusive (:gt/:lt) bounds
        min-bound (or (:gte cons) (:gt cons))
        max-bound (or (:lte cons) (:lt cons))]
    (when-not scale
      (throw (ex-info "unmapped :semantic-type for slider scale"
                      {:message (:id msg) :semantic-type st})))
    (when-not related
      (throw (ex-info "slider node has no :related-state"
                      {:message (:id msg)})))
    (when-not (and min-bound max-bound)
      (throw (ex-info "slider field lacks min/max (:gte/:gt + :lte/:lt) constraints"
                      {:message (:id msg) :constraints cons})))
    (let [;; "ser.JonGuiDataCameraDay" → "camera_day"
          container (-> related leaf (strip-prefix "JonGuiData") camel->snake)
          ;; "SetClaheLevel" → "clahe_level"
          field-name (-> (:name msg) (strip-prefix "Set") camel->snake)
          state-path (str container "." field-name)
          ;; "SetClaheLevel" → "Clahe Level"
          title (->> (camel-words (:name msg)) (remove #{"Set" "Shift"}) (str/join " "))
          presets (->> (get-in f [:interaction :presets])
                       (mapv #(long (Math/round (* (double %) (double scale))))))]
      {:id (:id msg)
       :kind :slider
       :title title
       :state-field-path state-path
       :subject state-path
       :command-id (:id msg)
       :scale scale
       :min-value (long (Math/round (* (double min-bound) (double scale))))
       :max-value (long (Math/round (* (double max-bound) (double scale))))
       :presets presets})))

;; ============================================================================
;; Generation
;; ============================================================================

(defn derive-nodes
  "Pure transform: proto-db → sorted vector of node IR entries (slider kind
   only for now). A slider whose bindings can't be cleanly derived is
   SKIPPED-with-reason (loudly), never emitted as a degraded node and never
   aborting the rest of the run. Non-slider command leaves are counted only."
  [db]
  (let [msgs (vals (:messages db))
        sliders (filter slider-node? msgs)
        results (for [m sliders]
                  (try
                    {:node (derive-slider-node m)}
                    (catch clojure.lang.ExceptionInfo e
                      {:skipped {:id (:id m) :reason (.getMessage e)}})))]
    {:nodes (->> results (keep :node) (sort-by :id) vec)
     :skipped (->> results (keep :skipped) (sort-by :id) vec)
     :non-slider-count (- (count msgs) (count sliders))}))

(defn generate-nodes
  "Read proto-db.edn, derive the node IR, and write nodes.json.

   Options:
   - :db-path    — path to proto-db.edn
   - :output-dir — directory for nodes.json
   - :git-sha    — optional git commit SHA for metadata"
  [{:keys [db-path output-dir git-sha]
    :or {db-path "docs/.protodoc/proto-db.edn"
         output-dir "output/nodes"}}]
  (let [db (edn/read-string (slurp db-path))
        {:keys [nodes skipped non-slider-count]} (derive-nodes db)
        manifest {:version "1.0.0"
                  :generated-at (str (java.time.Instant/now))
                  :protogen-commit (or git-sha "unknown")
                  :nodes nodes
                  :skipped skipped}
        out-dir (io/file output-dir)]
    (.mkdirs out-dir)
    (let [f (io/file out-dir "nodes.json")]
      (spit f (json/write-str manifest
                              :key-fn #(if (keyword? %)
                                         (cond-> (name %)
                                           (namespace %) (->> (str (namespace %) "/")))
                                         (str %))
                              :escape-slash false))
      (t/log! :info ["Wrote" (.getPath f)]))
    (doseq [{:keys [id reason]} skipped]
      (t/log! :warn ["Skipped slider node" id "—" reason]))
    (t/log! :info ["Generated nodes:" (count nodes) "slider node(s),"
                   (count skipped) "slider(s) skipped,"
                   non-slider-count "non-slider message(s)"])
    manifest))
