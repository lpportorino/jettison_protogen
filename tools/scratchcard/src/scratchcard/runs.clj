(ns scratchcard.runs
  "Allocates and rosters the per-invocation run directories.

  EVERY INVOCATION GETS A DIRECTORY, INCLUDING A FAILED ONE. The tool's return
  value is what distinguishes success from failure; the directory always
  exists, because a failure that can be diffed against the previous success is
  the whole reason the archive exists. A run that wrote nothing because it
  errored early is exactly the run a reader most wants to compare.

  ALLOCATION IS COLLISION-FREE WITHOUT A LOCK. Two clients can reach one
  daemon at once, so the id cannot be `(inc (count existing))` computed and
  then used — that is a read-modify-write with a window in it.
  `Files/createDirectory` is atomic and FAILS when the name is taken, so the
  loser of a race simply retries with the next id. The filesystem is the
  arbiter; there is no lock to leak.

  THE ROSTER IS LINE-DELIMITED AND APPEND-ONLY, which is why it is `.ednl` and
  not `.edn`. The obvious shape — one EDN vector, read-conj-write — silently
  DROPS a run when two invocations finish together, and that loss is invisible
  precisely to the reader who is trying to compare runs. One map per line,
  opened for append, has no such window.

  WHERE IT WRITES, AND WHY THAT IS SAFE. Everything lands under the repo's
  gitignored `.protogen/`. That matters beyond tidiness: this repo's proof
  battery READS THE WORKING TREE as it runs, so a tool that wrote into tracked
  space while a battery ran would produce a result describing no commit that
  ever existed. Writing only into ignored space is what makes the daemon safe
  to leave running. DO NOT relocate this output into the tree."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.nio.file FileAlreadyExistsException Files LinkOption Path)
   (java.nio.file.attribute FileAttribute)
   (java.time Instant)
   (java.time.format DateTimeFormatter)
   (java.time.temporal ChronoUnit)))

(def roster-name
  "The per-card run roster: one EDN map per line, append-only.

  `.ednl` rather than `.edn` because the file is a SEQUENCE of forms, not one
  form. A reader that slurps and calls `read-string` gets only the first line,
  so the extension is what stops that mistake being silent."
  "index.ednl")

(def ^:private id-digits 4)

