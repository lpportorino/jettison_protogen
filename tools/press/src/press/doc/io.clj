(ns press.doc.io
  "Where a document's files are, how they are written, and the build stamp.

   Every path a caller passes is resolved by `press.config/from-root`: an
   absolute path stands, a relative one is taken against the configured
   ROOT (`press.config`, by default the repository root — never the JVM's
   working directory). Host commands and `RUNBOOK.md` spell paths
   root-relative, so a document directory must lie inside the root — `rel`
   refuses one that does not.

   The build stamp is a sha256 over `doc.edn`, every placed figure's
   description, pick record and SVG, every placed raster's bytes, the house constants, the stylesheet
   and the font (each file's sha256, or `absent`; see `stamp-inputs`). `build!` writes it into the page and `doc/pdf`
   recomputes it, so a page built from files that have since changed is
   never printed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [press.config :as config]
            [press.doc.figure :as fig]
            [press.doc.image :as image]
            [press.doc.spec :as spec])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.util HexFormat]))

(set! *warn-on-reflection* true)

(def file?
  "A `java.io.File`."
  [:fn #(instance? File %)])

(defn resolve-path
  "`p` as a canonical File: absolute as given, relative against the
   configured root."
  ^File [p]
  (io/file (config/from-root p)))

(m/=> resolve-path [:=> [:cat spec/ne-string] file?])

(defn dir-of
  "The document directory `doc-dir` (resolved); refuses `:doc/no-dir` when
   it is not a directory."
  ^File [doc-dir]
  (let [d (resolve-path doc-dir)]
    (when-not (.isDirectory d)
      (throw (ex-info (str "no document directory " d) {:doc/refusal :doc/no-dir :path (str d)})))
    d))

(m/=> dir-of [:=> [:cat spec/ne-string] file?])

(defn root
  "The configured root as a canonical File."
  ^File []
  (io/file (config/from-root ".")))

(m/=> root [:=> [:cat] file?])

(defn rel
  "`f` relative to the configured root, as a host command spells it;
   refuses `:doc/outside-repo` for a file outside it."
  [^File f]
  (let [r (str (.relativize (.toPath (root)) (.toPath (.getCanonicalFile f))))]
    (when (or (str/starts-with? r "..") (.isAbsolute (io/file r)))
      (throw (ex-info (str f " is outside the root " (root) " — a document lives inside it")
                      {:doc/refusal :doc/outside-repo :path (str f)})))
    (if (str/blank? r) "." r)))

(m/=> rel [:=> [:cat file?] spec/ne-string])

(defn build-dir
  "The never-committed build directory of the document in `d`:
   `<build-dir>/<doc-name>/`, `<build-dir>` being the configured one
   (`press.config`, by default `<root>/.press/doc-build`)."
  ^File [^File d]
  (io/file (config/from-root (config/build-dir)) (.getName d)))

(m/=> build-dir [:=> [:cat file?] file?])

(defn write-atomically!
  "Write `text` to `f` via a sibling temp file and an atomic move, so a
   half-written file never exists at the destination; the temp file is
   removed whatever happens. Returns the bytes written."
  [^File f ^String text]
  (let [dest (.getAbsoluteFile f)
        parent (doto (.getParentFile dest) (.mkdirs))
        tmp (File/createTempFile ".doc-" ".tmp" parent)]
    (try
      (spit tmp text)
      (Files/move (.toPath tmp) (.toPath dest)
                  (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
      (.length dest)
      (finally (Files/deleteIfExists (.toPath tmp))))))

(m/=> write-atomically! [:=> [:cat file? spec/any-string] :int])

(defn read-doc
  "`doc.edn` of `d` as data, or a violation naming why it cannot be read."
  [^File d]
  (let [f (io/file d "doc.edn")]
    (if-not (.isFile f)
      {:violation {:rule :doc/no-doc :at (str f) :message (str "no doc.edn in " d)}}
      (try {:doc (edn/read-string (slurp f))}
           (catch Exception e
             {:violation {:rule :doc/unreadable :at (str f) :message (str "doc.edn is not readable EDN: " (ex-message e))}})))))

(m/=> read-doc [:=> [:cat file?] [:or [:map [:doc spec/anything]] [:map [:violation spec/violation]]]])

(defn- file-hash
  "The sha256 of `f` with bounded streaming memory, or `absent`."
  [^File f]
  (if (.isFile f)
    (with-open [stream (io/input-stream f)]
      (let [digest (MessageDigest/getInstance "SHA-256") buffer (byte-array 8192)]
        (loop [n (.read stream buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur (.read stream buffer))))
        (.formatHex (HexFormat/of) (.digest digest))))
    "absent"))

(m/=> file-hash [:=> [:cat file?] spec/ne-string])

(defn- resource-hash
  "The sha256 of classpath resource `nm`, or `absent`."
  [nm]
  (if-let [r (io/resource nm)]
    (fig/sha256 (with-open [in (io/input-stream r)] (.readAllBytes in)))
    "absent"))

(m/=> resource-hash [:=> [:cat spec/ne-string] spec/ne-string])

(defn stamp-inputs
  "`[name sha256-or-absent]` for every file the build stamp covers, in
   order: `doc.edn`, each placed figure's description, pick record and SVG,
   every placed raster's bytes (named `image:<document-relative src>`),
   the house constants (named as configured — by default
   `tools/press/press/house.json` — and read under the configured root),
   the house stylesheet and the inlined Inter font. The library CODE is not
   covered — a page built by an older press.doc with the same inputs reads
   as current; rebuild after upgrading the library."
  [^File d fig-ids]
  (vec (concat [["doc.edn" (file-hash (io/file d "doc.edn"))]]
               (for [id (sort (distinct fig-ids)) sfx [".edn" ".pick.json" ".svg"]]
                 [(str "figs/" id sfx) (file-hash (io/file d "figs" (str id sfx)))])
               (let [{:keys [doc]} (read-doc d)]
                 (for [src (sort (distinct (if (m/validate spec/doc-spec doc) (image/sources doc) [])))]
                   [(str "image:" src)
                    (try (let [f (image/local-file d src)]
                           (if (> (.length f) image/max-bytes) "oversized" (file-hash f)))
                         (catch Exception _ "unreadable"))]))
               [[(config/house) (file-hash (io/file (config/from-root (config/house))))]
                ["press/doc/house-print.css" (resource-hash "press/doc/house-print.css")]
                ["press/deck/Inter.var.woff2" (resource-hash "press/deck/Inter.var.woff2")]])))

(m/=> stamp-inputs [:=> [:cat file? [:sequential spec/ne-string]] [:sequential [:tuple spec/ne-string spec/ne-string]]])

(defn stamp
  "The build stamp of the document in `d` placing figures `fig-ids`: a
   sha256 over `stamp-inputs`."
  [^File d fig-ids]
  (fig/sha256 (str/join "\n" (map (fn [[n h]] (str n " " h)) (stamp-inputs d fig-ids)))))

(m/=> stamp [:=> [:cat file? [:sequential spec/ne-string]] fig/sha256-hex])

(defn page-stamp
  "The stamp a built page carries, or nil."
  [html]
  (some->> (re-find #"<meta\b[^>]*\bname=\"press-doc-stamp\"[^>]*>" html)
           (re-find #"\bcontent=\"([0-9a-f]{64})\"")
           second))

(m/=> page-stamp [:=> [:cat spec/any-string] [:maybe fig/sha256-hex]])

(defn- shell-word
  "`a` as one shell word, single-quoted when it needs it."
  [a]
  (if (re-matches #"[A-Za-z0-9_./:=@%+-]+" a) a (str "'" (str/replace a "'" "'\\''") "'")))

(m/=> shell-word [:=> [:cat spec/ne-string] spec/ne-string])

(defn shell-line
  "`argv` as one shell line, single-quoting any argument that needs it, behind
   the launcher's environment (`press.config/launcher-env`) as `NAME=value`
   words when there is any."
  [argv]
  (str/join " " (concat (map (fn [[k v]] (str k "=" (shell-word v))) (sort (config/launcher-env)))
                        (map shell-word argv))))

(m/=> shell-line [:=> [:cat [:vector spec/ne-string]] spec/ne-string])
