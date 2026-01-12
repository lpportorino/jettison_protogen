#!/usr/bin/env bb
;; Fuzzy search proto schema (messages, fields, enums)
;; Usage: bb proto-search.clj <query> [db-path]

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn fuzzy-match?
  "Check if text contains query (case-insensitive)."
  [text query]
  (let [q (str/lower-case query)
        t (str/lower-case (or text ""))]
    (str/includes? t q)))

(defn search-index
  "Search using pre-built index."
  [db query]
  (->> (:search-index db)
       (filter (fn [[k _]] (fuzzy-match? k query)))
       (mapcat val)
       distinct))

(defn search-messages
  "Direct search in messages (fallback if index empty)."
  [db query]
  (->> (:messages db)
       vals
       (filter (fn [msg]
                 (or (fuzzy-match? (:name msg) query)
                     (fuzzy-match? (:package msg) query)
                     (fuzzy-match? (:description msg) query)
                     (some #(fuzzy-match? (:name %) query) (:fields msg)))))
       (map :id)))

(defn search-enums
  "Direct search in enums (fallback if index empty)."
  [db query]
  (->> (:enums db)
       vals
       (filter (fn [enum]
                 (or (fuzzy-match? (:name enum) query)
                     (fuzzy-match? (:package enum) query)
                     (fuzzy-match? (:description enum) query)
                     (some #(fuzzy-match? (:name %) query) (:values enum)))))
       (map :id)))

(defn search
  "Search proto database for query."
  [db query]
  (let [index-results (search-index db query)]
    (if (seq index-results)
      index-results
      ;; Fallback to direct search if index empty
      (distinct (concat (search-messages db query)
                        (search-enums db query))))))

(defn format-result
  "Format a result ID with type indicator."
  [db id]
  (let [type (cond
               (get-in db [:messages id]) "msg"
               (get-in db [:enums id]) "enum"
               :else "?")]
    (format "[%s] %s" type id)))

(let [[query db-path] *command-line-args*
      db-path (or db-path "docs/.protodoc/proto-db.edn")]
  (if-not query
    (do
      (println "Usage: proto-search.clj <query> [db-path]")
      (System/exit 1))
    (let [db-file (clojure.java.io/file db-path)]
      (if-not (.exists db-file)
        (do
          (println "Database not found:" db-path)
          (System/exit 1))
        (let [db (edn/read-string (slurp db-file))
              results (search db query)]
          (if (seq results)
            (doseq [id results]
              (println (format-result db id)))
            (println "No results found for:" query)))))))
