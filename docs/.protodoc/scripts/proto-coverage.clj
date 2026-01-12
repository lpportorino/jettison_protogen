#!/usr/bin/env bb
;; Show documentation coverage report
;; Usage: bb proto-coverage.clj [db-path]

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn pct
  "Calculate percentage safely."
  [documented total]
  (if (zero? total)
    100.0
    (* 100.0 (/ documented total))))

(defn bar
  "Create a progress bar string."
  [percent width]
  (let [filled (int (* width (/ percent 100)))
        empty (- width filled)]
    (str "[" (apply str (repeat filled "#")) (apply str (repeat empty "-")) "]")))

(defn has-real-description?
  "Check if item has a real description (not just placeholder text)."
  [item]
  (let [desc (:description item)]
    (and (seq desc)
         (not (str/includes? desc "*No description yet.*"))
         (not (str/includes? desc "No description")))))

(defn has-interaction?
  "Check if item has interaction metadata."
  [item]
  (seq (:interaction item)))

(let [[db-path] *command-line-args*
      db-path (or db-path "docs/.protodoc/proto-db.edn")]
  (let [db-file (clojure.java.io/file db-path)]
    (if-not (.exists db-file)
      (do
        (println "Database not found:" db-path)
        (System/exit 1))
      (let [db (edn/read-string (slurp db-file))
            messages (vals (:messages db))
            enums (vals (:enums db))
            fields (->> messages (mapcat :fields))
            constrained-fields (filter :constraints fields)

            ;; Calculate coverage (excludes placeholder text)
            msg-total (count messages)
            msg-doc (count (filter has-real-description? messages))
            msg-interaction (count (filter has-interaction? messages))
            msg-pct (pct msg-doc msg-total)

            enum-total (count enums)
            enum-doc (count (filter has-real-description? enums))
            enum-pct (pct enum-doc enum-total)

            field-total (count fields)
            field-doc (count (filter has-real-description? fields))
            field-interaction (count (filter has-interaction? fields))
            field-pct (pct field-doc field-total)

            constrained-total (count constrained-fields)
            constrained-doc (count (filter has-real-description? constrained-fields))
            constrained-pct (pct constrained-doc constrained-total)]

        (println "Proto Documentation Coverage")
        (println "============================")
        (println)
        (println (format "Messages:           %3d / %3d  %s %5.1f%%"
                         msg-doc msg-total (bar msg-pct 20) msg-pct))
        (when (pos? msg-interaction)
          (println (format "  with interaction: %3d" msg-interaction)))
        (println (format "Enums:              %3d / %3d  %s %5.1f%%"
                         enum-doc enum-total (bar enum-pct 20) enum-pct))
        (println (format "Fields:             %3d / %3d  %s %5.1f%%"
                         field-doc field-total (bar field-pct 20) field-pct))
        (when (pos? field-interaction)
          (println (format "  with interaction: %3d" field-interaction)))
        (println (format "Constrained Fields: %3d / %3d  %s %5.1f%%"
                         constrained-doc constrained-total (bar constrained-pct 20) constrained-pct))

        ;; List undocumented items if any
        (let [undoc-msgs (->> messages (remove has-real-description?) (map :id) sort)]
          (when (and (seq undoc-msgs) (< (count undoc-msgs) msg-total))
            (println)
            (println "Undocumented messages:" (count undoc-msgs))
            (doseq [id (take 10 undoc-msgs)]
              (println "  -" id))
            (when (> (count undoc-msgs) 10)
              (println "  ..." (- (count undoc-msgs) 10) "more"))))))))