(def ^:private stamp-format
  "Compact UTC, no colons.

  Colons are legal on Linux but hostile in a path a human pastes into a shell,
  and they are illegal on the filesystems a consumer may mount this from."
  (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'"))

(defn utc-stamp
  "`instant` as a compact UTC directory stamp."
  ^String [^Instant instant]
  (.format stamp-format (.atZone (.truncatedTo instant ChronoUnit/SECONDS)
                                 java.time.ZoneOffset/UTC)))

(defn card-dir
  "The directory holding every run of `card-slug`."
  ^File [^String scratch-root ^String card-slug]
  (io/file scratch-root card-slug))

(defn runs-dir
  "The directory holding `card-slug`'s numbered run directories."
  ^File [^String scratch-root ^String card-slug]
  (io/file (card-dir scratch-root card-slug) "runs"))

(defn roster-file
  "`card-slug`'s append-only run roster."
  ^File [^String scratch-root ^String card-slug]
  (io/file (card-dir scratch-root card-slug) roster-name))

(defn- run-id
  "The numeric id encoded in a run directory name, or nil when the name is not
  one of ours.

  Returning nil rather than throwing matters: the runs directory is on disk
  where a human can drop things, and one stray file must not make the whole
  card unallocatable."
  [^String dir-name]
  (when-let [[_ digits] (re-matches #"(\d{4})-.*" dir-name)]
    (parse-long digits)))

(defn existing-ids
  "Every run id already present under `runs-dir`, ascending."
  [^File dir]
  (->> (.listFiles dir)
       (keep #(run-id (.getName ^File %)))
       sort
       vec))

(defn- next-id
  [^File dir]
  (if-let [ids (seq (existing-ids dir))]
    (inc (apply max ids))
    1))

(defn- try-create
  "Create `dir` atomically. Returns it, or nil when the name was taken.

  The nil is the RACE SIGNAL — another invocation won this id — and is
  distinct from any other failure, which propagates."
  ^File [^File dir]
  (try
    (Files/createDirectory (.toPath dir) (into-array FileAttribute []))
    dir
    (catch FileAlreadyExistsException _ nil)))

(defn allocate!
  "Create and return a fresh run directory for `card-slug`.

  Retries on a lost race rather than locking. `attempts` bounds the retry so a
  pathological condition (a read-only directory presenting as perpetually
  taken) fails loudly instead of spinning forever."
  (^File [scratch-root card-slug instant]
   (allocate! scratch-root card-slug instant 64))
  (^File [^String scratch-root ^String card-slug ^Instant instant attempts]
   (let [dir (runs-dir scratch-root card-slug)
         stamp (utc-stamp instant)]
     (Files/createDirectories (.toPath dir) (into-array FileAttribute []))
     (loop [id (next-id dir), left attempts]
       (when (zero? left)
         (throw (ex-info (str "could not allocate a run dir under " dir
                              " after " attempts " attempts")
                         {:error "RUN_ALLOC_FAILED" :runs-dir (str dir)})))
       (if-let [made (try-create (io/file dir (format (str "%0" id-digits "d-%s") id stamp)))]
         made
         (recur (inc id) (dec left)))))))

(defn append-roster!
  "Append `entry` to `card-slug`'s roster as one line.

  Opened for append per call: the write is the commit, and no reader has to
  hold anything while it happens."
  [^String scratch-root ^String card-slug entry]
  (let [f (roster-file scratch-root card-slug)]
    (io/make-parents f)
    (with-open [w (io/writer f :append true)]
      (.write w (pr-str entry))
      (.write w "\n"))
    f))

(defn read-roster
  "Every roster entry for `card-slug`, in write order.

  A malformed line is SKIPPED rather than fatal — a torn final line from a
  killed process must not make the whole history unreadable — but the count of
  skipped lines rides along under `:scratchcard.runs/unreadable` on the
  metadata so a caller can report it rather than discover a short list."
  [^String scratch-root ^String card-slug]
  (let [f (roster-file scratch-root card-slug)]
    (if-not (.exists f)
      []
      (let [lines (remove str/blank? (str/split-lines (slurp f)))
            parsed (map (fn [line]
                          (try (read-string line)
                               (catch Exception _ ::unreadable)))
                        lines)
            good (vec (remove #(= ::unreadable %) parsed))]
        (with-meta good
          {::unreadable (- (count parsed) (count good))})))))

(defn latest-link!
  "Point `<card>/latest` at `run-dir`.

  BEST EFFORT AND CONVENIENCE ONLY — never the source of truth, which is the
  roster. A filesystem that cannot make symlinks, or a race between two
  finishing runs, must not fail the run that produced real output. Returns the
  link path on success, nil otherwise."
  [^String scratch-root ^String card-slug ^File run-dir]
  (try
    (let [link (.toPath (io/file (card-dir scratch-root card-slug) "latest"))]
      (Files/deleteIfExists link)
      (Files/createSymbolicLink link (.toPath run-dir) (into-array FileAttribute []))
      link)
    (catch Exception _ nil)))

(defn resolve-run
  "The directory for `run-ref` under `card-slug`, or nil.

  `run-ref` is `:latest`, an integer id, or a directory name. Accepting all
  three is deliberate: a caller comparing two runs should not have to know
  which spelling the roster used."
  ^File [^String scratch-root ^String card-slug run-ref]
  (let [dir (runs-dir scratch-root card-slug)]
    (cond
      (= :latest run-ref)
      (let [link (.toPath (io/file (card-dir scratch-root card-slug) "latest"))]
        (when (Files/exists link (into-array LinkOption []))
          (.toFile ^Path (Files/readSymbolicLink link))))

      (integer? run-ref)
      (->> (.listFiles dir)
           (filter #(= run-ref (run-id (.getName ^File %))))
           first)

      :else
      (let [f (io/file dir (str run-ref))]
        (when (.isDirectory f) f)))))
