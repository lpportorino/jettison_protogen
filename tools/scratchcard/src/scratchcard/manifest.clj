(ns scratchcard.manifest
  "The run manifest — the machine-readable index a reader consults FIRST.

  MALLI HERE IS AN ENFORCED CALL SITE, NOT AN ANNOTATION. This repo carries
  two malli populations and they behave differently: `m/=>` arrow specs are
  documentation everywhere except one instrumented tree, while an explicit
  `m/validate` at a call site throws for real. `validate!` is the second kind.
  Nothing about this schema is advisory.

  THE MAP IS CLOSED, AND UNKNOWN KEYS ARE REFUSED RATHER THAN IGNORED. Every
  registry in this repo works that way for one reason: a key nothing reads
  looks armed and does nothing. The sibling fleet records the sharper version
  of the same bug — a handler destructured what it knew, the rest evaporated,
  and the call returned a normal success envelope for work it had not done.
  A manifest is exactly that shape of surface, so it refuses.

  IT IS WRITTEN SORTED AND PRETTY-PRINTED so two runs diff minimally. The
  committed golden manifests are written the same way and for the same reason:
  an unordered map re-serialises differently on every run and buries the one
  line that actually changed.

  WHAT MAKES IT USEFUL IS `:lanes`, WHICH IS EASY TO OMIT. Renders and hashes
  alone cannot distinguish a pixel regression from a threshold edit. Recording
  the armed producer roster, the resolved thresholds and a digest of the
  classification table is what lets a diff attribute a change to JUDGEMENT
  rather than to the renderer."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   (java.io File PushbackReader)))

(def manifest-name "manifest.edn")

(def ^:private non-blank-string
  [:string {:min 1}])

(def ^:private run-schema
  [:map {:closed true}
   [:id non-blank-string]
   [:utc non-blank-string]
   [:card non-blank-string]
   [:status [:enum :ok :error]]
   [:elapsed-ms [:maybe number?]]
   ;; Present ONLY on :error. A status of :error with no reason is the shape
   ;; that makes a failed run undiagnosable months later.
   [:error {:optional true} [:map [:code non-blank-string] [:message string?]]]])

(def ^:private render-schema
  [:map {:closed true}
   ;; The cell label is what every other artefact is keyed by — the PNG
   ;; filename, the findings stamp, the report row. Without it a reader has to
   ;; reconstruct it from four fields and hope the recipe matches.
   [:cell [:string {:min 1}]]
   [:family [:enum 0 1 2]]
   [:dark [:enum 0 1]]
   [:w pos-int?]
   [:h pos-int?]
   [:bp [:int {:min 0 :max 3}]]
   ;; The resolved LVGL display tier. Recorded because the theme silently
   ;; drops a tier below a size threshold, and a silent tier change reads as a
   ;; theme bug to anyone who cannot see this field.
   [:disp-tier [:maybe non-blank-string]]
   [:status [:enum :ok :error]]
   [:fb-sha256 {:optional true} [:re #"[0-9a-f]{64}"]]
   [:png {:optional true} non-blank-string]
   [:dump {:optional true} non-blank-string]
   [:stats {:optional true} [:map-of keyword? any?]]
   [:error {:optional true} [:map [:code non-blank-string] [:message string?]]]])

(def ^:private lanes-schema
  [:map {:closed true}
   [:producers [:sequential keyword?]]
   [:thresholds [:map-of keyword? any?]]
   [:caps [:map-of keyword? any?]]
   ;; A digest rather than the table: the table is large and its IDENTITY is
   ;; what a diff needs.
   [:classes-sha non-blank-string]
   [:declined [:sequential keyword?]]])

(def schema
  "The manifest's shape. Closed at the top level and in every block a writer
  controls."
  [:map {:closed true}
   [:run run-schema]
   [:protogen [:map-of keyword? any?]]
   [:wasm [:map-of keyword? any?]]
   [:toolchain [:map-of keyword? any?]]
   [:protocol [:map-of keyword? any?]]
   [:host [:map-of keyword? any?]]
   [:matrix [:map-of keyword? any?]]
   [:input [:map-of keyword? any?]]
   [:lanes lanes-schema]
   [:renders [:sequential render-schema]]
   [:findings [:map-of keyword? any?]]])

(def ^:private top-level-keys
  ;; Derived from `schema` rather than restated: a key added above must not
  ;; need a second edit here to appear in the error message.
  (into (sorted-set) (map first) (drop 2 schema)))

(defn explain
  "A human-readable explanation of why `manifest` is invalid, or nil."
  [manifest]
  (when-let [err (m/explain schema manifest)]
    (me/humanize err)))

(defn validate!
  "Return `manifest`, or throw naming what is wrong AND what is accepted.

  Naming the accepted key set is the difference between an error a caller can
  act on and one that sends them reading this source."
  [manifest]
  (if-let [problem (explain manifest)]
    (throw (ex-info (str "invalid run manifest: " (pr-str problem)
                         " — accepted top-level keys: " (str/join ", " (map name top-level-keys)))
                    {:error "INVALID_MANIFEST"
                     :problem problem
                     :accepted (vec top-level-keys)}))
    manifest))

(defn- deep-sort
  "Recursively convert every map to a sorted map, so serialisation is stable.

  Two runs that differ in one field must produce a one-line diff, not a
  reshuffled file."
  [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [k (deep-sort v)])) x)
    (vector? x) (mapv deep-sort x)
    (seq? x) (mapv deep-sort x)
    :else x))

(defn write!
  "Validate `manifest` and write it into `run-dir`. Returns the file.

  VALIDATES BEFORE WRITING, so an invalid manifest never reaches disk where a
  later reader would have to cope with it."
  ^File [^File run-dir manifest]
  (validate! manifest)
  (let [f (io/file run-dir manifest-name)]
    (io/make-parents f)
    (with-open [w (io/writer f)]
      (pp/pprint (deep-sort manifest) w))
    f))

(defn read-manifest
  "Read and VALIDATE the manifest in `run-dir`.

  Named `read-manifest` rather than `read` so it does not shadow
  `clojure.core/read` — a shadowed core var is the class this repo has been
  bitten by twice, where a missed reference silently resolves to the core
  function and dies at runtime rather than at lint time.

  Validating on read as well as on write is deliberate. The file sits in a
  directory a human can edit, and a manifest that has drifted out of shape
  must fail here rather than produce a confusing diff three steps later."
  [^File run-dir]
  (let [f (io/file run-dir manifest-name)]
    (when-not (.isFile f)
      (throw (ex-info (str "no " manifest-name " in " run-dir)
                      {:error "NO_MANIFEST" :run-dir (str run-dir)})))
    (with-open [r (PushbackReader. (io/reader f))]
      (validate! (read r)))))
