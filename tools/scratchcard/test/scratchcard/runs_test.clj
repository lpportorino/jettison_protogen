(ns scratchcard.runs-test
  "Pins run-directory allocation, the roster, and the scratch-tree invariant.

  The concurrency test SPAWNS THREADS rather than reasoning about the
  allocator. A read-modify-write race is invisible to inspection and to any
  single-threaded test — it only ever shows up as a run that quietly
  overwrote another, which is the one failure this archive cannot tolerate."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scratchcard.runs :as runs]
   [scratchcard.scope :as scope])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.time Instant)))

(defn- temp-root
  ^String []
  (str (Files/createTempDirectory "scratchcard-runs-test"
                                  (into-array FileAttribute []))))

(def ^:private fixed-instant (Instant/parse "2026-08-02T13:22:44Z"))

(deftest utc-stamp-is-compact-and-colon-free
  (is (= "20260802T132244Z" (runs/utc-stamp fixed-instant)))
  (testing "sub-second precision is truncated, so a stamp is stable within a second"
    (is (= (runs/utc-stamp fixed-instant)
           (runs/utc-stamp (.plusMillis fixed-instant 700)))))
  (testing "no colons — hostile in a pasted shell path, illegal on some mounts"
    (is (not (str/includes? (runs/utc-stamp fixed-instant) ":")))))

