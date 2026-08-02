(ns scratchcard.retention
  "Bounds the run archive.

  UNBOUNDED GROWTH HERE IS STRUCTURAL, NOT ACCIDENTAL. Every invocation
  creates a new run directory by design — that is the feature — and the
  default matrix is six theme states across seven canvases, so a single run
  carries dozens of PNGs, dumps and stat files. Nothing reclaims that unless
  something is written to.

  THE PRUNE NEVER TOUCHES THE RUN A READER IS ABOUT TO COMPARE AGAINST. The
  newest runs are kept, `latest` is kept whatever its age, and callers can
  protect specific runs by name. A garbage collector that silently removed the
  baseline of an in-flight comparison would destroy the exact thing this
  archive exists to provide, and it would look like the tool never wrote it.

  IT REPORTS WHAT IT REMOVED. A silent sweep is indistinguishable from a run
  that never happened. Every call returns the deleted names and the reclaimed
  bytes, for the caller to surface.

  IT CANNOT REACH ANYTHING BUT ITS OWN RUNS. `.protogen/` is shared with other
  machinery — the fork lifecycle keeps state there too — so this only ever
  walks `<scratch-root>/<card>/runs/` and only ever deletes directories whose
  names match the allocator's own `NNNN-stamp` shape. Two tools sharing a
  parent directory with different lifecycles is precisely how one deletes the
  other's data."
  (:require
   [clojure.java.io :as io]
   [scratchcard.runs :as runs])
  (:import
   (java.io File)
   (java.nio.file Files LinkOption)))

(def default-keep
  "How many runs per card survive a prune by default."
  20)

(def ^:private run-dir-pattern #"\d{4}-\d{8}T\d{6}Z")

(defn- run-dir?
  "True only for a directory the allocator itself could have created.

  The whole safety property of this namespace: a name it does not recognise is
  never deleted, whoever put it there."
  [^File f]
  (and (.isDirectory f)
       (boolean (re-matches run-dir-pattern (.getName f)))))

(defn- dir-bytes
  [^File dir]
  (->> (file-seq dir) (filter #(.isFile ^File %)) (map #(.length ^File %)) (reduce + 0)))

(defn- delete-tree!
  "Recursively delete `dir`. Returns bytes reclaimed."
  [^File dir]
  (let [n (dir-bytes dir)]
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f))
    n))

(defn- latest-target
  "The run directory `latest` points at, or nil."
  ^File [^String scratch-root ^String card-slug]
  (try
    (let [link (.toPath (io/file (runs/card-dir scratch-root card-slug) "latest"))]
      (when (Files/exists link (into-array LinkOption []))
        (.toFile (Files/readSymbolicLink link))))
    (catch Exception _ nil)))

(defn plan
  "Which run directories a prune WOULD remove, newest-first ordering applied.

  Separated from the deletion so a caller — or a test — can inspect the
  decision without performing it."
  ;; `keep-n`, not `keep` — the KEYWORD stays `:keep` because it is data, but
  ;; binding `keep` would shadow `clojure.core/keep`, which this namespace
  ;; uses. Rename the local, never the key.
  [^String scratch-root ^String card-slug {:keys [protect] keep-n :keep :or {keep-n default-keep}}]
  (let [dir (runs/runs-dir scratch-root card-slug)
        protected (into #{} (map str) protect)
        latest (some-> (latest-target scratch-root card-slug) .getName)
        all (->> (.listFiles dir)
                 (filter run-dir?)
                 (sort-by #(.getName ^File %))
                 reverse
                 vec)
        survivors (into #{} (map #(.getName ^File %)) (take keep-n all))]
    {:total (count all)
     :keep keep-n
     :doomed (vec (for [^File f all
                        :let [n (.getName f)]
                        :when (and (not (contains? survivors n))
                                   (not (contains? protected n))
                                   (not= n latest))]
                    f))}))

(defn prune!
  "Remove all but the newest `:keep` runs of `card-slug`.

  `:protect` is a collection of run directory names never removed. Returns
  `{:removed [names] :bytes-reclaimed n :kept n}` — always, including when it
  removed nothing, so a caller can say so rather than infer it."
  ([scratch-root card-slug] (prune! scratch-root card-slug {}))
  ([^String scratch-root ^String card-slug opts]
   (let [{:keys [doomed total] keep-n :keep} (plan scratch-root card-slug opts)
         reclaimed (reduce + 0 (map delete-tree! doomed))]
     {:removed (mapv #(.getName ^File %) doomed)
      :bytes-reclaimed reclaimed
      :kept (- total (count doomed))
      :keep keep-n})))

(defn card-usage
  "Total bytes and run count for `card-slug`.

  Surfaced by the daemon's status op so growth is visible BEFORE it becomes a
  disk problem."
  [^String scratch-root ^String card-slug]
  (let [dir (runs/runs-dir scratch-root card-slug)
        rs (filter run-dir? (or (.listFiles dir) []))]
    {:runs (count rs)
     :bytes (reduce + 0 (map dir-bytes rs))}))

(defn total-usage
  "Runs and bytes across EVERY card under `scratch-root`.

  `card-usage` needs a card name, and the status op has none to give: its
  declared arg set is EMPTY, so a `(:card args)` lookup there is unreachable
  and whatever default it fell back to reported zero for every real card.
  Growth has to be visible without naming anything."
  [^String scratch-root]
  (let [cards (filter #(.isDirectory ^File %)
                      (or (.listFiles (io/file scratch-root)) []))
        per (mapv #(card-usage scratch-root (.getName ^File %)) cards)]
    {:cards (count cards)
     :runs (reduce + 0 (map :runs per))
     :bytes (reduce + 0 (map :bytes per))}))
