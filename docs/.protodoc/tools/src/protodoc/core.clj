(ns protodoc.core
  "CLI entry point for protodoc tool."
  (:require [protodoc.schema :as schema]
            [protodoc.parse :as parse]
            [protodoc.extract :as extract]
            [protodoc.render :as render]
            [protodoc.lint :as lint]
            [protodoc.manifest :as manifest]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.telemere :as t])
  (:gen-class))

(defn- load-db
  "Load proto-db.edn from path, returns nil if not found."
  [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn- save-db
  "Save database to EDN file."
  [db path]
  (spit path (pr-str db)))

(defn generate
  "Full roundtrip: parse descriptors + extract user content + render.

   Options:
   - :descriptor - path to descriptor-set.json
   - :output-dir - path to vault output directory
   - :db-path    - path to proto-db.edn"
  [{:keys [descriptor output-dir db-path]
    :or {output-dir "docs"
         db-path "docs/.protodoc/proto-db.edn"}}]
  (t/log! :info "Starting generate...")

  ;; 1. Parse JSON descriptors
  (t/log! :info ["Parsing descriptor:" descriptor])
  (let [ir (parse/parse-descriptor-file descriptor)]
    (t/log! :info ["Parsed" (count (:messages ir)) "messages,"
                   (count (:enums ir)) "enums"])

    ;; 2. Extract user content from existing markdown (if any)
    (let [existing-db (load-db db-path)
          user-content (when existing-db
                         (t/log! :info ["Extracting user content from existing docs"])
                         (extract/extract-all-user-content output-dir))

          ;; 3. Merge user content into IR
          db (-> ir
                 (extract/merge-user-content user-content)
                 (parse/build-search-index))]

      ;; 4. Validate
      (schema/validate! schema/ProtoDb db)
      (t/log! :info "Database validated")

      ;; 5. Save database
      (save-db db db-path)
      (t/log! :info ["Saved database to" db-path])

      ;; 6. Render markdown
      (render/render-all db output-dir)
      (t/log! :info ["Rendered markdown to" output-dir])

      db)))

(defn sync-ir
  "Update DB from descriptors without markdown extraction/render.

   Options:
   - :descriptor - path to descriptor-set.json
   - :db-path    - path to proto-db.edn"
  [{:keys [descriptor db-path]
    :or {db-path "docs/.protodoc/proto-db.edn"}}]
  (t/log! :info "Syncing IR from descriptors...")

  (let [ir (parse/parse-descriptor-file descriptor)
        existing-db (load-db db-path)
        ;; Preserve existing user content
        db (if existing-db
             (-> ir
                 (extract/preserve-user-content existing-db)
                 (parse/build-search-index))
             (parse/build-search-index ir))]

    (schema/validate! schema/ProtoDb db)
    (save-db db db-path)
    (t/log! :info ["Saved database to" db-path])
    db))

(defn render-only
  "Render markdown from existing proto-db.edn without parsing/extraction.

   Options:
   - :db-path    - path to proto-db.edn
   - :output-dir - path to vault output directory"
  [{:keys [db-path output-dir]
    :or {db-path "docs/.protodoc/proto-db.edn"
         output-dir "docs"}}]
  (let [db (load-db db-path)]
    (when-not db
      (throw (ex-info "Database not found" {:path db-path})))
    (t/log! :info ["Rendering from" db-path "to" output-dir])
    (render/render-all db output-dir)
    (t/log! :info "Render complete.")
    db))

(defn coverage
  "Show documentation coverage report.

   Options:
   - :db-path - path to proto-db.edn
   - :strict  - if true, exit with error if coverage < 100%"
  [{:keys [db-path strict]
    :or {db-path "docs/.protodoc/proto-db.edn"
         strict false}}]
  (let [db (load-db db-path)
        _ (when-not db
            (throw (ex-info "Database not found" {:path db-path})))

        messages (vals (:messages db))
        enums (vals (:enums db))
        fields (->> messages (mapcat :fields))
        constrained-fields (filter :constraints fields)

        ;; Calculate coverage
        msg-total (count messages)
        msg-documented (count (filter #(seq (:description %)) messages))

        enum-total (count enums)
        enum-documented (count (filter #(seq (:description %)) enums))

        field-total (count fields)
        field-documented (count (filter #(seq (:description %)) fields))

        constrained-total (count constrained-fields)
        constrained-documented (count (filter #(seq (:description %)) constrained-fields))]

    (println "Documentation Coverage Report")
    (println "=============================")
    (println)
    (println (format "Messages:           %3d / %3d (%5.1f%%)"
                     msg-documented msg-total
                     (* 100.0 (/ msg-documented (max 1 msg-total)))))
    (println (format "Enums:              %3d / %3d (%5.1f%%)"
                     enum-documented enum-total
                     (* 100.0 (/ enum-documented (max 1 enum-total)))))
    (println (format "Fields:             %3d / %3d (%5.1f%%)"
                     field-documented field-total
                     (* 100.0 (/ field-documented (max 1 field-total)))))
    (println (format "Constrained Fields: %3d / %3d (%5.1f%%)"
                     constrained-documented constrained-total
                     (* 100.0 (/ constrained-documented (max 1 constrained-total)))))

    (when (and strict
               (or (< msg-documented msg-total)
                   (< enum-documented enum-total)))
      (System/exit 1))))

(defn lint-cli
  "Lint documentation quality.

   Options:
   - :db-path  - path to proto-db.edn
   - :rules    - comma-separated rule names to include
   - :exclude  - comma-separated rule names to exclude
   - :severity - comma-separated severity levels to include"
  [{:keys [db-path rules exclude severity]
    :or {db-path "docs/.protodoc/proto-db.edn"}}]
  (let [db (load-db db-path)
        _ (when-not db
            (throw (ex-info "Database not found" {:path db-path})))
        parse-kws (fn [s] (when s (set (map keyword (str/split s #",")))))
        result (lint/lint db
                          :rules (parse-kws rules)
                          :exclude (parse-kws exclude)
                          :severity (parse-kws severity))]
    (print (lint/format-findings result))
    (flush)
    (when (pos? (:error (:summary result)))
      (System/exit 1))))

(defn validate
  "Validate database integrity.

   Options:
   - :db-path - path to proto-db.edn

   Returns:
   - {:valid true :db db} if valid
   - {:valid false :error :not-found} if DB not found
   - {:valid false :error :invalid :errors errors} if invalid"
  [{:keys [db-path]
    :or {db-path "docs/.protodoc/proto-db.edn"}}]
  (let [db (load-db db-path)]
    (cond
      (not db)
      {:valid false :error :not-found :path db-path}

      (schema/validate schema/ProtoDb db)
      {:valid false :error :invalid :errors (schema/validate schema/ProtoDb db)}

      :else
      {:valid true :db db})))

(defn validate-cli
  "CLI wrapper for validate that handles output and exit codes."
  [opts]
  (let [result (validate opts)]
    (case (:error result)
      :not-found (do (println "Database not found:" (:path result))
                     (System/exit 1))
      :invalid   (do (println "Validation failed:")
                     (println (:errors result))
                     (System/exit 1))
      nil        (do (println "Database is valid")
                     (:db result)))))

(defn- parse-args
  "Parse CLI arguments into command and options map."
  [args]
  (let [[cmd & rest-args] args]
    {:command cmd
     :options (loop [opts {}
                     [k v & more] rest-args]
                (if k
                  (recur (assoc opts (keyword (subs k 2)) v) more)
                  opts))}))

(defn -main
  "CLI entry point.

   Commands:
     generate   - Full roundtrip (parse + extract + render)
     render     - Render markdown from existing proto-db.edn
     sync-ir    - Update DB from descriptors only
     coverage   - Show documentation coverage
     lint       - Lint documentation quality
     validate   - Validate database

   Options:
     --descriptor PATH  - Path to descriptor-set.json
     --output-dir PATH  - Path to output vault directory
     --db-path PATH     - Path to proto-db.edn
     --strict           - Exit with error if coverage < 100%
     --rules RULES      - Comma-separated lint rules to include
     --exclude RULES    - Comma-separated lint rules to exclude
     --severity LEVELS  - Comma-separated severity levels to include"
  [& args]
  (let [{:keys [command options]} (parse-args args)]
    (case command
      "generate" (generate options)
      "render"   (render-only options)
      "sync-ir"  (sync-ir options)
      "coverage" (coverage (update options :strict #(= % "true")))
      "lint"     (lint-cli options)
      "validate" (validate-cli options)
      "manifest" (manifest/generate-manifests options)
      (do
        (println "Usage: protodoc <command> [options]")
        (println)
        (println "Commands:")
        (println "  generate   Full roundtrip (parse + extract + render)")
        (println "  render     Render markdown from existing proto-db.edn")
        (println "  sync-ir    Update DB from descriptors only")
        (println "  coverage   Show documentation coverage")
        (println "  lint       Lint documentation quality")
        (println "  validate   Validate database")
        (println "  manifest   Generate machine-readable JSON manifests")
        (println)
        (println "Options:")
        (println "  --descriptor PATH    Path to descriptor-set.json")
        (println "  --output-dir PATH    Path to output vault directory (or manifests dir)")
        (println "  --db-path PATH       Path to proto-db.edn")
        (println "  --config-path PATH   Path to manifest-config.edn")
        (println "  --git-sha SHA        Git commit SHA for manifest metadata")
        (println "  --strict             Exit with error if coverage < 100%")
        (println "  --rules RULES        Comma-separated lint rules to include")
        (println "  --exclude RULES      Comma-separated lint rules to exclude")
        (println "  --severity LEVELS    Comma-separated severity levels to include")
        (System/exit 1)))))
