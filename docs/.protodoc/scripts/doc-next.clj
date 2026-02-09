#!/usr/bin/env bb
;; Show next undocumented item with context for documentation
;; Usage: bb doc-next.clj [db-path]

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn has-real-description?
  "Check if item has a real description (not just placeholder text)."
  [item]
  (let [desc (:description item)]
    (and (seq desc)
         (not (str/includes? desc "*No description yet.*"))
         (not (str/includes? desc "No description")))))

(defn undocumented-messages
  "Get all messages without real descriptions."
  [db]
  (->> (:messages db)
       vals
       (remove has-real-description?)
       (sort-by :id)))

(defn undocumented-fields
  "Get all fields without descriptions, grouped by message."
  [db]
  (->> (:messages db)
       vals
       (filter has-real-description?)  ; Only documented messages
       (mapcat (fn [msg]
                 (->> (:fields msg)
                      (remove has-real-description?)
                      (map #(assoc % :message-id (:id msg))))))
       (sort-by (juxt :message-id :number))))

(defn get-module
  "Extract module name from message id (e.g., 'cmd.PMU.Start' -> 'PMU')."
  [id]
  (-> id (str/split #"\.") second))

(defn group-by-module
  "Group messages by their module."
  [messages]
  (->> messages
       (group-by #(get-module (:id %)))
       (sort-by first)))

(defn format-type
  "Format field type for display."
  [field]
  (let [t (name (:type field))
        ref (:type-ref field)]
    (if ref
      (str t " -> " ref)
      t)))

(defn format-constraints
  "Format constraints for display."
  [constraints]
  (when (seq constraints)
    (->> constraints
         (keep (fn [[k v]]
                 (when (not= k :example)
                   (case k
                     :gte (str ">= " v)
                     :gt (str "> " v)
                     :lte (str "<= " v)
                     :lt (str "< " v)
                     :minLen (str "minLen=" v)
                     :maxLen (str "maxLen=" v)
                     :pattern (str "pattern=\"" v "\"")
                     :required "required"
                     :in (str "in=" v)
                     (str (name k) "=" v)))))
         (str/join ", "))))

(defn print-field
  "Print a single field with context."
  [field]
  (let [constraints (format-constraints (:constraints field))
        type-str (format-type field)]
    (println (str "    #" (:number field) " " (:name field) ": " type-str
                  (when constraints (str " [" constraints "]"))))))

(defn print-message-context
  "Print full context for a message."
  [msg]
  (println)
  (println "=========================================")
  (println "NEXT: " (:id msg))
  (println "=========================================")
  (println "Source:" (:source msg))
  (println "Package:" (:package msg))

  (when-let [fields (seq (:fields msg))]
    (println)
    (println "Fields:")
    (doseq [field fields]
      (print-field field)))

  (when-let [oneofs (seq (:oneofs msg))]
    (println)
    (println "Oneofs:")
    (doseq [oneof oneofs]
      (println (str "  - " (:name oneof)
                    (when (:required oneof) " (required)")
                    ": " (count (:fields oneof)) " options"))))

  (println)
  (println "--- Questions to Answer ---")
  (println)
  (println "1. PURPOSE: What does this message do?")
  (println)
  (println "2. CATEGORY: Which category?")
  (println "   :sensor :actuator :settings :status :lifecycle :diagnostic")
  (println)
  (println "3. UI PATTERN: How would this be displayed?")
  (println "   :toggle :action-button :slider :stepper :indicator :enum-picker")
  (println "   :slider-with-steppers :slider-with-presets :directional-mover")
  (println)
  (println "4. FEEDBACK: How does UI respond?")
  (println "   :fire-and-forget :pending-timeout :poll-confirm :optimistic-visual")
  (println)
  (println "5. RELATED STATE: Which ser.* message shows this state?")
  (println)
  (println "6. RELATED COMMANDS: What other commands are related?")
  (println)
  (when (seq (:fields msg))
    (println "7. FIELDS: For each field above:")
    (println "   - Semantic type? :normalized :angle :percentage :temperature :voltage etc.")
    (println "   - Unit? (%, V, degrees, ms, etc.)")
    (println "   - Precision? (decimal places)")
    (println)))

(defn pct [n total]
  (if (zero? total) 100.0 (* 100.0 (/ n total))))

(let [[db-path] *command-line-args*
      db-path (or db-path "docs/.protodoc/proto-db.edn")]
  (let [db-file (clojure.java.io/file db-path)]
    (if-not (.exists db-file)
      (do
        (println "Database not found:" db-path)
        (System/exit 1))
      (let [db (edn/read-string (slurp db-file))
            messages (vals (:messages db))
            undoc-msgs (undocumented-messages db)
            undoc-fields (undocumented-fields db)
            by-module (group-by-module undoc-msgs)]

        (println "Proto Documentation - What's Next")
        (println "==================================")
        (println)

        ;; Summary
        (let [msg-total (count messages)
              msg-undoc (count undoc-msgs)
              msg-doc (- msg-total msg-undoc)
              field-undoc (count undoc-fields)]
          (println (format "Messages:  %d/%d documented (%.0f%%)"
                           msg-doc msg-total (pct msg-doc msg-total)))
          (println (format "           %d messages need documentation" msg-undoc))
          (println (format "Fields:    %d fields in documented messages need notes" field-undoc))
          (println))

        ;; Undocumented by module
        (when (seq by-module)
          (println "Undocumented Messages by Module:")
          (doseq [[module msgs] by-module]
            (println (format "  %-15s %d messages" module (count msgs))))
          (println))

        ;; Show next message
        (if-let [next-msg (first undoc-msgs)]
          (print-message-context next-msg)
          (if (seq undoc-fields)
            (do
              (println "All messages documented!")
              (println)
              (println "Fields needing documentation:")
              (let [by-msg (group-by :message-id (take 10 undoc-fields))]
                (doseq [[msg-id fields] by-msg]
                  (println (str "  " msg-id ":"))
                  (doseq [f fields]
                    (println (str "    - " (:name f) " (#" (:number f) ")"))))))
            (println "All documentation complete!")))))))
