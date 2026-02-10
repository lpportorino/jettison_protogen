#!/usr/bin/env bb
;; Lint proto documentation for quality issues
;; Usage: bb proto-lint.clj [db-path]

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

;; Semantic type / field type compatibility matrix
(def compatible-types
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

(def vague-patterns
  [#"(?i)\bunclear\b" #"(?i)\bTBD\b" #"(?i)\bTODO\b" #"(?i)\bunknown\b" #"\?\?"])

;; Rule: enum values with nil/empty/'-' descriptions when parent has description
(defn lint-enum-values [db]
  (for [[_ enum] (:enums db)
        :when (seq (:description enum))
        :let [vals (:values enum)
              total (count vals)
              undoc (count (filter #(let [d (:description %)] (or (nil? d) (str/blank? d) (= d "-"))) vals))]
        :when (and (pos? total) (pos? undoc))]
    {:rule :enum-values-undocumented :severity :warning :id (:id enum)
     :message (format "%d/%d values undocumented" undoc total)}))

;; Rule: fields with interaction metadata but no description
(defn lint-field-metadata [db]
  (for [[_ msg] (:messages db)
        field (:fields msg)
        :when (and (seq (:interaction field)) (not (seq (:description field))))]
    {:rule :field-metadata-without-description :severity :warning :id (:id msg)
     :field (:name field) :message (format "field '%s' has interaction metadata but no description" (:name field))}))

;; Rule: incompatible semantic-type + field-type
(defn lint-semantic-type [db]
  (for [[_ msg] (:messages db)
        field (:fields msg)
        :let [sem (get-in field [:interaction :semantic-type])
              ft (:type field)]
        :when sem
        :let [compat (get compatible-types sem)]
        :when (and compat (not (contains? compat ft)))]
    {:rule :semantic-type-mismatch :severity :error :id (:id msg)
     :field (:name field) :message (format "field '%s': %s incompatible with %s" (:name field) (name ft) (name sem))}))

;; Rule: has :category but missing :ui-pattern or :feedback
(defn lint-interaction-incomplete [db]
  (for [[_ msg] (:messages db)
        :let [inter (:interaction msg)]
        :when (:category inter)
        :let [missing (cond-> [] (not (:ui-pattern inter)) (conj ":ui-pattern") (not (:feedback inter)) (conj ":feedback"))]
        :when (seq missing)]
    {:rule :interaction-incomplete :severity :warning :id (:id msg)
     :message (str "has :category but missing " (str/join ", " missing))}))

;; Rule: related-state/related-commands pointing to non-existent IDs
(defn lint-invalid-refs [db]
  (let [all-ids (into (set (keys (:messages db))) (keys (:enums db)))]
    (for [[_ msg] (:messages db)
          :let [inter (:interaction msg)]
          :when inter
          ref-id (concat (:related-state inter) (:related-commands inter))
          :when (not (contains? all-ids ref-id))]
      {:rule :invalid-references :severity :error :id (:id msg)
       :message (format "reference '%s' not found in database" ref-id)})))

;; Rule: descriptions with vague/placeholder text
(defn lint-vague [db]
  (let [check (fn [id desc]
                (when (seq desc)
                  (some #(when (re-find % desc)
                           {:rule :description-vague :severity :info :id id
                            :message (format "description contains vague text matching %s" %)})
                        vague-patterns)))]
    (concat
     (keep (fn [[_ msg]] (check (:id msg) (:description msg))) (:messages db))
     (keep (fn [[_ enum]] (check (:id enum) (:description enum))) (:enums db)))))

;; Rule: fields with constraints but no description
(defn lint-constrained [db]
  (for [[_ msg] (:messages db)
        field (:fields msg)
        :when (and (seq (:constraints field)) (not (seq (:description field))))]
    {:rule :constrained-fields-undocumented :severity :warning :id (:id msg)
     :field (:name field) :message (format "field '%s' has constraints %s but no description" (:name field) (pr-str (:constraints field)))}))

;; Run all rules and format output
(let [[db-path] *command-line-args*
      db-path (or db-path "docs/.protodoc/proto-db.edn")]
  (let [db-file (clojure.java.io/file db-path)]
    (if-not (.exists db-file)
      (do (println "Database not found:" db-path) (System/exit 1))
      (let [db (edn/read-string (slurp db-file))
            findings (->> [(lint-enum-values db)
                           (lint-field-metadata db)
                           (lint-semantic-type db)
                           (lint-interaction-incomplete db)
                           (lint-invalid-refs db)
                           (lint-vague db)
                           (lint-constrained db)]
                          (apply concat)
                          (sort-by (juxt (comp {:error 0 :warning 1 :info 2} :severity)
                                        :rule :id :field))
                          vec)
            by-sev (group-by :severity findings)
            summary {:error (count (get by-sev :error []))
                     :warning (count (get by-sev :warning []))
                     :info (count (get by-sev :info []))}]
        (println "Proto Documentation Lint Report")
        (println "================================")
        (doseq [[sev label] [[:error "ERRORS"] [:warning "WARNINGS"] [:info "INFO"]]]
          (when-let [sev-findings (seq (get by-sev sev))]
            (println)
            (println (format "%s (%d)" label (count sev-findings)))
            (let [by-rule (group-by :rule sev-findings)]
              (doseq [[rule rule-findings] (sort-by key by-rule)]
                (println (format "  %s (%d)" rule (count rule-findings)))
                (doseq [f (sort-by (juxt :id :field) rule-findings)]
                  (if (:field f)
                    (println (format "    %s #%s: %s" (:id f) (:field f) (:message f)))
                    (println (format "    %s: %s" (:id f) (:message f)))))))))
        (when (empty? findings)
          (println)
          (println "No issues found."))
        (println)
        (let [n-err (count (get by-sev :error))
              n-warn (count (get by-sev :warning))
              n-info (count (get by-sev :info))]
          (println (format "Summary: %d errors, %d warnings, %d info (7 rules checked)"
                           n-err n-warn n-info))
          (when (pos? n-err)
            (System/exit 1)))))))
