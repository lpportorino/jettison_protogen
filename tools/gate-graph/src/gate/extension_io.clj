(ns gate.extension-io
  "Explicit paired report I/O and escaped offline presentation; core-only reads make no completeness claim."
  (:require [clojure.string :as str]
            [gate.admission :as admission]
            [gate.archive-io :as archive-io]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.extension :as extension]
            [gate.extension-contract :as ec]
            [gate.report :as report]
            [gate.report-io :as reader]
            [gate.viewer-asset :as viewer]
            [hiccup2.core :as h]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path LinkOption OpenOption StandardOpenOption FileAlreadyExistsException]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)
(def limits (assoc admission/default-limits :bytes (inc (:sidecar-bytes ec/limits))))
(def Publication [:map {:closed true} [:core/artifact c/Digest] [:artifact c/Digest]
                  [:core/file [:= "run.edn"]] [:file [:= "extensions.edn"]]
                  [:html [:= "extensions.html"]] [:viewer c/Digest]])
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn read-pair!
  "Require both explicit files; unknown extensions or a missing sidecar never downgrade to core-only success."
  [context core-path sidecar-path]
  (extension/require-context! context)
  (let [core (reader/read-archive! core-path admission/default-limits)
        sidecar (reader/read-text! sidecar-path (inc (:sidecar-bytes ec/limits)))]
    (extension/decode context core sidecar limits)))
(m/=> read-pair! [:=> [:cat extension/ContextInput reader/PathName reader/PathName] extension/Pair])

(defn render
  "Render an already verified pair with escaped static observations and the unchanged offline core viewer.
   The sidecar binds the core, not vice versa. A supplied callback/source declaration is not code attestation."
  [pair asset]
  (extension/require-pair! pair)
  (when-not (and (m/validate viewer/Asset asset)
                 (= (:digest asset) (canonical/sha256 (:javascript asset)))) (ec/refuse! :extension-output))
  (let [core (:core pair) sidecar (:sidecar pair)
        base (report/render (:graph core) (:javascript asset) (dissoc core :graph))
        _ (when-not (str/includes? base "</main>") (ec/refuse! :extension-output))
        section (str (h/html
                      [:section {:id "report-extensions" :aria-label "Typed report observations"}
                       [:h2 "Typed report observations"]
                       [:p "Observations do not change the core verdict. This sidecar binds the core archive; core-only reads do not establish extension completeness."]
                       [:p {:id "extension-core-identity"} (:artifact core)]
                       [:p {:id "extension-identity"} (:artifact sidecar)]
                       (for [observation (:observations sidecar)]
                         [:details {:class "extension-observation"}
                          [:summary (str (:observation/id observation) " · " (:extension/id observation)
                                         " v" (:extension/version observation))]
                          [:p (str "Schema: " (:schema/fingerprint observation))]
                          [:p (str "Subject: " (get-in observation [:subject :id]) " · Source: " (:source observation))]
                          (if-let [projection (:projection observation)]
                            [:section [:h3 (:title projection)]
                             (for [field (:fields projection)]
                               [:p (str (:label field) ": " (get-in field [:value :value])
                                        (when-let [unit (get-in field [:value :unit])] (str " " unit)))])
                             (when (:text projection) [:p (:text projection)])
                             [:p "Projection implementation source is caller-declared:"]
                             [:pre (canonical/encode-portable (:provenance projection) (:projection-bytes ec/limits))]]
                            [:p "Structural observation; no producer summary."])
                          [:pre {:class "extension-payload"} (canonical/encode-portable (:payload observation) (:payload-bytes ec/limits))]])]))
        html (str/replace-first base "</main>" (str section "</main>"))]
    (when (> (canonical/utf8-size html) report/max-html-bytes) (ec/refuse! :extension-limit))
    html))
(m/=> render [:=> [:cat extension/PairInput viewer/Asset] report/Html])

(defn- install!
  "Publish one completed artifact by atomic create-only hard link; partial pairs remain explicit failure evidence."
  [directory filename content]
  (let [base (Path/of directory (make-array String 0))]
    (when-not (Files/isDirectory base no-follow) (ec/refuse! :extension-output))
    (let [temporary (Files/createTempFile base ".extension-" ".tmp" (make-array FileAttribute 0))]
      (try
        (Files/writeString temporary content StandardCharsets/UTF_8
                           (into-array OpenOption [StandardOpenOption/WRITE StandardOpenOption/TRUNCATE_EXISTING]))
        (Files/createLink (.resolve base ^String filename) temporary)
        nil
        (finally (Files/deleteIfExists temporary))))))
(m/=> install! [:=> [:cat reader/PathName [:enum "extensions.edn" "extensions.html"] report/Html] :nil])

(defn publish!
  "Create a core archive plus paired HTML and sidecar, installing the sidecar completion record last.
   No overwrite, fsync durability or multi-file atomicity is promised. Existing files refuse."
  [directory pair asset]
  (extension/require-pair! pair)
  (let [sidecar (str (extension/encode pair) "\n") html (render pair asset)]
    (try
      (let [base (Path/of directory (make-array String 0))]
        (doseq [filename ["extensions.edn" "extensions.html"]]
          (when (Files/exists (.resolve base ^String filename) no-follow) (ec/refuse! :extension-output-exists)))
        (archive-io/publish! directory (:core pair) asset)
        (install! directory "extensions.html" html)
        (install! directory "extensions.edn" sidecar)
        {:core/artifact (get-in pair [:core :artifact]) :artifact (get-in pair [:sidecar :artifact])
         :core/file "run.edn" :file "extensions.edn" :html "extensions.html" :viewer (:digest asset)})
      (catch FileAlreadyExistsException _ (ec/refuse! :extension-output-exists))
      (catch java.io.IOException _ (ec/refuse! :extension-output))
      (catch UnsupportedOperationException _ (ec/refuse! :extension-output))
      (catch SecurityException _ (ec/refuse! :extension-output)))))
(m/=> publish! [:=> [:cat reader/PathName extension/PairInput viewer/Asset] Publication])