(deftest allocate-numbers-monotonically-and-pads
  (let [root (temp-root)
        a (runs/allocate! root "demo" fixed-instant)
        b (runs/allocate! root "demo" fixed-instant)
        c (runs/allocate! root "demo" fixed-instant)]
    (is (str/starts-with? (.getName ^File a) "0001-"))
    (is (str/starts-with? (.getName ^File b) "0002-"))
    (is (str/starts-with? (.getName ^File c) "0003-"))
    (testing "zero-padding keeps lexical order equal to numeric order"
      (is (= ["0001" "0002" "0003"]
             (->> (.listFiles (runs/runs-dir root "demo"))
                  (map #(subs (.getName ^File %) 0 4))
                  sort
                  vec))))
    (testing "each is a real directory"
      (is (every? #(.isDirectory ^File %) [a b c])))))

(deftest allocate-is-collision-free-under-concurrency
  ;; THE POINT OF THIS TEST: `(inc (count existing))` passes every
  ;; single-threaded assertion above and loses runs here.
  (let [root (temp-root)
        n 32
        results (->> (range n)
                     (map (fn [_] (future (runs/allocate! root "race" fixed-instant))))
                     doall
                     (mapv deref))
        paths (map #(.getCanonicalPath ^File %) results)]
    (is (= n (count results)))
    (testing "every allocation got its OWN directory"
      (is (= n (count (set paths)))))
    (testing "and the ids are exactly 1..n with no gaps and no repeats"
      (is (= (vec (range 1 (inc n)))
             (runs/existing-ids (runs/runs-dir root "race")))))))

(deftest stray-files-do-not-break-allocation
  (let [root (temp-root)
        _ (runs/allocate! root "demo" fixed-instant)
        dir (runs/runs-dir root "demo")]
    (spit (io/file dir "NOTES.txt") "a human dropped this here")
    (.mkdirs (io/file dir "not-a-run"))
    (testing "unrecognised names are ignored rather than fatal"
      (is (= [1] (runs/existing-ids dir))))
    (testing "allocation still proceeds"
      (is (str/starts-with? (.getName ^File (runs/allocate! root "demo" fixed-instant))
                            "0002-")))))

(deftest roster-is-append-only-and-ordered
  (let [root (temp-root)]
    (runs/append-roster! root "demo" {:run "0001" :status :ok})
    (runs/append-roster! root "demo" {:run "0002" :status :error})
    (runs/append-roster! root "demo" {:run "0003" :status :ok})
    (let [entries (runs/read-roster root "demo")]
      (is (= 3 (count entries)))
      (is (= ["0001" "0002" "0003"] (mapv :run entries)))
      (testing "a failed run is rostered like any other — it is diffable output"
        (is (= :error (:status (second entries))))))
    (testing "an absent roster reads as empty, not as an error"
      (is (= [] (runs/read-roster root "never-run"))))))

(deftest roster-survives-a-torn-final-line
  ;; A killed process can leave a partial line. Losing the whole history to it
  ;; would be a far worse outcome than losing the torn entry.
  (let [root (temp-root)]
    (runs/append-roster! root "demo" {:run "0001" :status :ok})
    (spit (runs/roster-file root "demo") "{:run \"0002\" :stat" :append true)
    (let [entries (runs/read-roster root "demo")]
      (is (= 1 (count entries)))
      (testing "and the loss is REPORTED, never silent"
        (is (= 1 (::runs/unreadable (meta entries))))))))

(deftest latest-link-and-run-resolution
  (let [root (temp-root)
        first-run (runs/allocate! root "demo" fixed-instant)
        second-run (runs/allocate! root "demo" fixed-instant)]
    (runs/latest-link! root "demo" second-run)
    (testing "resolve by :latest"
      (is (= (.getCanonicalPath ^File second-run)
             (.getCanonicalPath ^File (runs/resolve-run root "demo" :latest)))))
    (testing "resolve by integer id"
      (is (= (.getCanonicalPath ^File first-run)
             (.getCanonicalPath ^File (runs/resolve-run root "demo" 1)))))
    (testing "resolve by directory name"
      (is (= (.getCanonicalPath ^File first-run)
             (.getCanonicalPath ^File (runs/resolve-run root "demo" (.getName ^File first-run))))))
    (testing "an unknown ref resolves to nil rather than throwing"
      (is (nil? (runs/resolve-run root "demo" 99))))
    (testing "relinking moves the pointer"
      (runs/latest-link! root "demo" first-run)
      (is (= (.getCanonicalPath ^File first-run)
             (.getCanonicalPath ^File (runs/resolve-run root "demo" :latest)))))))

(deftest a-card-slug-cannot-escape-the-scratch-root
  ;; A slug becomes a PATH SEGMENT and `io/file` does not normalise, so an
  ;; unvalidated one is a directory-traversal primitive for anything that can
  ;; reach the daemon socket: arbitrary directory creation, deletion of any
  ;; path named `latest`, and a recursive delete of any directory matching the
  ;; run-name shape. Same-uid behind a 0700 socket dir is a blast RADIUS, not
  ;; confinement.
  (let [root (temp-root)]
    (testing "traversal spellings are refused"
      (doseq [bad ["../../.." ".." "a/../../b" "/etc" "./x" "a/b"]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (runs/card-dir root bad))
                    (str "slug " (pr-str bad) " must be refused"))]
          (is (= "INVALID_CARD" (:error (ex-data e)))))))
    (testing "nil, empty and over-long are refused"
      (doseq [bad [nil "" (str/join (repeat 200 "x"))]]
        (is (thrown? clojure.lang.ExceptionInfo (runs/card-dir root bad)))))
    (testing "a leading dot is refused — it would hide the directory"
      (is (thrown? clojure.lang.ExceptionInfo (runs/card-dir root ".hidden"))))
    (testing "ordinary slugs still work, so the guard is not simply a wall"
      (doseq [good ["demo" "hello" "lego_scrubber" "a.b-c" "X9"]]
        (is (.isAbsolute ^File (runs/card-dir root good)))))
    (testing "and every path helper goes through the same seam"
      (is (thrown? clojure.lang.ExceptionInfo (runs/runs-dir root "../x")))
      (is (thrown? clojure.lang.ExceptionInfo (runs/roster-file root "../x")))
      (is (thrown? clojure.lang.ExceptionInfo (runs/allocate! root "../x" fixed-instant))))))

(deftest scratch-tree-holds-no-tracked-files
  ;; TRAP T4: a directory NAMED for scratch is not scratch if the base tree
  ;; commits files into it. Three independent workers hit that class in one
  ;; wave here. If this ever fails, a `git add -A` would sweep repository
  ;; content into a run directory and the retention pruner would delete
  ;; tracked files.
  (let [repo-root (scope/discover-repo-root (System/getProperty "user.dir"))
        {:keys [exit out]} (shell/sh "git" "ls-files" ".protogen" :dir repo-root)]
    (is (zero? exit) "git ls-files must succeed for this invariant to mean anything")
    (is (str/blank? (str/trim out))
        (str "tracked files under .protogen/ — scratch is not scratch: " out))))
