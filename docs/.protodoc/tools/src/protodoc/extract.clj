(ns protodoc.extract
  "Extract user content from existing markdown files for roundtrip preservation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

;; Extraction rules:
;; - ## Description → :description (raw markdown blob until next ## heading)
;; - ## Interaction → :interaction (structured metadata with subsections)
;; - ### {name} (#{number}) → field :description (raw markdown blob until next ### or ##)
;; - #### Metadata → field :interaction (structured field metadata)

(defn- parse-frontmatter
  "Parse YAML frontmatter from markdown file.
   Returns [frontmatter-map remaining-lines]."
  [lines]
  (if (= "---" (first lines))
    (let [end-idx (loop [idx 1]
                    (cond
                      (>= idx (count lines)) idx
                      (= "---" (nth lines idx)) idx
                      :else (recur (inc idx))))
          fm-lines (subvec (vec lines) 1 end-idx)
          remaining (if (< (inc end-idx) (count lines))
                      (subvec (vec lines) (inc end-idx))
                      [])]
      [(reduce (fn [m line]
                 (let [[k v] (str/split line #":\s*" 2)]
                   (assoc m (keyword k) (str/trim (or v "")))))
               {}
               fm-lines)
       remaining])
    [{} lines]))

(defn- section-type
  "Determine section type from line."
  [line]
  (cond
    (str/starts-with? line "## ") :h2
    (str/starts-with? line "### ") :h3
    (str/starts-with? line "#### ") :h4
    :else nil))

;; ============================================================================
;; Interaction Metadata Parsing
;; ============================================================================

(defn- parse-metadata-bullet
  "Parse '- **Key:** value' format.
   Returns [key value] or nil."
  [line]
  (when-let [[_ k value] (re-matches #"- \*\*([^:]+):\*\*\s*(.*)" line)]
    [(-> k str/lower-case (str/replace " " "-") keyword)
     (str/trim value)]))

(defn- extract-wikilinks
  "Extract wikilinks from text, stripping proto/ prefix and handling display links.
   [[proto/ser.State]] → 'ser.State'
   [[proto/cmd.Test|Test]] → 'cmd.Test'"
  [text]
  (->> (re-seq #"\[\[([^\]]+)\]\]" text)
       (mapv second)
       (mapv (fn [link]
               ;; Handle display links (take part before |)
               (let [target (if (str/includes? link "|")
                              (first (str/split link #"\|"))
                              link)]
                 ;; Strip all proto/ prefixes (may compound across regeneration cycles)
                 (loop [s target]
                   (if (str/starts-with? s "proto/")
                     (recur (subs s 6))
                     s)))))))

(defn- parse-keyword-value
  "Parse keyword from string like ':foo' or 'foo'."
  [s]
  (let [s (str/trim s)]
    (if (str/starts-with? s ":")
      (keyword (subs s 1))
      (keyword s))))

(defn- parse-interaction-meta
  "Parse interaction metadata from bullet list.
   Returns structured map."
  [lines]
  (let [result (atom {})
        current-subsection (atom nil)
        buffer (atom [])]

    (doseq [line lines]
      (cond
        ;; Subsection header (### within ## Interaction)
        (str/starts-with? line "### ")
        (do
          ;; Flush previous subsection
          (when (and @current-subsection (seq @buffer))
            (let [content (str/trim (str/join "\n" @buffer))]
              (case @current-subsection
                :purpose (swap! result assoc :purpose content)
                :related-state (swap! result assoc :related-state (extract-wikilinks content))
                :related-commands (swap! result assoc :related-commands (extract-wikilinks content))
                :preconditions (swap! result assoc :preconditions
                                      (->> (str/split-lines content)
                                           (filter #(str/starts-with? % "- "))
                                           (mapv #(subs % 2))))
                :implementation-notes (swap! result assoc :notes content)
                nil)))
          ;; Start new subsection
          (reset! buffer [])
          (let [header (str/lower-case (subs line 4))]
            (reset! current-subsection
                    (cond
                      (str/starts-with? header "purpose") :purpose
                      (str/starts-with? header "related state") :related-state
                      (str/starts-with? header "related commands") :related-commands
                      (str/starts-with? header "preconditions") :preconditions
                      (str/starts-with? header "implementation") :implementation-notes
                      :else nil))))

        ;; Top-level bullet metadata
        (and (nil? @current-subsection)
             (str/starts-with? line "- **"))
        (when-let [[k v] (parse-metadata-bullet line)]
          (case k
            :category (swap! result assoc :category (parse-keyword-value v))
            :ui-pattern (swap! result assoc :ui-pattern (parse-keyword-value v))
            :feedback (swap! result assoc :feedback (parse-keyword-value v))
            :timeout-ms (swap! result assoc :timeout-ms (parse-long v))
            nil))

        ;; Regular line - add to buffer if in subsection
        @current-subsection
        (swap! buffer conj line)))

    ;; Flush remaining buffer
    (when (and @current-subsection (seq @buffer))
      (let [content (str/trim (str/join "\n" @buffer))]
        (case @current-subsection
          :purpose (swap! result assoc :purpose content)
          :related-state (swap! result assoc :related-state (extract-wikilinks content))
          :related-commands (swap! result assoc :related-commands (extract-wikilinks content))
          :preconditions (swap! result assoc :preconditions
                                (->> (str/split-lines content)
                                     (filter #(str/starts-with? % "- "))
                                     (mapv #(subs % 2))))
          :implementation-notes (swap! result assoc :notes content)
          nil)))

    @result))

(defn- parse-field-interaction-meta
  "Parse field-level interaction metadata from #### Metadata section.
   Returns structured map."
  [lines]
  (let [result (atom {})]
    (doseq [line lines]
      (when-let [[k v] (parse-metadata-bullet line)]
        (case k
          :semantic-type (swap! result assoc :semantic-type (parse-keyword-value v))
          :unit (swap! result assoc :unit v)
          :precision (swap! result assoc :precision (parse-long v))
          :display-format (let [v (str/replace v #"`" "")]
                            (swap! result assoc :display-format v))
          :presets (let [items (->> (str/split v #",\s*")
                                    (mapv (fn [s]
                                            (let [s (str/trim s)]
                                              (if-let [n (parse-double s)]
                                                n
                                                s)))))]
                     (swap! result assoc :presets items))
          nil)))
    @result))

(defn- parse-field-header
  "Parse '### field_name (#number)' format.
   Returns [field-name field-number] or nil."
  [line]
  (when-let [[_ fname num-str] (re-matches #"### (\S+) \(#(\d+)\)" line)]
    [fname (parse-long num-str)]))

(defn- extract-sections
  "Extract sections from markdown lines.
   Returns {:description \"...\"
            :interaction {...}
            :field-notes {1 {:description \"...\" :interaction {...}}}}"
  [lines]
  (let [result (atom {:description nil :interaction nil :field-notes {}})
        current-section (atom nil)
        current-field (atom nil)
        in-field-metadata (atom false)
        buffer (atom [])
        field-desc-buffer (atom [])
        field-meta-buffer (atom [])]

    ;; Helper to flush field content
    (letfn [(flush-field! []
              (when @current-field
                (let [desc (str/trim (str/join "\n" @field-desc-buffer))
                      meta-lines @field-meta-buffer
                      field-interaction (when (seq meta-lines)
                                          (parse-field-interaction-meta meta-lines))]
                  (when (or (seq desc) (seq field-interaction))
                    (swap! result assoc-in [:field-notes @current-field]
                           (cond-> {}
                             (seq desc) (assoc :description desc)
                             (seq field-interaction) (assoc :interaction field-interaction))))))
              (reset! field-desc-buffer [])
              (reset! field-meta-buffer [])
              (reset! in-field-metadata false))]

      ;; Process each line
      (doseq [line lines]
        (let [stype (section-type line)]
          (cond
            ;; New h2 section
            (= stype :h2)
            (do
              ;; Flush previous section
              (when @current-section
                (let [content (str/trim (str/join "\n" @buffer))]
                  (case @current-section
                    :description (swap! result assoc :description content)
                    :interaction (swap! result assoc :interaction
                                        (parse-interaction-meta @buffer))
                    :field-notes (flush-field!)
                    nil)))
              ;; Start new section
              (reset! buffer [])
              (reset! current-field nil)
              (reset! in-field-metadata false)
              (reset! current-section
                      (cond
                        (str/starts-with? line "## Description") :description
                        (str/starts-with? line "## Interaction") :interaction
                        (str/starts-with? line "## Field Notes") :field-notes
                        :else nil)))

            ;; Field note header (### field_name (#number))
            (and (= stype :h3) (= @current-section :field-notes))
            (do
              ;; Flush previous field note if any
              (flush-field!)
              ;; Start new field note
              (if-let [[_ fnum] (parse-field-header line)]
                (reset! current-field fnum)
                (reset! current-field nil)))

            ;; Field metadata header (#### Metadata)
            (and (= stype :h4) @current-field
                 (str/starts-with? (str/lower-case line) "#### metadata"))
            (reset! in-field-metadata true)

            ;; Regular line - add to appropriate buffer
            :else
            (cond
              ;; In field metadata section
              (and @current-field @in-field-metadata)
              (swap! field-meta-buffer conj line)

              ;; In field description (before #### Metadata)
              @current-field
              (swap! field-desc-buffer conj line)

              ;; In regular section
              @current-section
              (swap! buffer conj line)))))

      ;; Flush remaining buffer
      (when @current-section
        (let [content (str/trim (str/join "\n" @buffer))]
          (case @current-section
            :description (swap! result assoc :description content)
            :interaction (swap! result assoc :interaction
                                (parse-interaction-meta @buffer))
            :field-notes (flush-field!)
            nil))))

    ;; Clean up nil values
    (let [r @result]
      (cond-> r
        (nil? (:description r)) (dissoc :description)
        (nil? (:interaction r)) (dissoc :interaction)
        (empty? (:field-notes r)) (dissoc :field-notes)))))

(defn extract-from-file
  "Extract user content from a single markdown file.
   Returns {:id \"...\" :description \"...\" :field-notes {1 \"...\"}}
   or nil if file doesn't exist."
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (let [lines (str/split-lines (slurp file))
            [frontmatter content-lines] (parse-frontmatter lines)]
        (when-let [id (:id frontmatter)]
          (let [extracted (extract-sections content-lines)]
            (assoc extracted :id id)))))))

(defn extract-all-user-content
  "Extract user content from all markdown files in vault directory.
   Returns map of {message-id {:description \"...\" :field-notes {...}}}."
  [vault-dir]
  (t/log! :info ["Extracting user content from" vault-dir])
  (let [vault (io/file vault-dir)
        result (atom {})]
    (when (.exists vault)
      (doseq [file (file-seq vault)
              :when (and (.isFile file)
                         (str/ends-with? (.getName file) ".md")
                         (not= "index.md" (.getName file)))]
        (when-let [extracted (extract-from-file (.getPath file))]
          (let [id (:id extracted)]
            (swap! result assoc id (dissoc extracted :id))))))
    (t/log! :info ["Extracted content from" (count @result) "files"])
    @result))

(defn merge-user-content
  "Merge extracted user content into IR database.
   User content keys: {:description \"...\"
                       :interaction {...}
                       :field-notes {field-number {:description \"...\" :interaction {...}}}}"
  [db user-content]
  (-> db
      ;; Merge message descriptions, interaction, and field notes
      (update :messages
              (fn [messages]
                (reduce-kv
                 (fn [msgs id content]
                   (if-let [msg (get msgs id)]
                     (assoc msgs id
                            (cond-> msg
                              ;; Add message description
                              (seq (:description content))
                              (assoc :description (:description content))

                              ;; Add message-level interaction metadata
                              (seq (:interaction content))
                              (assoc :interaction (:interaction content))

                              ;; Add field descriptions and interaction by number
                              (seq (:field-notes content))
                              (update :fields
                                      (fn [fields]
                                        (mapv (fn [field]
                                                (if-let [field-content (get-in content [:field-notes (:number field)])]
                                                  (cond-> field
                                                    ;; Field content can be string (old format) or map (new format)
                                                    (string? field-content)
                                                    (assoc :description field-content)

                                                    (and (map? field-content) (seq (:description field-content)))
                                                    (assoc :description (:description field-content))

                                                    (and (map? field-content) (seq (:interaction field-content)))
                                                    (assoc :interaction (:interaction field-content)))
                                                  field))
                                              fields)))))
                     msgs))
                 messages
                 user-content)))

      ;; Merge enum descriptions
      (update :enums
              (fn [enums]
                (reduce-kv
                 (fn [es id content]
                   (if-let [e (get es id)]
                     (assoc es id
                            (cond-> e
                              (seq (:description content))
                              (assoc :description (:description content))))
                     es))
                 enums
                 user-content)))))

(defn preserve-user-content
  "Preserve user content from existing DB when syncing IR.
   Copies descriptions and interaction metadata from old-db to new-db where they exist."
  [new-db old-db]
  (-> new-db
      ;; Preserve message descriptions and interaction
      (update :messages
              (fn [msgs]
                (reduce-kv
                 (fn [m id msg]
                   (if-let [old-msg (get-in old-db [:messages id])]
                     (assoc m id
                            (cond-> msg
                              (seq (:description old-msg))
                              (assoc :description (:description old-msg))

                              ;; Preserve message-level interaction metadata
                              (seq (:interaction old-msg))
                              (assoc :interaction (:interaction old-msg))

                              ;; Preserve field descriptions and interaction by matching field numbers
                              true
                              (update :fields
                                      (fn [fields]
                                        (let [old-fields-by-num (into {}
                                                                      (map (juxt :number identity)
                                                                           (:fields old-msg)))]
                                          (mapv (fn [field]
                                                  (let [old-field (get old-fields-by-num (:number field))]
                                                    (cond-> field
                                                      (seq (:description old-field))
                                                      (assoc :description (:description old-field))

                                                      (seq (:interaction old-field))
                                                      (assoc :interaction (:interaction old-field)))))
                                                fields))))))
                     (assoc m id msg)))
                 {}
                 msgs)))

      ;; Preserve enum descriptions
      (update :enums
              (fn [es]
                (reduce-kv
                 (fn [e id enum]
                   (if-let [old-enum (get-in old-db [:enums id])]
                     (assoc e id
                            (cond-> enum
                              (seq (:description old-enum))
                              (assoc :description (:description old-enum))

                              ;; Preserve enum value descriptions
                              true
                              (update :values
                                      (fn [values]
                                        (let [old-vals-by-num (into {}
                                                                    (map (juxt :number identity)
                                                                         (:values old-enum)))]
                                          (mapv (fn [v]
                                                  (if-let [old-desc (get-in old-vals-by-num [(:number v) :description])]
                                                    (assoc v :description old-desc)
                                                    v))
                                                values))))))
                     (assoc e id enum)))
                 {}
                 es)))))
