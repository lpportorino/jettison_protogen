(ns protodoc.manifest
  "Generate machine-readable JSON manifests from proto-db.edn.

   Produces:
   - endpoints.json  — REST endpoint manifest for each leaf command
   - signals.json    — Signal manifest for each scalar field in JonGUIState subsystems
   - sub-signals.json — Signals for deeply nested fields (power modules, etc.)
   - reverse-index.json — Links endpoints/signals to doc files"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

;; ============================================================================
;; Name Derivation Functions (no csk dependency)
;; ============================================================================

(defn snake->camel
  "Convert snake_case to camelCase.
   Examples:
     \"camera_day\" → \"cameraDay\"
     \"focus_pos\" → \"focusPos\"
     \"s0\" → \"s0\"
     \"is_on\" → \"isOn\""
  [^String s]
  (let [parts (str/split s #"_")]
    (str (first parts)
         (str/join (map str/capitalize (rest parts))))))

(defn snake->kebab
  "Convert snake_case to kebab-case.
   Examples:
     \"set_iris\" → \"set-iris\"
     \"DayCamera\" → \"day-camera\"
     \"Lrf_calib\" → \"lrf-calib\""
  [^String s]
  ;; Insert hyphens before uppercase runs, then lowercase everything
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      str/lower-case
      (str/replace "_" "-")))

;; ============================================================================
;; Root / Group Identification
;; ============================================================================

(defn root-message?
  "Is this message a Root routing container?"
  [msg]
  (str/ends-with? (:id msg) ".Root"))

(defn group-message?
  "Is this a nested group container (has oneof named 'cmd')?
   Excludes Root messages. Includes Lrf_calib.Offsets-style groups."
  [msg]
  (and (not (root-message? msg))
       (some #(= "cmd" (:name %)) (:oneofs msg))))

(defn routing-container?
  "Is this message a routing container (Root or group)?"
  [msg]
  (or (root-message? msg)
      (group-message? msg)))

;; ============================================================================
;; Constraint → Malli Schema Conversion
;; ============================================================================

(defn constraints->malli
  "Convert buf.validate constraints + field type to Malli schema string."
  [{:keys [type constraints type-ref]} enums-map]
  (case type
    :double (if constraints
              (let [parts (cond-> [:double]
                            (:gte constraints) (conj (str ":min " (:gte constraints)))
                            (:gt constraints) (conj (str ":min " (inc (:gt constraints))))
                            (:lte constraints) (conj (str ":max " (:lte constraints)))
                            (:lt constraints) (conj (str ":max " (dec (:lt constraints)))))]
                (if (> (count parts) 1)
                  (str "[:double {" (str/join " " (rest parts)) "}]")
                  ":double"))
              ":double")
    :float (if constraints
             (let [parts (cond-> [:float]
                           (:gte constraints) (conj (str ":min " (:gte constraints)))
                           (:gt constraints) (conj (str ":min " (inc (:gt constraints))))
                           (:lte constraints) (conj (str ":max " (:lte constraints)))
                           (:lt constraints) (conj (str ":max " (dec (:lt constraints)))))]
               (if (> (count parts) 1)
                 (str "[:double {" (str/join " " (rest parts)) "}]")
                 ":double"))
             ":double")
    :int32 (if constraints
             (let [parts (cond-> [:int]
                           (:gte constraints) (conj (str ":min " (:gte constraints)))
                           (:gt constraints) (conj (str ":min " (inc (:gt constraints))))
                           (:lte constraints) (conj (str ":max " (:lte constraints)))
                           (:lt constraints) (conj (str ":max " (dec (:lt constraints)))))]
               (if (> (count parts) 1)
                 (str "[:int {" (str/join " " (rest parts)) "}]")
                 ":int"))
             ":int")
    :uint32 (if constraints
              (let [parts (cond-> [:int]
                            (:gte constraints) (conj (str ":min " (:gte constraints)))
                            (:gt constraints) (conj (str ":min " (inc (:gt constraints))))
                            (:lte constraints) (conj (str ":max " (:lte constraints)))
                            (:lt constraints) (conj (str ":max " (dec (:lt constraints)))))]
                (if (> (count parts) 1)
                  (str "[:int {" (str/join " " (rest parts)) "}]")
                  "[:int {:min 0}]"))
              "[:int {:min 0}]")
    (:int64 :uint64) ":int"
    :bool ":boolean"
    :string (if constraints
              (let [parts (cond-> [:string]
                            (:min-len constraints) (conj (str ":min " (:min-len constraints)))
                            (:max-len constraints) (conj (str ":max " (:max-len constraints))))]
                (if (> (count parts) 1)
                  (str "[:string {" (str/join " " (rest parts)) "}]")
                  ":string"))
              ":string")
    :enum (if-let [enum-def (get enums-map type-ref)]
            (let [vals (->> (:values enum-def)
                            (map :name)
                            (map #(str "\"" % "\"")))]
              (str "[:enum " (str/join " " vals) "]"))
            ":string")
    :bytes ":string"
    :message ":map"
    ":any"))

;; ============================================================================
;; Endpoint Extraction
;; ============================================================================

(defn- resolve-leaf-commands
  "Walk a Root or group message and collect leaf commands.
   Returns sequence of {:id :subsystem :command :path :root-id :oneof-field :fields :interaction :proto-source :doc-file}."
  [msgs root-msg subsystem-kebab parent-path]
  (for [field (:fields root-msg)
        :let [child (get msgs (:type-ref field))]
        :when child
        cmd (if (group-message? child)
              ;; Nested group — recurse one level deeper
              (let [group-kebab (snake->kebab (:name field))
                    group-path (str parent-path "/" group-kebab)]
                (for [gf (:fields child)
                      :let [leaf (get msgs (:type-ref gf))]
                      :when (and leaf (not (routing-container? leaf)))]
                  {:id (:id leaf)
                   :subsystem subsystem-kebab
                   :command (snake->kebab (:name gf))
                   :path (str group-path "/" (snake->kebab (:name gf)))
                   :root-id (:id root-msg)
                   :oneof-field (:name gf)
                   :proto-source (:source leaf)
                   :doc-file (str "proto/" (:id leaf) ".md")
                   :fields (mapv (fn [f]
                                   (cond-> {:name (:name f)
                                            :type (name (:type f))}
                                     (:constraints f) (assoc :constraints (:constraints f))
                                     (:interaction f) (assoc :interaction (:interaction f))))
                                 (:fields leaf))
                   :interaction (:interaction leaf)}))
              ;; Direct leaf command
              (when-not (routing-container? child)
                [{:id (:id child)
                  :subsystem subsystem-kebab
                  :command (snake->kebab (:name field))
                  :path (str parent-path "/" (snake->kebab (:name field)))
                  :root-id (:id root-msg)
                  :oneof-field (:name field)
                  :proto-source (:source child)
                  :doc-file (str "proto/" (:id child) ".md")
                  :fields (mapv (fn [f]
                                  (cond-> {:name (:name f)
                                           :type (name (:type f))}
                                    (:constraints f) (assoc :constraints (:constraints f))
                                    (:interaction f) (assoc :interaction (:interaction f))))
                                (:fields child))
                  :interaction (:interaction child)}]))]
    cmd))

(defn- resolve-lrf-calib-commands
  "Special handling for cmd.Lrf_calib.Root which has channel dispatch (day/heat)
   instead of the standard cmd oneof."
  [msgs root-msg]
  ;; Lrf_calib.Root has oneof 'channel' with 'day' and 'heat' fields,
  ;; both pointing to cmd.Lrf_calib.Offsets (a group with oneof 'cmd')
  (for [channel-field (:fields root-msg)
        :let [offsets-msg (get msgs (:type-ref channel-field))]
        :when (and offsets-msg (group-message? offsets-msg))
        leaf-field (:fields offsets-msg)
        :let [leaf (get msgs (:type-ref leaf-field))]
        :when (and leaf (not (routing-container? leaf)))]
    {:id (:id leaf)
     :subsystem "lrf-calib"
     :command (str (snake->kebab (:name channel-field))
                   "/" (snake->kebab (:name leaf-field)))
     :path (str "cmd/lrf-calib/"
                (snake->kebab (:name channel-field))
                "/" (snake->kebab (:name leaf-field)))
     :root-id (:id root-msg)
     :oneof-field (str (:name channel-field) "." (:name leaf-field))
     :proto-source (:source leaf)
     :doc-file (str "proto/" (:id leaf) ".md")
     :fields (mapv (fn [f]
                     (cond-> {:name (:name f)
                              :type (name (:type f))}
                       (:constraints f) (assoc :constraints (:constraints f))
                       (:interaction f) (assoc :interaction (:interaction f))))
                   (:fields leaf))
     :interaction (:interaction leaf)}))

(defn extract-endpoints
  "Extract all leaf command endpoints from the database."
  [db config]
  (let [msgs (:messages db)
        cmd-root (get msgs "cmd.Root")
        cmd-skip (:cmd-skip-fields config #{})
        endpoints (for [root-field (:fields cmd-root)
                        :when (not (cmd-skip (:name root-field)))
                        :let [subsystem-root (get msgs (:type-ref root-field))]
                        :when (and subsystem-root (root-message? subsystem-root))
                        :let [subsystem-kebab (snake->kebab (:name root-field))
                              base-path (str "cmd/" subsystem-kebab)]
                        ep (if (= "cmd.Lrf_calib.Root" (:id subsystem-root))
                             (resolve-lrf-calib-commands msgs subsystem-root)
                             (resolve-leaf-commands msgs subsystem-root
                                                    subsystem-kebab base-path))]
                    ep)
        ;; Build subsystem summary
        by-subsystem (group-by :subsystem endpoints)
        subsystems (for [[sub eps] (sort by-subsystem)]
                     {:name sub
                      :root-id (:root-id (first eps))
                      :endpoint-count (count eps)})]
    {:endpoints (vec (sort-by :path endpoints))
     :subsystems (vec subsystems)}))

;; ============================================================================
;; Signal Extraction
;; ============================================================================

(defn make-signal-name
  "Create a camelCase signal name from subsystem prefix and field name.
   prefix: \"cameraDay\", field: \"focus_pos\" → \"cameraDayFocusPos\""
  [prefix field-name]
  (let [field-camel (snake->camel field-name)
        ;; Capitalize first letter of field camel for concat
        capitalized (str (str/upper-case (subs field-camel 0 1))
                         (subs field-camel 1))]
    (str prefix capitalized)))

(defn extract-signals
  "Extract all signals from JonGUIState subsystem fields."
  [db config]
  (let [msgs (:messages db)
        state-msg (get msgs "ser.JonGUIState")
        skip-fields (:root-skip-fields config #{})
        results
        (for [f (:fields state-msg)
              :when (and (= :message (:type f))
                         (not (skip-fields (:name f))))
              :let [sub-msg (get msgs (:type-ref f))
                    prefix (snake->camel (:name f))]]
          {:subsystem-field (:name f)
           :prefix prefix
           :sub-msg sub-msg
           :signals
           (for [sf (:fields sub-msg)
                 :when (not= :message (:type sf))]
             {:signal-name (make-signal-name prefix (:name sf))
              :subsystem-field (:name f)
              :subsystem-prefix prefix
              :field-name (:name sf)
              :proto-message (:id sub-msg)
              :proto-field-number (:number sf)
              :type (name (:type sf))
              :constraints (:constraints sf)
              :doc-file (str "proto/" (:id sub-msg) ".md")})})
        all-signals (mapcat :signals results)
        subsystems (for [r results]
                     {:field-in-root (:subsystem-field r)
                      :signal-prefix (:prefix r)
                      :proto-message (:id (:sub-msg r))
                      :signal-count (count (:signals r))})]
    {:signals (vec (sort-by :signal-name all-signals))
     :subsystems (vec subsystems)
     :derived-signals (or (:derived-signals config) [])}))

;; ============================================================================
;; Sub-Signal Extraction (deeply nested: power modules, heater channels, etc.)
;; ============================================================================

(defn extract-sub-signals
  "Extract signals from nested message fields (power s0-s7, heater channels, etc.)."
  [db config]
  (let [msgs (:messages db)
        state-msg (get msgs "ser.JonGUIState")
        skip-fields (:root-skip-fields config #{})
        nested-entries
        (for [sf (:fields state-msg)
              :when (and (= :message (:type sf))
                         (not (skip-fields (:name sf))))
              :let [sub-msg (get msgs (:type-ref sf))
                    sub-prefix (snake->camel (:name sf))]
              nf (:fields sub-msg)
              :when (= :message (:type nf))
              :let [nested-msg (get msgs (:type-ref nf))]
              :when nested-msg]
          {:parent-subsystem (:name sf)
           :parent-field (:name nf)
           :parent-message (:id sub-msg)
           :nested-message (:id nested-msg)
           :signals
           (vec (for [leaf (:fields nested-msg)
                      :when (not= :message (:type leaf))]
                  {:signal-name (make-signal-name
                                  (str sub-prefix
                                       (str/upper-case (subs (snake->camel (:name nf)) 0 1))
                                       (subs (snake->camel (:name nf)) 1))
                                  (:name leaf))
                   :field-name (:name leaf)
                   :type (name (:type leaf))
                   :constraints (:constraints leaf)}))})]
    {:nested-signals (vec nested-entries)}))

;; ============================================================================
;; Reverse Index
;; ============================================================================

(defn build-reverse-index
  "Build reverse index linking doc files to endpoints and signals."
  [endpoints-data signals-data sub-signals-data]
  (let [;; By doc file
        ep-by-doc (group-by :doc-file (:endpoints endpoints-data))
        sig-by-doc (group-by :doc-file (:signals signals-data))

        ;; Build by-doc-file map
        all-doc-files (set (concat (keys ep-by-doc) (keys sig-by-doc)))
        by-doc-file
        (into {}
              (for [df all-doc-files]
                [df (cond-> {}
                      (seq (get ep-by-doc df))
                      (assoc :endpoints (mapv :path (get ep-by-doc df)))

                      (seq (get sig-by-doc df))
                      (assoc :signals (mapv :signal-name (get sig-by-doc df)))

                      ;; Cross-link: endpoints → related signals via interaction.related-state
                      (seq (get ep-by-doc df))
                      (as-> m
                        (let [related-state-docs
                              (->> (get ep-by-doc df)
                                   (mapcat #(get-in % [:interaction :related-state]))
                                   (map #(str "proto/" % ".md"))
                                   distinct)]
                          (if (seq related-state-docs)
                            (assoc m :related-signal-docs (vec related-state-docs))
                            m)))

                      ;; Cross-link: signals → related endpoints via endpoint interaction.related-state
                      (seq (get sig-by-doc df))
                      (as-> m
                        (let [related-eps
                              (->> (:endpoints endpoints-data)
                                   (filter #(some (fn [rs]
                                                    (= df (str "proto/" rs ".md")))
                                                  (get-in % [:interaction :related-state])))
                                   (map :path)
                                   distinct)]
                          (if (seq related-eps)
                            (assoc m :related-endpoints (vec related-eps))
                            m))))]))

        ;; By endpoint
        by-endpoint
        (into {}
              (for [ep (:endpoints endpoints-data)]
                [(:path ep)
                 (cond-> {:doc-file (:doc-file ep)}
                   (seq (get-in ep [:interaction :related-state]))
                   (assoc :related-state-docs
                          (mapv #(str "proto/" % ".md")
                                (get-in ep [:interaction :related-state]))))]))

        ;; By signal
        by-signal
        (into {}
              (for [sig (:signals signals-data)]
                [(:signal-name sig)
                 (cond-> {:doc-file (:doc-file sig)}
                   ;; Find endpoints whose related-state includes this signal's message
                   true
                   (as-> m
                     (let [related-eps
                           (->> (:endpoints endpoints-data)
                                (filter #(some (fn [rs]
                                                 (= (:proto-message sig) rs))
                                               (get-in % [:interaction :related-state])))
                                (map :doc-file)
                                distinct)]
                       (if (seq related-eps)
                         (assoc m :related-cmd-docs (vec related-eps))
                         m))))]))]

    {:by-doc-file by-doc-file
     :by-endpoint by-endpoint
     :by-signal by-signal}))

;; ============================================================================
;; Manifest Generation (top-level)
;; ============================================================================

(defn load-config
  "Load manifest config from EDN file."
  [path]
  (if (.exists (io/file path))
    (edn/read-string (slurp path))
    (do (t/log! :warn ["Config not found, using defaults:" path])
        {:root-skip-fields #{}
         :cmd-skip-fields #{}
         :derived-signals []})))

(defn generate-manifests
  "Generate all manifest files from proto-db.edn.

   Options:
   - :db-path     — path to proto-db.edn
   - :config-path — path to manifest-config.edn
   - :output-dir  — path to output manifests directory
   - :git-sha     — optional git commit SHA for metadata"
  [{:keys [db-path config-path output-dir git-sha]
    :or {db-path "docs/.protodoc/proto-db.edn"
         config-path "docs/.protodoc/manifest-config.edn"
         output-dir "output/manifests"}}]
  (let [db (edn/read-string (slurp db-path))
        config (load-config config-path)
        now (str (java.time.Instant/now))

        ;; Extract
        endpoints-data (extract-endpoints db config)
        signals-data (extract-signals db config)
        sub-signals-data (extract-sub-signals db config)
        reverse-index-data (build-reverse-index endpoints-data signals-data sub-signals-data)

        ;; Add metadata
        endpoints-manifest {:version "1.0.0"
                            :generated-at now
                            :protogen-commit (or git-sha "unknown")
                            :endpoints (:endpoints endpoints-data)
                            :subsystems (:subsystems endpoints-data)}

        signals-manifest {:version "1.0.0"
                          :generated-at now
                          :signals (:signals signals-data)
                          :subsystems (:subsystems signals-data)
                          :derived-signals (:derived-signals signals-data)}

        sub-signals-manifest {:version "1.0.0"
                              :generated-at now
                              :nested-signals (:nested-signals sub-signals-data)}

        reverse-index-manifest {:version "1.0.0"
                                :generated-at now
                                :by-doc-file (:by-doc-file reverse-index-data)
                                :by-endpoint (:by-endpoint reverse-index-data)
                                :by-signal (:by-signal reverse-index-data)}

        ;; Write
        out-dir (io/file output-dir)]
    (.mkdirs out-dir)

    (doseq [[filename data] [["endpoints.json" endpoints-manifest]
                              ["signals.json" signals-manifest]
                              ["sub-signals.json" sub-signals-manifest]
                              ["reverse-index.json" reverse-index-manifest]]]
      (let [f (io/file out-dir filename)]
        (spit f (json/write-str data :key-fn #(if (keyword? %)
                                                 (cond-> (name %)
                                                   (namespace %)
                                                   (->> (str (namespace %) "/")))
                                                 (str %))
                                 :escape-slash false))
        (t/log! :info ["Wrote" (.getPath f)])))

    ;; Summary
    (t/log! :info ["Generated manifests:"
                   (count (:endpoints endpoints-data)) "endpoints,"
                   (count (:signals signals-data)) "signals,"
                   (count (:nested-signals sub-signals-data)) "sub-signal groups"])

    {:endpoints endpoints-manifest
     :signals signals-manifest
     :sub-signals sub-signals-manifest
     :reverse-index reverse-index-manifest}))
