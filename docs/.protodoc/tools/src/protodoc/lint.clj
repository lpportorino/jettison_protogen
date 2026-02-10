(ns protodoc.lint
  "Lint proto documentation for quality issues beyond simple coverage.

   Each rule is a function (db) -> [finding ...] where finding is:
   {:rule     :rule-keyword
    :severity :error | :warning | :info
    :id       \"message.or.enum.id\"
    :field    \"field_name\" or nil
    :message  \"Human-readable issue description\"}"
  (:require [clojure.string :as str]))

;; ============================================================================
;; Semantic type / field type compatibility matrix
;; ============================================================================

(def semantic-type-compatible-field-types
  "Map of semantic-type -> set of compatible proto field types.
   nil means compatible with all field types."
  {:normalized       #{:double :float}
   :angle            #{:double :float :int32 :uint32}
   :cardinal         #{:double :float :int32 :uint32}
   :percentage       #{:double :float :int32 :uint32}
   :coordinate-geo   #{:double :float}
   :coordinate-viewport #{:double :float}
   :speed            #{:double :float}
   :voltage          #{:double :float}
   :current          #{:double :float}
   :power            #{:double :float}
   :temperature      #{:double :float :int32}
   :distance         #{:double :float :uint32 :int32 :uint64 :int64}
   :duration         #{:uint32 :uint64 :int32 :int64 :double :float}
   :count            #{:uint32 :int32 :uint64 :int64}
   :timestamp        #{:uint64 :int64 :uint32 :double}
   :identifier       #{:uint32 :int32 :uint64 :int64 :string}
   :enum-label       #{:enum}
   :toggle-state     #{:bool}
   :raw              nil})

;; ============================================================================
;; Vague description patterns
;; ============================================================================

(def ^:private vague-patterns
  "Regex patterns that indicate a vague or placeholder description."
  [#"(?i)\bunclear\b"
   #"(?i)\bTBD\b"
   #"(?i)\bTODO\b"
   #"(?i)\bunknown\b"
   #"\?\?"])

;; ============================================================================
;; Individual lint rules
;; ============================================================================

(defn- enum-values-undocumented
  "Enum values with nil/empty/'-' descriptions when parent enum has a description."
  [db]
  (for [[_id enum] (:enums db)
        :when (seq (:description enum))
        :let [values (:values enum)
              total (count values)
              undoc (count (filter (fn [v]
                                    (let [d (:description v)]
                                      (or (nil? d) (str/blank? d) (= d "-"))))
                                  values))]
        :when (and (pos? total) (pos? undoc))]
    {:rule     :enum-values-undocumented
     :severity :warning
     :id       (:id enum)
     :field    nil
     :message  (format "%d/%d values undocumented" undoc total)}))

(defn- field-metadata-without-description
  "Fields with :interaction metadata but no text description."
  [db]
  (for [[_id msg] (:messages db)
        field (:fields msg)
        :when (and (seq (:interaction field))
                   (not (seq (:description field))))]
    {:rule     :field-metadata-without-description
     :severity :warning
     :id       (:id msg)
     :field    (:name field)
     :message  (format "field '%s' has interaction metadata but no description"
                       (:name field))}))

(defn- semantic-type-mismatch
  "Incompatible semantic-type + field-type combinations."
  [db]
  (for [[_id msg] (:messages db)
        field (:fields msg)
        :let [sem-type (get-in field [:interaction :semantic-type])
              field-type (:type field)]
        :when sem-type
        :let [compatible (get semantic-type-compatible-field-types sem-type)]
        :when (and compatible (not (contains? compatible field-type)))]
    {:rule     :semantic-type-mismatch
     :severity :error
     :id       (:id msg)
     :field    (:name field)
     :message  (format "field '%s': %s incompatible with %s"
                       (:name field) (name field-type) (name sem-type))}))

(defn- interaction-incomplete
  "Messages with :category but missing :ui-pattern or :feedback."
  [db]
  (for [[_id msg] (:messages db)
        :let [inter (:interaction msg)]
        :when (:category inter)
        :let [missing (cond-> []
                        (not (:ui-pattern inter)) (conj ":ui-pattern")
                        (not (:feedback inter))   (conj ":feedback"))]
        :when (seq missing)]
    {:rule     :interaction-incomplete
     :severity :warning
     :id       (:id msg)
     :field    nil
     :message  (str "has :category but missing " (str/join ", " missing))}))

(defn- invalid-references
  "Related-state/related-commands pointing to non-existent IDs."
  [db]
  (let [all-ids (into (set (keys (:messages db)))
                      (keys (:enums db)))]
    (for [[_id msg] (:messages db)
          :let [inter (:interaction msg)]
          :when inter
          ref-id (concat (:related-state inter) (:related-commands inter))
          :when (not (contains? all-ids ref-id))]
      {:rule     :invalid-references
       :severity :error
       :id       (:id msg)
       :field    nil
       :message  (format "reference '%s' not found in database" ref-id)})))

(defn- description-vague
  "Descriptions containing placeholder/vague text."
  [db]
  (let [check-desc (fn [id desc]
                     (when (seq desc)
                       (some (fn [pat]
                               (when (re-find pat desc)
                                 {:rule     :description-vague
                                  :severity :info
                                  :id       id
                                  :field    nil
                                  :message  (format "description contains vague text matching %s" pat)}))
                             vague-patterns)))]
    (concat
     ;; Check message descriptions
     (keep (fn [[_id msg]] (check-desc (:id msg) (:description msg)))
           (:messages db))
     ;; Check enum descriptions
     (keep (fn [[_id enum]] (check-desc (:id enum) (:description enum)))
           (:enums db)))))

(defn- constrained-fields-undocumented
  "Fields with validation constraints but no description."
  [db]
  (for [[_id msg] (:messages db)
        field (:fields msg)
        :when (and (seq (:constraints field))
                   (not (seq (:description field))))]
    {:rule     :constrained-fields-undocumented
     :severity :warning
     :id       (:id msg)
     :field    (:name field)
     :message  (format "field '%s' has constraints %s but no description"
                       (:name field) (pr-str (:constraints field)))}))

;; ============================================================================
;; Rule registry and orchestrator
;; ============================================================================

(def default-rules
  "Registry of all lint rules."
  {:enum-values-undocumented        enum-values-undocumented
   :field-metadata-without-description field-metadata-without-description
   :semantic-type-mismatch          semantic-type-mismatch
   :interaction-incomplete          interaction-incomplete
   :invalid-references              invalid-references
   :description-vague               description-vague
   :constrained-fields-undocumented constrained-fields-undocumented})

(defn lint
  "Run lint rules against a proto database.

   Options:
   - :rules    - set of rule keywords to run (default: all)
   - :exclude  - set of rule keywords to skip (default: none)
   - :severity - minimum severity to include, #{:error :warning :info} (default: all)

   Returns {:findings [...] :summary {:error N :warning N :info N}}"
  [db & {:keys [rules exclude severity]}]
  (let [active-rules (cond-> default-rules
                       rules   (select-keys rules)
                       exclude (#(apply dissoc % exclude)))
        all-findings (->> (vals active-rules)
                          (mapcat #(% db))
                          (sort-by (juxt (comp {:error 0 :warning 1 :info 2} :severity)
                                         :rule :id :field)))
        filtered (if severity
                   (filter #(contains? severity (:severity %)) all-findings)
                   all-findings)
        summary (merge {:error 0 :warning 0 :info 0}
                       (update-vals (group-by :severity filtered) count))]
    {:findings (vec filtered)
     :summary summary}))

(defn format-findings
  "Format lint results as a human-readable report string."
  [{:keys [findings summary]}]
  (let [sb (StringBuilder.)
        _ (.append sb "Proto Documentation Lint Report\n")
        _ (.append sb "================================\n")
        grouped-by-sev (group-by :severity findings)
        rules-checked (count default-rules)]
    (doseq [[sev label] [[:error "ERRORS"] [:warning "WARNINGS"] [:info "INFO"]]]
      (when-let [sev-findings (seq (get grouped-by-sev sev))]
        (.append sb (format "\n%s (%d)\n" label (count sev-findings)))
        (let [by-rule (group-by :rule sev-findings)]
          (doseq [[rule rule-findings] (sort-by key by-rule)]
            (.append sb (format "  %s (%d)\n" rule (count rule-findings)))
            (doseq [f (sort-by (juxt :id :field) rule-findings)]
              (if (:field f)
                (.append sb (format "    %s #%s: %s\n" (:id f) (:field f) (:message f)))
                (.append sb (format "    %s: %s\n" (:id f) (:message f)))))))))
    (when (empty? findings)
      (.append sb "\nNo issues found.\n"))
    (.append sb (format "\nSummary: %d errors, %d warnings, %d info (%d rules checked)\n"
                        (:error summary) (:warning summary) (:info summary) rules-checked))
    (str sb)))
