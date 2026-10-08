(ns gate.archive-io
  "Create-only run archives and deliberate export beneath checked generated-report exclusions."
  (:require [clojure.string :as str]
            [gate.admission :as admission]
            [gate.archive :as archive]
            [gate.archive-contract :as ac]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.inputs :as inputs]
            [gate.report-io :as reader]
            [gate.report-publish :as html]
            [gate.repository-contract :as rc]
            [gate.repository-identity :as repository]
            [gate.run-contract :as r]
            [gate.viewer-asset :as viewer]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path LinkOption OpenOption StandardOpenOption FileAlreadyExistsException]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)
(def Publication
  [:map {:closed true} [:artifact c/Digest] [:graph c/Digest] [:viewer c/Digest]
   [:file [:= "run.edn"]] [:html [:= "index.html"]]])
(def Code [:enum :archive-output-policy :archive-output-exists :archive-output-io :archive-graph-binding])
(def Failure [:map {:closed true} [:code Code]])
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- refuse!
  "Report local publication failures without echoing paths or retained private data."
  [code]
  (throw (ex-info "Archive publication refused" {:code code})))
(m/=> refuse! [:=> [:cat Code] :nil])

(defn- install!
  "Install one completed UTF-8 file using an atomic create-only hard link in a trusted directory.
   A later failure leaves earlier files as partial evidence; no fsync or multi-file transaction."
  [directory filename text]
  (let [temporary (Files/createTempFile directory ".archive-" ".tmp" (make-array FileAttribute 0))]
    (try
      (Files/writeString temporary text StandardCharsets/UTF_8
                         (into-array OpenOption [StandardOpenOption/WRITE StandardOpenOption/TRUNCATE_EXISTING]))
      (Files/createLink (.resolve ^Path directory ^String filename) temporary)
      nil
      (finally (Files/deleteIfExists temporary)))))
(m/=> install! [:=> [:cat inputs/NativePath [:enum "graph.edn" "run.edn" "repository-before.edn" "repository-after.edn"] canonical/Text] :nil])

(defn write-observation!
  "Retain one complete private repository inventory alongside a run; never replace earlier evidence."
  [directory phase observation]
  (repository/require-observation! observation)
  (let [text (canonical/encode observation 134217727)]
    (try (install! (Path/of directory (make-array String 0))
                   (if (= :before phase) "repository-before.edn" "repository-after.edn") (str text "\n"))
         (catch FileAlreadyExistsException _ (refuse! :archive-output-exists))
         (catch IllegalArgumentException _ (refuse! :archive-output-policy))
         (catch java.io.IOException _ (refuse! :archive-output-io))
         (catch UnsupportedOperationException _ (refuse! :archive-output-io))
         (catch SecurityException _ (refuse! :archive-output-io)))))
(m/=> write-observation! [:=> [:cat inputs/Root [:enum :before :after] rc/Observation] :nil])

(defn publish!
  "Publish run.edn and matching standalone HTML in an existing trusted run directory.
   Reuse an existing regular graph.edn only when its normalized graph equals the archive graph.
   Never overwrite index.html or run.edn. Write run.edn last as the completed archive record;
   earlier EDN/HTML can remain after failure. Consumers must not report publication success on error.
   Parent directories must remain trusted. No automatic Git staging or committing occurs."
  [directory document asset]
  (archive/require-valid! document)
  (let [encoded (canonical/encode document 134217727)
        graph (:graph document) graph-text (canonical/encode graph 134217727)]
    (when-not (= document (admission/decode encoded :run-archive admission/default-limits))
      (refuse! :archive-output-policy))
    (try
      (let [base (Path/of directory (make-array String 0)) graph-path (.resolve base "graph.edn")]
        (when-not (Files/isDirectory base no-follow) (refuse! :archive-output-policy))
        (doseq [filename ["index.html" "run.edn"]]
          (when (Files/exists (.resolve base ^String filename) no-follow) (refuse! :archive-output-exists)))
        (if (Files/exists graph-path no-follow)
          (when-not (= graph (canonical/normalize-graph (reader/read-graph! (str graph-path) admission/default-limits)))
            (refuse! :archive-graph-binding))
          (install! base "graph.edn" (str graph-text "\n")))
        (let [publication (html/publish! directory graph asset (dissoc document :graph))]
          (install! base "run.edn" (str encoded "\n"))
          {:artifact (:artifact document) :graph (:artifact publication) :viewer (:viewer publication)
           :file "run.edn" :html "index.html"}))
      (catch FileAlreadyExistsException _ (refuse! :archive-output-exists))
      (catch IllegalArgumentException _ (refuse! :archive-output-policy))
      (catch java.io.IOException _ (refuse! :archive-output-io))
      (catch UnsupportedOperationException _ (refuse! :archive-output-io))
      (catch SecurityException _ (refuse! :archive-output-io)))))
(m/=> publish! [:=> [:cat inputs/Root ac/Document viewer/Asset] Publication])

(defn- excluded-output!
  "Require an export path covered by each supplied checked policy, including observed policies."
  [relative policy document]
  (doseq [candidate (cons policy (keep #(get-in document [:provenance % :snapshot :policy]) [:before :after]))]
    (repository/require-policy! candidate)
    (when-not (some #(repository/beneath? % relative) (:excluded candidate))
      (refuse! :archive-output-policy)))
  nil)
(m/=> excluded-output! [:=> [:cat r/Path rc/Policy ac/Document] :nil])

(defn- fresh-directory!
  "Create one fresh destination below a real repository root, refusing every symlink component.
   Parent directories may be created; existing destinations refuse, even when empty."
  [directory relative]
  (let [root (.toRealPath (Path/of directory (make-array String 0)) (make-array LinkOption 0))
        parts (str/split relative #"/")
        parent (reduce (fn [^Path path part]
                         (let [child (.resolve path ^String part)]
                           (when (Files/isSymbolicLink child) (refuse! :archive-output-policy))
                           (when-not (Files/exists child no-follow) (Files/createDirectory child (make-array FileAttribute 0)))
                           (when-not (Files/isDirectory child no-follow) (refuse! :archive-output-policy))
                           child)) root (butlast parts))
        destination (.resolve ^Path parent ^String (last parts))]
    (Files/createDirectory destination (make-array FileAttribute 0))
    (str destination)))
(m/=> fresh-directory! [:=> [:cat inputs/Root r/Path] inputs/Root])

(defn export!
  "Deliberately export a reviewed archive into a fresh excluded repository-relative directory.
   The caller supplies the actual input-protection policy; every observed policy must also exclude
   this path. Export does not rerun gates, upgrade scope, stage files, or alter the judged revision.
   Commit the resulting run.edn, graph.edn and self-contained index.html deliberately after review."
  [directory relative policy document asset]
  (archive/require-valid! document)
  (excluded-output! relative policy document)
  (try (publish! (fresh-directory! directory relative) document asset)
       (catch FileAlreadyExistsException _ (refuse! :archive-output-exists))
       (catch IllegalArgumentException _ (refuse! :archive-output-policy))
       (catch java.io.IOException _ (refuse! :archive-output-io))
       (catch UnsupportedOperationException _ (refuse! :archive-output-io))
       (catch SecurityException _ (refuse! :archive-output-io))))
(m/=> export! [:=> [:cat inputs/Root r/Path rc/Policy ac/Document viewer/Asset] Publication])
