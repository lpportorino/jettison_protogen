(ns protodoc.render
  "Render proto-db to Obsidian-compatible markdown files."
  (:require [selmer.parser :as selmer]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

;; Configure Selmer to use our templates directory
(selmer/set-resource-path! (io/resource "templates"))

(defn- format-constraints
  "Format constraints map as human-readable string."
  [constraints]
  (when constraints
    (let [parts (cond-> []
                  (:gt constraints)
                  (conj (str "> " (:gt constraints)))

                  (:gte constraints)
                  (conj (str ">= " (:gte constraints)))

                  (:lt constraints)
                  (conj (str "< " (:lt constraints)))

                  (:lte constraints)
                  (conj (str "<= " (:lte constraints)))

                  (:min-len constraints)
                  (conj (str "min-len: " (:min-len constraints)))

                  (:max-len constraints)
                  (conj (str "max-len: " (:max-len constraints)))

                  (:pattern constraints)
                  (conj (str "pattern: " (:pattern constraints)))

                  (:defined-only constraints)
                  (conj "defined enum value only")

                  (:not-in constraints)
                  (conj (str "not in: " (str/join ", " (:not-in constraints))))

                  (:required constraints)
                  (conj "required"))]
      (when (seq parts)
        (str/join ", " parts)))))

(defn- format-type
  "Format field type with optional reference link."
  [{:keys [type type-ref repeated]}]
  (let [base-type (if type-ref
                    (str "[[proto/" type-ref "]]")
                    (name type))]
    (if repeated
      (str "repeated " base-type)
      base-type)))

;; ============================================================================
;; Interaction Metadata Formatting
;; ============================================================================

(defn- format-field-interaction
  "Format field-level interaction metadata for template."
  [interaction]
  (when (seq interaction)
    {:semantic-type (some-> (:semantic-type interaction) name)
     :unit (:unit interaction)
     :precision (:precision interaction)
     :display-format (:display-format interaction)
     :presets (when (:presets interaction)
                (str/join ", " (map str (:presets interaction))))
     :has-any true}))

(defn- format-interaction-meta
  "Format message-level interaction metadata for template."
  [interaction]
  (when (seq interaction)
    {:purpose (:purpose interaction)
     :category (some-> (:category interaction) name)
     :ui-pattern (some-> (:ui-pattern interaction) name)
     :feedback (some-> (:feedback interaction) name)
     :timeout-ms (:timeout-ms interaction)
     :related-state (when (seq (:related-state interaction))
                      (mapv #(str "[[proto/" % "]]") (:related-state interaction)))
     :related-commands (when (seq (:related-commands interaction))
                         (mapv #(str "[[proto/" % "]]") (:related-commands interaction)))
     :preconditions (:preconditions interaction)
     :notes (:notes interaction)
     :has-any true}))

(defn- message-to-template-data
  "Transform message to data for template rendering."
  [message]
  (let [interaction (format-interaction-meta (:interaction message))
        fields-data (for [field (:fields message)]
                      (let [field-interaction (format-field-interaction (:interaction field))]
                        {:number (:number field)
                         :name (:name field)
                         :type (format-type field)
                         :constraints (format-constraints (:constraints field))
                         :description (:description field)
                         :interaction field-interaction
                         :has-field-note (or (:description field) (:has-any field-interaction))}))]
    {:id (:id message)
     :name (:name message)
     :package (:package message)
     :source (:source message)
     :description (:description message)
     :fields fields-data
     :oneofs (:oneofs message)
     :interaction interaction
     :has-interaction (boolean interaction)
     :has-field-notes (or (some :description (:fields message))
                          (some :interaction (:fields message)))}))

(defn- enum-to-template-data
  "Transform enum to data for template rendering."
  [enum]
  {:id (:id enum)
   :name (:name enum)
   :package (:package enum)
   :source (:source enum)
   :description (:description enum)
   :values (for [val (:values enum)]
             {:number (:number val)
              :name (:name val)
              :description (:description val)})})

(defn- output-path-for-message
  "Generate output file path for a message.
   Uses flat structure in proto/ subdirectory with full ID as filename."
  [output-dir message]
  (io/file output-dir "proto" (str (:id message) ".md")))

(defn- output-path-for-enum
  "Generate output file path for an enum.
   Uses flat structure in proto/ subdirectory with full ID as filename."
  [output-dir enum]
  (io/file output-dir "proto" (str (:id enum) ".md")))

(defn render-message
  "Render a single message to markdown string."
  [message]
  (selmer/render-file "message.md.selmer"
                      (message-to-template-data message)))

(defn render-enum
  "Render a single enum to markdown string."
  [enum]
  (selmer/render-file "enum.md.selmer"
                      (enum-to-template-data enum)))

(defn- truncate-description
  "Truncate description to max-len characters, adding ellipsis if needed."
  [desc max-len]
  (when desc
    (if (<= (count desc) max-len)
      desc
      (str (subs desc 0 (- max-len 3)) "..."))))

(defn- add-short-description
  "Add truncated short-description to a message or enum map."
  [item]
  (assoc item :short-description (truncate-description (:description item) 120)))

(defn render-index
  "Render the vault index."
  [db]
  (let [messages-by-package (group-by :package (vals (:messages db)))
        packages (sort (keys messages-by-package))]
    (selmer/render-file "index.md.selmer"
                        {:packages (for [pkg packages]
                                     {:name pkg
                                      :messages (->> (get messages-by-package pkg)
                                                     (sort-by :name)
                                                     (map add-short-description))})
                         :enums (->> (vals (:enums db))
                                     (sort-by :name)
                                     (map add-short-description))
                         :stats {:message-count (count (:messages db))
                                 :enum-count (count (:enums db))
                                 :field-count (->> (:messages db)
                                                   vals
                                                   (mapcat :fields)
                                                   count)}})))

(defn- ensure-parent-dirs
  "Ensure parent directories exist for file."
  [file]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent)))

(defn render-all
  "Render all messages and enums to output directory."
  [db output-dir]
  (t/log! :info ["Rendering to" output-dir])

  ;; Render messages
  (doseq [[_ message] (:messages db)]
    (let [file (output-path-for-message output-dir message)]
      (ensure-parent-dirs file)
      (spit file (render-message message))))
  (t/log! :info ["Rendered" (count (:messages db)) "messages"])

  ;; Render enums
  (doseq [[_ enum] (:enums db)]
    (let [file (output-path-for-enum output-dir enum)]
      (ensure-parent-dirs file)
      (spit file (render-enum enum))))
  (t/log! :info ["Rendered" (count (:enums db)) "enums"])

  ;; Render index
  (let [index-file (io/file output-dir "index.md")]
    (ensure-parent-dirs index-file)
    (spit index-file (render-index db)))
  (t/log! :info "Rendered index.md"))
