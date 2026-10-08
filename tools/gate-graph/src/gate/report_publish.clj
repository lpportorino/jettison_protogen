(ns gate.report-publish
  "Create-only publication of a complete standalone HTML report next to canonical EDN."
  (:require [gate.archive-contract :as ac]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.graph :as graph]
            [gate.report :as report]
            [gate.viewer-asset :as asset]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path LinkOption OpenOption StandardOpenOption FileAlreadyExistsException]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(def Directory [:string {:min 1 :max 4096}])
(def Publication
  [:map {:closed true} [:file [:= "index.html"]] [:artifact c/Digest]
   [:viewer c/Digest] [:bytes [:int {:min 1 :max 167772160}]]])
(def Failure
  [:map {:closed true} [:code [:enum :html-policy :html-directory :html-exists :html-io
                               :html-byte-limit :html-viewer-mismatch]]])
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- refuse!
  "Keep filesystem failures bounded and independent of private paths or HTML."
  [code]
  (throw (ex-info "HTML publication refused" {:code code})))
(m/=> refuse! [:=> [:cat (second (nth Failure 2))] :nil])

(defn publish!
  "Publish index.html once in an existing trusted report directory.
   A completed temporary file is hard-linked into place, so readers never see a
   partial index and concurrent publishers cannot replace earlier evidence. Leaf
   symlinks and existing files refuse. Parent directories must remain trusted.
   Filesystems without hard links fail closed. This is neither a durable fsync
   protocol nor a transaction with the caller's EDN files. Returned digests bind
   the normalized graph and viewer bytes, including failed/incomplete graphs."
  ([directory value viewer] (publish! directory value viewer nil))
  ([directory value viewer metadata]
   (when-not (and (m/validate Directory directory) (m/validate asset/Asset viewer))
     (refuse! :html-policy))
   (graph/require-valid! value)
   (when-not (= (:digest viewer) (canonical/sha256 (:javascript viewer)))
     (refuse! :html-viewer-mismatch))
   (let [encoded (canonical/encode (canonical/normalize-graph value) 134217728)
         html (report/render value (:javascript viewer) metadata)
         content (.getBytes ^String html StandardCharsets/UTF_8)]
     (when (> (alength content) 167772160) (refuse! :html-byte-limit))
     (try
       (let [base (Path/of directory (make-array String 0))]
         (when-not (Files/isDirectory base no-follow) (refuse! :html-directory))
         (let [temporary (Files/createTempFile base ".html-" ".tmp" (make-array FileAttribute 0))]
           (try
             (Files/write temporary content ^"[Ljava.nio.file.OpenOption;" (into-array OpenOption [StandardOpenOption/WRITE StandardOpenOption/TRUNCATE_EXISTING]))
             (Files/createLink (.resolve base "index.html") temporary)
             {:file "index.html" :artifact (canonical/sha256 encoded)
              :viewer (:digest viewer) :bytes (alength content)}
             (finally (Files/deleteIfExists temporary)))))
       (catch FileAlreadyExistsException _ (refuse! :html-exists))
       (catch IllegalArgumentException _ (refuse! :html-policy))
       (catch java.io.IOException _ (refuse! :html-io))
       (catch UnsupportedOperationException _ (refuse! :html-io))
       (catch SecurityException _ (refuse! :html-io))))))
(m/=> publish! [:function [:=> [:cat Directory c/Graph asset/Asset] Publication]
                [:=> [:cat Directory c/Graph asset/Asset [:maybe ac/Metadata]] Publication]])
